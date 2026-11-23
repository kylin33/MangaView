# 架构

MangaView 分成三层：`ui → engine → data`，依赖方向单向。

```
ui/           Compose 页面：只持状态、发命令，不解析 HTML、不拼 URL
 └─ engine/
     ├─ source/   站点适配引擎：各种站点 → 统一的 MangaSource
     ├─ reader/   阅读引擎：只认 List<Page>，不知道站点的存在
     └─ autogen/  自动识别引擎：URL → 一套带置信度的配置
 └─ data/         引擎内部的实现与基础设施（HTTP、解析、存储）
```

站点规则的变化被挡在源适配引擎里，阅读体验的变化被挡在阅读引擎里，两者互不影响。
识别引擎是唯一同时认识「页面结构」和「配置格式」的地方，也只有它会生成配置。

## 源适配引擎

```kotlin
interface MangaSource {
    val id: String
    val name: String
    val capabilities: SourceCapabilities   // canListHome / canSearch / searchOnly

    suspend fun getHome(): List<Manga>
    suspend fun search(keyword: String): List<Manga>
    suspend fun getDetail(url: String): MangaDetail
    suspend fun getChapters(url: String): List<Chapter>
    suspend fun getPages(url: String): List<Page>
}
```

- **`JsonMangaSource`** 是默认实现：把一份 JSON 规则跑起来，结果翻译成标准类型。
  它不属于任何具体站点，换站点只换配置。
- **`SourceEngine`** 是上层唯一入口，负责实例缓存、配置变更后重建、以及路由到专用实现。
- **专用实现扩展点**：某个站特殊到规则表达不了（正文走 WebSocket、要登录态、私有 API），
  写一个 `MangaSource` 实现挂上去即可，UI 和阅读引擎都不用动：

```kotlin
sourceEngine.register { config ->
    if (config.id == "special-site") SpecialSiteSource(config) else null
}
```

返回 `null` 表示不管这个配置，引擎自动退回 JSON 实现。

能力缺失用 `SourceUnsupportedException` 表达，和网络错误区分开 —— UI 才能给出
「换个源」而不是「检查网络」。

## 阅读引擎

```kotlin
class ReadingEngine(
    loader: PageLoader,                 // suspend (chapterUrl) -> AppResult<List<Page>>
    preloader: PagePreloader,           // 通常由图片库实现
    initialChapter: Chapter,
    scope: CoroutineScope,
    positionListener: PositionListener? // 停稳后回调，用于落盘
)
```

它只接收标准化的 `List<Page>`（图片地址 + Referer），因此**一行业务代码都不依赖站点**：

| 职责 | 实现要点 |
| --- | --- |
| 页面加载与调度 | 切章时取消上一章的加载，避免旧响应覆盖新章节 |
| 预加载窗口 | 可见页之后 N 页入队，到最后一页自动截断 |
| 阅读位置 | 恢复上次页码（只用一次）；停稳 600ms 后防抖落盘 |
| 章节切换 | 持有正序目录，上一章/下一章内部完成，返回栈不会堆积 |
| 失败重试 | 重开当前章并回到原页码 |
| 下一章预取 | 离末尾 3 页时预取下一章前 2 页，翻章不白屏 |
| 跨章释放 | 新章页面到达后调 `retainOnly(urls)`，旧章的请求和位图立即放掉 |
| 滚动位置恢复 | 暴露 `restoreIndex`，UI 消费后调 `consumeRestore()` 复位 |

换图片库只改 `data/image` 下的适配器（`CoilPagePreloader` + `ImageRequestManager`）；
引擎本身的「恢复位置 / 预加载窗口 / 翻章」都能用假的 loader 在纯 JVM 里测
（见 `ReadingEngineTest`）。

## 自动识别引擎

### 三步编排

```
URL → 首页 HTML → 推断列表选择器 → 进详情页 → 推断章节选择器
    → 进第一章 → 推断图片选择器 → 生成 JSON → 用正式解析器验证
```

每一步都用正式解析器回放一次，所以「识别通过」和「实际能抓」是同一件事，
不会出现「识别时看着对、保存后空白」。

### 候选生成与评分分离

页面里的列表型内容有共同特征——同一容器下有一批结构相同的子元素。识别分两步，
两步各有独立实现，调权重不用碰 DOM 遍历：

1. **生成候选**（`ListCandidates`）：把所有可能成列表的节点集合都列出来，不做取舍。
   除了「容器 + 重复子元素」，还会试「全站封面图」这种没有统一容器的形态。
2. **给候选打分**（`ListScorer`）：每个候选先算出字段规则，再用**运行时的同一个解析器**
   （`PageParser`）真的解析一遍，然后把事实交给评分器：

   | 分量 | 含义 | 首页权重 | 章节权重 |
   | --- | --- | --- | --- |
   | N | 条目数量（对数饱和，30 条封顶） | 0.20 | 0.30 |
   | I | 图片有效率 | 0.25 | — |
   | T | 标题有效率 | 0.25 | 0.30 |
   | U | 地址有效率 | 0.20 | 0.25 |
   | D | DOM 结构一致性 | 0.10 | 0.15 |

   再叠加惩罚项：结构不一致、标题重复、地址重复、封面指向图标广告、落在导航页脚里、
   选择器过宽、章节名不像章节。

为什么不做「找到第一个满足条件的 DOM 就返回」：首页往往同时有热门、最新、推荐、广告
好几组结构相似（图片 + 链接 + 文本）的板块，先出现的那个纯属排版顺序。
按上面的分数量化后，30 部完整作品会赢过 5 条没有标题的广告位。

### 结构一致性

`StructureSignature` 是 D 的来源：给每个条目算一份「标签名 + 深度」的结构签名
（不掺 class 与文本，避免 `first` / `odd` 这类可变 class 干扰），再看这一组条目里
有多少个和主流结构相似。

相似判定用序列编辑距离、阈值 0.8 —— 真实模板常在某些条目上多一个「NEW」角标，
全等比较会把它算成另一种结构。一个过宽的选择器（`div.grid > div` 同时框进作品卡和横幅）
条数很好看，但一致性只有 0.5，会被结构惩罚压下去。

### 能力探测（Capability-Based Detection）

识别不再假设「站点长成标准模样」。每一阶段先回答「这一项在页面上靠什么承载」，
再按答案选通道，并把结论写进识别报告：

| 阶段 | 可探测到的通道 |
| --- | --- |
| 列表 | 块级重复容器 / 整页平铺封面图 / 内联脚本 JSON |
| 封面 | `img[src]` / 懒加载属性 / CSS 背景图 |
| 标题 | 链接 `title` 属性 / 语义节点 / 可见文字 |
| 章节 | DOM 列表 / 内联脚本 JSON |
| 正文图 | `img` 属性 / CSS 背景图 |

探测只统计**条目集合上的事实**（多少条带 `data-original`、多少条靠背景图），
不做全页统计 —— 全页会被页脚、侧栏、广告稀释，一个「30 条里 28 条懒加载」的列表
可能被算成 40%，于是错误地退回 `src`。

探测结果有两条实际用途：一是给规则挑选一个 0.05 的同分偏向（探测说封面在背景图上，
`style` 候选优先）；二是生成 `weakPoints`，例如「封面写在 CSS 背景图里」「书名只能
从可见文字里取」——这些话直接出现在识别报告里，用户不用猜为什么书名带了更新时间。

能力探测不通过时（`Channel.NONE`）不算失败，只说明这一阶段没有稳定承载方式，
后面靠兜底和置信度如实反映。

### 标题归一化

书名和章节名很少是干净的字符串，`TitleNormalizer` 统一处理三类脏数据：

| 现象 | 处理 |
| --- | --- |
| `14小时前 姨见钟情`（时间/状态徽章） | 剥徽章；但 `14岁的夏天`、`3月的狮子` 这类数字开头的书名不动 |
| `雪山飞狐外传 作者：金先生 更新：第30回` | 从第一个元信息字段处截断 |
| `第1话-阿姨，我` + `title="第1话-阿姨，我们结婚吧"` | 可见文字是 `title` 的前缀时，用更完整的那个 |

反向也要防：`title="书名-第1话"` 且可见文字是「第1话」时，**不能**采信 `title`，
否则章节列表每一行都会重复一遍书名。判据是多出来的部分是否以「第N话」开头。

识别阶段的 `isUsableTitle` 和运行时的 `PageParser` 用同一个函数，避免「识别时判为
合格、跑起来是脏标题」。

### 语义特征

`Semantics` 用来补上「class 名读不懂」这一大片站点。真实站点里 `item-123`、`list_456`、
`box-a1` 这种命名非常常见，但语义并没有消失，它散在别的地方：

| 来源 | 例子 | 用来判断 |
| --- | --- | --- |
| class / id | `book-title`、`chapter-list`、`封面`、`bookTitle`（会按驼峰拆词） | 字段角色 |
| 属性 | `alt="封面"`、`title`、`itemprop=name`、`aria-label` | 字段角色 |
| URL 路径 | `/comic/123`、`/read/101/5`、`/promo/8`、`/author/11` | 详情页 / 章节 / 推广位 |
| 链接文字 | `第 12 话`、`第 3 卷` | 章节 |

用法上刻意保守：**正向只做小幅偏向，负向才是硬否定**。容器叫 `book-list` 加 0.02，
成员地址全指向 `/promo/` 扣 0.10，容器叫 `promo-banner` 扣 0.15。原因是语义判断本身也会错
（广告位的 class 完全可以叫 `book-item`），而「名字里写着 promo」几乎没有反例。
字段选择里同样只做同分偏向：`span.title` 和 `h3` 都取得到干净文本时优先前者，
质量差距大于 6% 时语义不再影响结果。

### 图片地址解析

同一张封面可能出现在七个地方，而 `src` 常常是占位图。所以地址解析独立成
`ImageUrlResolver`，先给每个候选打分再取最高：

| 来源 | 初始优先级 |
| --- | --- |
| `data-original` | 100 |
| `data-src` | 95 |
| `data-lazy-src` / `data-lazyload` | 92 / 91 |
| `data-lazyload-src` / `data-lazy` / `data-aload` | 90 / 89 / 89 |
| `data-echo` / `data-actualsrc` | 88 / 87 |
| `data-ks-lazyload` / `data-ll-src` / `data-lazy-img` | 86 / 86 / 85 |
| `srcset` / `data-srcset` | 85 / 84 |
| `data-url` / `data-bg` / `data-image` | 83 / 82 / 81 |
| `data-poster` / `src` | 80 / 80 |
| `style` 里的 `background-image:url(...)` | 75 |

打分还会修正：落在 `/upload/`、`/content/`、`/pic/` 这类目录加 10，文件名带连续数字加 6，
是 `logo` / `avatar` / `banner` / `.svg` 扣 30，占位图（`data:`、`blank.gif`、`spacer`、
`lazyload`、`px.gif`、`1.gif`、`default.jpg`、1×1）直接判 0 分；元素上声明了 `width`/`height`
且都不超过 48 时，`src` 候选再降 25 分（同元素上有别的候选才降，避免误杀真的小图）。`srcset` 取最大的一档而不是第一个 —— 真实站点常把
「小图预览 + 原图」两档写在一起。

属性链（`attrChain`）只保留过半条目可用的属性并按可靠性排序，**不按覆盖率**：
占位 `src` 往往 100% 存在，按覆盖率排会让它排在 `data-original` 前面。

运行时还有一层兜底：配置里的取值链取到占位图时，回头在这个元素上重新挑一次最可信的地址。
懒加载站点里同一条目偶尔换写法（有的给 `data-src`、有的给 `srcset`），没有这一层就会出现
「大部分封面正常、个别是空白」，而这种问题在配置里完全看不出来。

### 置信度

同一站点可能推出好几套都「勉强能抓」的规则，需要有一个数说明哪套更可信、哪一步最可疑。

首页五项权重（初始值，需用真实站点校准，集中定义在 `ConfidenceScorer`）：

| 检测项 | 权重 |
| --- | --- |
| 列表条目数量 | 20% |
| 标题有效率 | 20% |
| URL 有效率 | 20% |
| 图片有效率 | 20% |
| 详情页及章节验证 | 20% |

整体按「越接近正文越重要」加权：首页 30%、目录 35%、正文 35%。
仅搜索配置因为首页必然为空，额外扣 0.08。

输出会写进配置本身，以后排查「这份配置当时是怎么来的」不用靠记忆：

```json
"confidence": { "overall": 0.984, "home": 1, "detail": 1, "reader": 0.955 }
```

每一项都是**已经真实发生的事实**（解析出多少条、多少条标题非空、第一章是否真打开过），
不做基于页面猜测的加分。识别页会把最弱的一项直接指出来，
例如「最弱环节：文件名去重率 —— 去重率 42%（低说明抓到的是封面墙）」。

### 已处理过的站点形态

| 情况 | 处理 |
| --- | --- |
| 证书链不完整 / 只支持明文 | 停在「等你确认」，弹窗列证书详情；确认后才带上 `trustAllCerts` / `allowPlainHttp` |
| 首页域名和作品链接域名不同 | 以作品链接的实际域名为准，自动切换 `baseUrl` |
| 封面不是 `<img>`，而是 `<a data-original>` 背景图 | 把「有图」从「有 img 标签」放宽为「有图片地址载体」 |
| 首页拆成十几个小板块 | 除「单个板块」外还尝试「全站封面图」，取覆盖更全的那个 |
| 正文图每张一个容器 | 分组只按标签 + class，不掺唯一 id |
| 章节写在 `<script>` 的 JSON 里 | 从内联脚本取数组（括号配平防嵌套截断），但必须实开第一章抓到图才采信 |
| 章节跳转交给 JS（`href="javascript:void(0)"`） | 除 `href` 外还试 `data-href` / `data-url` / `data-link`，并排除 data-* 里的封面图 |
| 章节被拆成多个 tab（连载 / 番外、按卷分块） | 把结构相同且都像章节的块用分组选择器合并，避免目录只抓到一半 |
| 搜索框在页面上，但地址不是 `?keyword=` | 先读 `<form>`：`action` 是请求路径、`method` 决定 GET/POST、`<input name>` 是参数名，隐藏域一并带上 |
| 搜索只能用 POST（关键字不能出现在地址栏） | 配置里用 `method: post` + `keywordParam`，关键字进请求体；识别时也真的发 POST 去验 |
| 页面没有 `<form>`，提交逻辑在 JS 里 | 先从内联脚本里抽「像搜索」的地址和参数名（`location.href='/ss?kw='+…`）；没有再读 1~3 个同源脚本文件（跳过 jquery 这类第三方库）。仍拿生僻词探一次：页面回显该词才算参数生效 |
| 搜索地址不固定 | 依次试 query 与路径式共 17 种常见形态，命中即停；整轮请求有上限，避免把站点打到限流 |
| class / id 完全不可读（`item-123`、`box-a1`） | 靠 `alt`、链接文字与 URL 路径判断字段；广告位靠 `/promo/` 这类路径扣分 |
| `src` 是 1×1 占位图 / base64 | 按属性优先级与地址形态打分，取 `data-original`、`data-src`、`srcset` 里最可信的那个 |
| 只有 `srcset`、没有 `src` | 取密度/宽度最大的一档；`<picture><source>` 也算候选 |
| 封面写在 `style` 的 `background-image` 里 | 从内联样式里抽 `url(...)`；整页背景图分页时把载体自身当作正文图候选 |
| 图片地址是协议相对（`//cdn...`） | 跟随站点自身协议补全，不写死 https |
| 搜索关键字被忽略、悄悄返回首页热门 | 三态判定：换个词结果不变 → 判定该地址无效，不写进配置 |
| 页面底部有几十张推荐位封面 | 用「图片文件名去重率」识别，正文图文件名互不相同 |
| 首页抓不到列表 | 降级为「仅搜索」配置，但目录和图片必须验证可用 |
| 人机验证 / 需要登录 / 正文加密接口下发 | 直接报错并说明原因，不会生成一份假配置 |

### 选择器为什么这么短

自动生成的选择器是给用户长期用的，站点却一直在改版。`html > body > div:nth-child(2) > ul > li`
看着「精确」，实际站方在中间插一条活动横幅就整站失效 —— 用户看到的现象只是
「突然抓不到漫画了」，根本无从判断原因。

所以选择器不是「拼一条看起来对的路径」，而是**先有目标元素、再反推选择器**：

1. 识别阶段已经确定了「这批节点就是列表条目」，把它们当作标准答案；
2. 按稳定性从高到低生成候选：`#id > 条目` → `条目.语义 class` → `#祖先id 条目` →
   `祖先.语义class 条目` → 兜底路径；
3. 每条候选都拿整篇文档重新查一遍，**结果必须恰好是这批元素**（不多不少）才采用。

第 3 步是关键：它让「短」和「准」不再冲突。`li.item` 短，但如果侧栏也有 `li.item`，
查询结果会多出来，直接落选，自动退到 `div.comic-list li.item`。
这样既不会输出 `:nth-child` 这类位置依赖，也不会因为贪短而抓宽。

class 只按稳定性排序、不按「看起来像」选：`active` / `first` / `col-md-6` / `item-123`
这类状态类、布局类、带序号的名字排到最后（但不是不用 —— class 全是 `g-1`/`i-9` 的站点
照样能出来）。生成的规则如果仍然偏脆（叠了 3 个以上 class、层级过深），
识别报告会直接写一条警告，让用户在**还能用的时候**就知道哪一处迟早要坏。

**识别不出来的情况**（明确报错，不会硬生成假配置）：整屏 JS 渲染且搜索也不可用、
正文由加密接口下发、必须登录才能看。

## 图片加载与内存

韩漫的图是「长图 + 高清 + 连续滚动」，一张 2000×5000 的图按原尺寸解出来是
2000×5000×4B ≈ **38MB**，同时解十张就是 381MB —— 堆根本不够。所以图片链路
单独拆了一层，请求和显示分开：

```
Page URL
   │
   ▼
ImageRequestManager        请求去重 / 失败重试 / 章节释放 / 缓存 key
   │
   ▼
Coil ImageLoader           有界 LRU 内存缓存 + 磁盘缓存 + 解码闸门
   │
   ▼
PageBoundsDecoder          按原图尺寸算采样尺寸（超长图兜底）
   │
   ▼
AsyncImage                 只负责显示与失败提示
```

| 措施 | 做法 |
| --- | --- |
| 按屏幕尺寸采样 | 请求尺寸给屏宽，原图 2000 宽只解到 1000 宽；**只缩不放** |
| 对齐 2 的幂采样 | 解码宽度往 `原图/2ⁿ` 上靠（不低于屏宽 85%），让 `BitmapFactory` 一次采样到位：2000 宽的图从「先解 38MB 中间位图再缩」变成直接解 10MB |
| 超长图低内存渲染 | 解码前 `peek` 出原始尺寸，按面积预算（单页 ≤ 600 万像素）继续缩；超出纹理上限（4096）的强制软件位图 |
| 限制同时解码 | `bitmapFactoryMaxParallelism(n)` + 解码调度器并行度 n，两道闸门；n 由设备堆上限给出（1–3） |
| 有界 LRU | 内存缓存按堆 **18%** 封顶，磁盘 256MB；key 带屏宽，换尺寸不会混用 |
| 请求去重 | 同一地址正在飞就不再入队（引擎按页索引去重，请求层按地址去重） |
| 失败重试 | 只有超时/断流/5xx/429 重试，最多 3 次退避；证书错误和 403 **绝不重试** |
| 离开页面取消 | 阅读页 `onDispose` 取消预加载；`AsyncImage` 离开组合时自动取消自己的请求 |
| 切章释放 | 新章加载完成后 `retainOnly(urls)`：取消旧章在飞的请求 + 从内存缓存摘掉旧章位图 |
| 内存压力 | `Application.onTrimMemory` 转发给 Coil，并停掉投机性的预加载；`onLowMemory` 清空缓存 |
| 滚动不跳 | 记住每页宽高比（`PageSizeCache`），图片到达前先把高度占好 |
| 可见页优先 | 预加载走闸门（`PreloadGate`）：可见页在加载时不放行，预加载席位固定 1 个，重试共用同一席位 |
| 设备自适应并发 | 按 `ActivityManager.memoryClass` 给 1–3 路，低内存设备一票否决（`ImageConcurrency`） |
| 失败恢复 | 单页可重试；菜单里能一次重试本章所有失败页，只重试真的失败的那些 |
| 缓存清理 | 面板显示磁盘占用并可整体清空；日常淘汰交给 Coil 按大小做的 LRU |

**没有自己写解码器**：`PageBoundsDecoder` 只做「探尺寸 → 改 `Options` → 交回 Coil 的
`BitmapFactoryDecoder`」，采样、EXIF、色彩空间、硬件位图仍然由 Coil 处理。任何一步
出意外（流不支持 `peek`）都退回原始 `Options`，优化不会变成新的失败点。

还踩过一个坑：`bitmapConfig(ARGB_8888)` 与 `allowHardware(true)` 同写时，显式 config 优先，
等于把硬件位图关掉。所以只保留 `allowHardware(true)`，不显式指定 config。

仍有的取舍：

- 解码一张 2000 宽的图时，`BitmapFactory` 必须先把中间位图解出来再缩放，
  单张的瞬时峰值无法消除——能控制的是**同时解几张**和**缓存里留几张**；
- 没有做真正的分块（按区域解码整卷长图）：那需要自定义 `Fetcher` + 分片缓存，
  在「不做完整解码系统」的前提下收益不划算，先用像素预算把最坏情况挡住。
- **解码并发在构造 `ImageLoader` 时定死**，运行中改不了。所以只能按设备给一个合适的初始值，
  不存在「边跑边调」——面板上显示的并发上限就是开局那个数。

### 优先级是怎么做的

Coil 2 的 `ImageRequest` 没有优先级字段（那是 Coil 3 才有的），所以优先级放在请求层自己做：
`PreloadGate` 记录「当前有几个可见页在加载」，预加载启动前必须等到 0，且同时只允许 1 个预加载。

正在飞的图不会被抢占 —— 断掉一个下了 80% 的请求，重下比等它完成更贵。能控制的是
**谁先开始**，这已经足够：滚动时新进入屏幕的页永远排在预加载前面。

### 监控与调参

埋点放在 `pageRequest` 的 listener 上，这是唯一同时经过可见请求和预加载的地方：

| 指标 | 来源 | 怎么用 |
| --- | --- | --- |
| 耗时 | `onStart` → 终止回调，`SystemClock.elapsedRealtime` | 长图 p90 高是正常的；p50 高要先看命中率 |
| 命中来源 | `SuccessResult.dataSource`（内存 / 磁盘 / 网络） | 命中率低但速度还行 = 图床快；命中率高还慢 = 解码或主线程 |
| 失败原因 | `ImageErrorText` 的分类 + HTTP 码 | 集中 403 是防盗链，该换源；集中超时是站或网络 |
| 并发 | 请求层计数器（开始 +1，终止 -1） | 峰值长期打满说明预加载确实在跟可见页抢 |

几个刻意的口径：

- **取消不算失败**。滚动时列表回收、`retainOnly` 切章都会取消请求，把它们算进分母，
  成功率会随滑动变低，看起来像网络坏了。
- **分位数只看最近 400 条**。早期数据对「现在快不快」没有参考价值；
  平均值仍取全会话。

统计活在内存里（`ImageLoadStats`），重启清零。它是排查工具，不是上报系统 ——
真要长期观察，应该导出而不是偷偷上传。

## 连接安全

默认严格：页面请求完全交给平台的 HTTPS 校验（系统 CA + 主机名），不注入任何自定义 SSL 工厂。

识别站点时，HTTPS 走不通**不会由程序替你决定**：

| 情况 | 行为 |
| --- | --- |
| 证书过期 / 自签名 / 域名不匹配 / 签发者不受信任 | 停下，弹窗给出证书主体、签发者、有效期和系统原因 |
| 站点没有 HTTPS（443 上不是 TLS） | 先探测明文是否可用，可用才提示「只能以明文访问」 |
| 站点返回 403 / 502 等状态码 | 当作「打不开」报错，**不会**因此降级到 HTTP |

只有用户点了「仍然关闭证书校验」或「仍然用明文 HTTP」，才会带着对应例外重新识别一次，
并把例外写进生成的配置：

```jsonc
"trustAllCerts": true,
"securityWarning": "已关闭证书校验（trustAllCerts=true）：无法防范中间人，页面和图片都可能被篡改"
```

例外一旦存在，编辑页顶部会出现红色横幅。`allowPlainHttp` 的处理完全相同。

三条硬规则：

1. **证书错误不自动重试**。握手失败是确定性结论，重试只会把真实原因拖成「网络异常」。
2. **证书错误不自动放宽**。没有弹窗确认，就绝不会生成 `trustAllCerts: true`。
3. **明文 HTTP 必须有配置授权**。`JsoupHttpClient` 在发请求前拦下所有未授权的 `http://`，
   报「已阻止不安全的明文连接」而不是「网络异常」。

配置解析层同样把关：`baseUrl` 写成 `http://` 却没有 `"allowPlainHttp": true` 会直接拒绝加载。

## 目录结构

```
com.example.hellocompose
├── MangaApp.kt / MainActivity.kt   Application + 入口
├── core/                           AppConfig、AppResult、TimeText
├── di/                             AppContainer 手动依赖容器
├── engine/
│   ├── source/                     ★ 站点适配引擎
│   │   ├── MangaSource             统一接口 + Page / MangaDetail / 能力描述
│   │   ├── JsonMangaSource         默认实现：JSON 规则驱动
│   │   └── SourceEngine            实例缓存、配置失效、专用实现注册
│   ├── reader/                     ★ 阅读引擎
│   │   ├── ReadingEngine           加载/预加载/位置/翻章/重试
│   │   └── ChapterOrder            目录顺序归一化（纯函数）
│   └── autogen/                    ★ 自动识别引擎
│       ├── SourceAutoGenerator     三步识别编排 + 严格连接策略 + 置信度汇总
│       ├── PageFetcher             取页接口（默认真连真抓，夹具测试注入本地 HTML）
│       ├── ListCandidates          候选生成：枚举可能的列表节点集合（不评分）
│       ├── ListScoring             候选评分：N/I/T/U/D 五项加权 + 惩罚项
│       ├── StructureSignature      DOM 结构签名与一致性（编辑距离，阈值 0.8）
│       ├── Semantics               语义特征：class/id/alt/URL 路径 → 标题/封面/章节/广告等角色
│       ├── ImageSignals            图片地址载体判定（委托 ImageUrlResolver，识别与运行时同一套语义）
│       ├── SourceAutoDetector      结构识别：HTML → 候选选择器 → 字段规则
│       ├── InsecurePolicy          安全例外：只有用户确认后才会构造出来
│       └── DetectionConfidence     打分模型与权重
├── data/
│   ├── model/                      Manga、Chapter
│   ├── source/                     配置与解析基础设施
│   │   ├── SourceConfig/Parser/Repository/Json   配置模型、解析、读写
│   │   ├── SourceConfidence       随配置保存的置信度
│   │   ├── ScriptData             内联脚本 JSON 定位与配平
│   │   ├── ChapterMatch           跨站章节匹配
│   │   ├── ImageUrlResolver       图片地址解析：src/data-*/srcset/背景图、占位图、优先级打分
│   │   ├── Dom                    结构分析底层工具
│   │   └── RuleEvaluator          select/attr 语义的唯一实现
│   ├── remote/                     JsoupHttpClient（严格 TLS + 明文闸门）、MangaScraper、PageParser
│   │   └── TlsDiagnosis            证书错误分类 + 只读探针（读证书，不取内容）
│   ├── repository/                 MangaRepository（选站点、统一异常、首页缓存）
│   ├── history/                    ReadingRecord、HistoryRepository（进度 + 浏览记录）
│   ├── settings/                   SettingsRepository（DataStore）
│   └── image/                      图片请求层
│       ├── MangaImageLoader        全局 Coil 配置：LRU 上限、解码闸门、磁盘缓存
│       ├── ImageRequestManager     请求构造 / 可见优先 / 重试 / 章节释放 / 埋点
│       ├── ImageLoadStats          加载事件环形缓冲：成功率、分位、命中率、失败归因
│       ├── ImageConcurrency        按设备堆上限给出解码并发
│       ├── PreloadGate             可见页优先的预加载闸门（纯状态机）
│       ├── PageBoundsDecoder       探原始尺寸 → 像素预算 → 交给 Coil 解码
│       ├── PageSampling            采样与预算的纯函数（单测覆盖）
│       ├── RetryPolicy             哪些错误值得重试（纯函数）
│       ├── PageSizeCache           宽高比 LRU，图片到达前占好高度
│       ├── ImageErrorText          失败原因的人话文案（纯函数）
│       ├── MangaImageRequests      封面等非页面图的请求构造
│       └── CoilPagePreloader       阅读引擎的预加载/释放适配器
└── ui/
    ├── home/ detail/ reader/ search/ history/ source/
    ├── stats/                      ImageStatsScreen：图片加载性能面板
    ├── components/                 SourceDrawer、SourceSwitchSheet、ContinueReadingCard…
    └── navigation/ theme/
```

> `ui/source`（添加/编辑站点）仍然直接持有 `SourceConfig`：它的职责就是编辑那份 JSON，
> 属于配置管理而不是阅读链路。首页、详情、阅读三处已经完全不接触规则结构。

## 设计取舍

- **进度和历史是一条记录**。一条 `ReadingRecord` 就是一本漫画的完整状态（书名、封面、
  详情页、来源站点、章节、页码）。拆成两张表只会让同一个动作写两次、还容易不同步。
- **写进度用仓库自管的协程作用域**。`onCleared` 里 `viewModelScope` 已经被取消，
  在那儿写会静默失败，最后一页必丢。
- **换源时搜索不切全局站点**。用传入的配置发请求，只有真正选中才 `select()`。
- **换源选中用一次性事件而不是状态**。换源面板的 ViewModel 跟着返回栈活着，
  把「已选定」放进状态里，第二次打开会重放上次的选择，用户什么都没点就被跳走。
- **重试下沉到 HTTP 客户端**。5xx/超时重试一次放在 `JsoupHttpClient` 里，
  用户翻页也受益，而不是只有识别阶段才重试。
- **站点配置损坏不拖垮启动**。单个文件解析失败只记进错误列表，抽屉底部提示。
