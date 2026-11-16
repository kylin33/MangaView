# MangaView

一个 Kotlin + Jetpack Compose 的漫画聚合阅读器。**不内置任何站点**——站点地址、请求头、
列表/目录/正文的抓取规则全部外置在 JSON 配置里，添加时只需要填一个首页地址，
由程序自动识别出全部选择器。

> 本项目只是一个把网页渲染成阅读器的工具，不提供、不托管、不分发任何漫画内容。
> 抓取规则由使用者自行配置，请遵守目标站点的服务条款与当地法律。

## 功能

- **站点自动识别**：填一个首页地址，程序依次打开首页 → 详情页 → 第一章，
  自己把三组选择器推出来，识别完直接保存即可，不需要懂 CSS 选择器
- **站点内搜索**：走配置里的 `search` 节点，结果以封面网格展示
- **竖向连续阅读**：按需预加载、滑到末尾直接翻下一章，返回键始终一步回到目录
- **阅读进度**：记录看到第几页，下次打开自动回到原位置
- **浏览历史**：书名 / 章节 / 页码 / 来源站点 / 时间，可续读、可"搜同书"、可单条删除
- **换源**：目录页或阅读页随时换到另一个站点。选中后按章号自动对上同一话，
  阅读页换源还会停在原来的位置
- **配置可移植**：粘贴 / 选文件 / 从链接导入，也可以把当前能用的配置分享给别人

## 架构：三个引擎

```
ui/           Compose 页面：只持状态、发命令，不解析 HTML、不拼 URL
 └─ engine/
     ├─ source/   站点适配引擎：各种站点 → 统一的 MangaSource
     ├─ reader/   阅读引擎：只认 List<Page>，不知道站点的存在
     └─ autogen/  自动识别引擎：URL → 一套带置信度的配置
 └─ data/         引擎内部的实现与基础设施（HTTP、解析、存储）
```

依赖方向单向：`ui → engine → data`。站点规则的变化被挡在 Source Engine 里，
阅读体验的变化被挡在 Reading Engine 里，两者互不影响。

### Source Engine

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
- **专用实现扩展点**：某个站结构特殊到规则表达不了（正文走 WebSocket、要登录态、私有 API），
  写一个 `MangaSource` 实现挂上去即可，UI 和阅读引擎都不用动：

```kotlin
sourceEngine.register { config ->
    if (config.id == "special-site") SpecialSiteSource(config) else null
}
```

返回 `null` 表示不管这个配置，引擎自动退回 JSON 实现。

能力缺失用 `SourceUnsupportedException` 表达，和网络错误区分开 ——
UI 才能给出「换个源」而不是「检查网络」。

### Reading Engine

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

### Auto Generator

```
URL → 首页 HTML → 推断列表选择器 → 进详情页 → 推断章节选择器
    → 进第一章 → 推断图片选择器 → 生成 JSON → 用正式解析器验证
```

在此之上补了**置信度**：同一站点可能推出好几套都「勉强能抓」的规则，
需要有一个数说明哪套更可信、哪一步最可疑。

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
| 限制同时解码 | `bitmapFactoryMaxParallelism(2)` + 解码调度器并行度 2，两道闸门 |
| 有界 LRU | 内存缓存按堆 **18%** 封顶，磁盘 256MB；key 带屏宽，换尺寸不会混用 |
| 请求去重 | 同一地址正在飞就不再入队（引擎按页索引去重，请求层按地址去重） |
| 失败重试 | 只有超时/断流/5xx/429 重试，最多 3 次退避；证书错误和 403 **绝不重试** |
| 离开页面取消 | 阅读页 `onDispose` 取消预加载；`AsyncImage` 离开组合时自动取消自己的请求 |
| 切章释放 | 新章加载完成后 `retainOnly(urls)`：取消旧章在飞的请求 + 从内存缓存摘掉旧章位图 |
| 内存压力 | `Application.onTrimMemory` 转发给 Coil，并停掉投机性的预加载；`onLowMemory` 清空缓存 |
| 滚动不跳 | 记住每页宽高比（`PageSizeCache`），图片到达前先把高度占好 |

**没有自己写解码器**：`PageBoundsDecoder` 只做「探尺寸 → 改 `Options` → 交回 Coil 的
`BitmapFactoryDecoder`」，采样、EXIF、色彩空间、硬件位图仍然由 Coil 处理。任何一步
出意外（流不支持 `peek`）都退回原始 `Options`，优化不会变成新的失败点。

仍有的取舍：

- 解码一张 2000 宽的图时，`BitmapFactory` 必须先把中间位图解出来再缩放，
  单张的瞬时峰值无法消除——能控制的是**同时解几张**和**缓存里留几张**；
- 没有做真正的分块（按区域解码整卷长图）：那需要自定义 `Fetcher` + 分片缓存，
  在「不做完整解码系统」的前提下收益不划算，先用像素预算把最坏情况挡住。

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

1. **证书错误不自动重试**。握手失败是确定性结论，重试只会把真实原因拖成"网络异常"。
2. **证书错误不自动放宽**。没有弹窗确认，就绝不会生成 `trustAllCerts: true`。
3. **明文 HTTP 必须有配置授权**。`JsoupHttpClient` 在发请求前拦下所有未授权的 `http://`，
   报「已阻止不安全的明文连接」而不是「网络异常」。

配置解析层同样把关：`baseUrl` 写成 `http://` 却没有 `"allowPlainHttp": true` 会直接拒绝加载。

## 构建

```bash
./gradlew assembleDebug       # 产物 app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest   # 单元测试（默认不联网）
```

工具链：Gradle 9.8.2 · AGP 9.5.0 · Kotlin 2.4.21 · compileSdk 37.2 · minSdk 24 · targetSdk 36

几个容易踩的坑：

- AGP 9 已内置 Kotlin 支持，**不要**再应用 `org.jetbrains.kotlin.android` 插件
- `androidx.core 1.19.x` 要求 `compileSdk >= 37`，所以这里是 `compileSdk = 37` + `compileSdkMinor = 2`
- Kotlin 的块注释可嵌套，注释里写 `sources/*.json` 这种路径会把 `/*` 当成嵌套注释开头，
  触发「Unclosed comment」

### 实连测试

`SourceAutoGeneratorLiveTest` 会真的去抓一个站点，验证「识别结果拿去做正式抓取真能用」。
仓库里不写死任何站点，域名从系统属性传入，不传就跳过：

```bash
./gradlew testDebugUnitTest --tests "*SourceAutoGeneratorLiveTest*" -DliveSite=https://example.com
```

识别算法是启发式的，读代码判断不了对不对，改完最好拿一个真实站点跑一遍。

## 使用

1. 左侧抽屉 → **添加站点** → 填站点首页地址 → **开始识别**
2. 识别过程会逐步显示进度：

   ```
   · 打开首页
   · 已连接（HTTPS）
   · 首页识别到 59 部作品
   · 搜索可用（/search?keyword={keyword}）
   · 打开详情页
   · 识别到 75 个章节
   · 打开第一章
   · 识别到 253 张图片
   ```

3. 识别结果会填进下方的 JSON 框，能看到程序推出来的具体选择器，需要时可以手动改。
4. 保存后回到首页就是该站点的排行榜。

### 首页没有排行榜的站点

有些站点首页整屏由脚本渲染，抓不到列表但有可用的搜索。这种情况识别器会降级成
**仅搜索配置**，首页提示「这个站点暂时找不到排行榜，请通过搜索观看漫画」，
并给一个直接去搜索的按钮。

### 换源

目录页右上角「换源」，阅读页右上角「⋮ → 换源」。选中目标站点后：

- 用**书名**去目标站搜索（书名可以改，繁体 / 译名不同很常见）
- 结果按标题相关度排序，全都不沾边时会红字提示「多半是推荐位，先确认书名」
- 从阅读页换源时，会抓一次目标站目录，**按章号对上同一话就直接读那一话**
- 搜索阶段不切换全局站点，只有真正选中才切——用户只是「看一眼有没有」不该影响首页

## 站点配置 JSON

| 位置 | 用途 | 优先级 |
| --- | --- | --- |
| `app/src/main/assets/sources/*.json` | 随 APK 打包的配置（仓库里只放一份模板） | 低 |
| 设备上 `filesDir/sources/*.json` | App 内添加/导入的配置 | 高（同 id 覆盖内置） |

App 启动时扫描两个目录全部加载进左侧抽屉。单个文件写错不影响其它站点，
错误会显示在抽屉底部。

### 顶层字段

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `id` | 是 | 唯一标识，缺省时用文件名 |
| `name` | 是 | 抽屉里显示的名字 |
| `baseUrl` | 是 | 站点根地址，不要带末尾斜杠 |
| `enabled` | 否 | 默认 `true`。`false` 会灰显在抽屉里但不可选 |
| `description` | 否 | 抽屉里的一行副标题 |
| `userAgent` | 否 | 覆盖默认 UA |
| `timeoutMs` | 否 | 默认 15000，限制在 3000–60000 |
| `referer` | 否 | 请求页面时的 Referer 模板，默认 `{baseUrl}/` |
| `trustAllCerts` | 否 | 默认 `false`。关闭证书校验，**显式例外**，见「连接安全」 |
| `allowPlainHttp` | 否 | 默认 `false`。允许明文 HTTP，`baseUrl` 是 `http://` 时必填 |
| `home` / `search` / `detail` / `reader` | 前三个必填 | 四组抓取规则，见下 |

### 四组规则

```jsonc
"home": {            // 首页列表（搜索结果页结构相同，复用同一套字段）
  "path": "/",                              // 相对 baseUrl 的路径，也可以直接写绝对地址
  "item": "img[data-original], img.lazy",   // 每一「条」的 CSS 选择器
  "link":  { "select": "^",  "attr": "href" },              // 详情页链接
  "cover": { "select": "",   "attr": "data-original|src" }, // 封面图
  "title": { "select": "",   "attr": "alt" }                // 标题
},
"search": {          // 可选。没有这项时该站点不支持搜索
  "path": "/search?keyword={keyword}",      // {keyword} 会被 URL 编码后替换
  "item": "div.comic-item", "link": { ... }, "cover": { ... }, "title": { ... }
},
"detail": {          // 详情页 -> 章节列表
  "item": "ul#chapter-list li a",
  "chapterUrl":   { "select": "", "attr": "href" },
  "chapterTitle": { "select": "", "attr": "text" }
},
"reader": {          // 阅读页 -> 本章图片
  "item": "div.reader img",
  "image":   { "select": "", "attr": "data-original|data-src|src" },
  "referer": "{pageUrl}"                    // 图片防盗链的 Referer，默认本章地址
}
```

章节不在 DOM 里（写在 `<script>` 里）的站点，可以在 `detail` 里用 `script` 节点取数：

```jsonc
"detail": {
  "item": "",
  "script": {
    "pattern": "initIntroData\\((\\[.*?\\])\\)",
    "listPath": "0.data",
    "urlTemplate": "/chapter/{id}",
    "urlField": "id",
    "titleField": "chapter_name"
  }
}
```

### `select` 的三种写法

| 写法 | 含义 |
| --- | --- |
| `""` | 元素自身（`item` 命中的那个节点） |
| `"^"` | 向上找最近的 `<a>`，适合「封面图被链接包住」的列表 |
| 其它 | CSS 选择器，在该元素内部找第一个匹配，如 `"a"`、`".title"`、`"h3 a"` |

### `attr` 的写法

| 写法 | 含义 |
| --- | --- |
| `text` | 文本内容 |
| `html` | 内部 HTML |
| `href` / `src` / `data-original` | 取该属性 |
| `data-original\|src` | 多个候选，按顺序取第一个非空 |
| `abs:href` | 让 jsoup 补全成绝对地址（页面有 `<base>` 标签时比手写拼接更准） |

### 模板占位符

| 占位符 | 展开为 |
| --- | --- |
| `{baseUrl}` | 站点根地址，末尾不带斜杠 |
| `{pageUrl}` | 当前请求的页面地址（图片 Referer 就靠它） |
| `{keyword}` | 仅 `search.path` 支持，搜索词经 URL 编码 |

### 导入现成配置

抽屉 → **添加站点**，四条通道，都不需要 adb、也不需要存储权限：
粘贴 JSON（会自动剥掉 markdown 代码围栏）、选文件、从链接、编辑已有配置。

**保存前先点「试抓首页」**：用未保存的配置真跑一次首页，显示解析到几部作品 + 前三条标题。
抓不出东西说明选择器不对。这是判断配置能不能用的唯一可靠方式。

### 命令行导入

```bash
adb push my-site.json /data/local/tmp/
adb shell run-as com.example.hellocompose cp /data/local/tmp/my-site.json files/sources/
adb shell am force-stop com.example.hellocompose
```

## 自动识别是怎么做的

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

**结构一致性**（`StructureSignature`）是 D 的来源：给每个条目算一份「标签名 + 深度」的
结构签名（不掺 class 与文本，避免 `first` / `odd` 这类可变 class 干扰），再看这一组
条目里有多少个和主流结构相似。相似判定用序列编辑距离、阈值 0.8 —— 真实模板常在某些
条目上多一个「NEW」角标，全等比较会把它算成另一种结构。一个过宽的选择器
（`div.grid > div` 同时框进作品卡和横幅）条数很好看，但一致性只有 0.5，
会被结构惩罚压下去。

关键点是打分用的就是正式抓取那条代码路径，所以「识别通过」和「实际能抓」是同一件事，
不会出现「识别时看着对、保存后空白」。

已经处理过的情况：

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
| 搜索地址不固定 | 依次试 query 与路径式共 11 种常见形态，命中即停 |
| 搜索关键字被忽略、悄悄返回首页热门 | 三态判定：换个词结果不变 → 判定该地址无效，不写进配置 |
| 页面底部有几十张推荐位封面 | 用「图片文件名去重率」识别，正文图文件名互不相同 |
| 首页抓不到列表 | 降级为「仅搜索」配置，但目录和图片必须验证可用 |
| 人机验证 / 需要登录 / 正文加密接口下发 | 直接报错并说明原因，不会生成一份假配置 |

**识别不出来的情况**（明确报错，不会硬生成假配置）：整屏 JS 渲染且搜索也不可用、
正文由加密接口下发、必须登录才能看。

### 回归夹具

识别算法是启发式的，改一条打分规则很难靠读代码判断「有没有让别的站点退化」。
实连测试能给出真实反馈，但站点一改版、一限流就红，跑几次之后人就学会无视它了。
所以 `app/src/test/resources/fixtures/` 下维护了一组固定 HTML，每组模拟一类结构：

| 夹具 | 覆盖的结构 |
| --- | --- |
| `site-normal` | 封面网格 + 章节列表 + 正文图，详情页和阅读页都带推荐位干扰 |
| `site-lazy-image` | `src` 是占位 gif、真实地址在 `data-original`；末尾两页直接给 `src` |
| `site-background-image` | 封面没有 `<img>`，地址在 `<a data-original>` 上靠背景图显示 |
| `site-script-chapters` | 章节只在 `<script>` 的 JSON 里，DOM 容器是空的 |
| `site-multi-domain` | 首页在 portal，作品链接全指向 mirror 域名 |
| `site-javascript-href` | 章节 `href` 是 `javascript:void(0)`，真实地址在 `data-href` |
| `site-split-chapters` | 章节拆成两个 tab，各 12 章，必须合起来才是完整目录 |
| `site-noise-blocks` | 广告位排在最前（5 条无标题）+ 热门 8 部 + 最新 30 部，必须选对板块 |
| `site-mixed-grid` | 同一容器里 12 张作品卡 + 8 条无标题横幅，靠结构一致性区分 |
| `site-search-only` | 首页排行榜由脚本渲染，只能靠搜索进入 |
| `site-scripted-reader` | 正文由接口下发 —— 应该干净失败并说明原因 |
| `site-invalid` | 非漫画站（纯文字列表）—— 不该生成配置 |

每组含 `site.json`（入口地址、地址路由表、期望值）和各阶段 HTML。测试跑的是完整的
`generate()`，断言分两层：

1. **识别结果**：条数、标题填满率、章节像不像章节、图片文件名去重率、`baseUrl` 有没有对齐；
2. **产物自洽**：拿生成的配置回放同一批 HTML，必须抓出和识别时一样多的数据。

第二层是关键。只断言「识别成功」很容易退化成「随便生成一份看起来完整的配置」，
回放能证明这份配置真的抓得到东西 —— 也就是用户在 App 里会看到的内容。

新增一类结构时加一个目录即可，不用改测试代码：

```
app/src/test/resources/fixtures/site-xxx/
├── site.json          # baseUrl / input / routes / expect
├── home.html
├── detail.html
└── reader.html        # 有搜索的站再加 search.html
```

`routes` 按顺序做 `exact:` / `prefix:` 匹配，没登记的地址返回 404 —— 不做「静默返回首页」，
否则「搜索地址根本没实现」这类问题会被掩盖成一片绿色。搜索页里的 `{{keyword}}`
会替换成当次关键词，用来验证「换个词结果变不变」这条判据。

```bash
./gradlew :app:testDebugUnitTest --tests "*AutoGeneratorFixtureTest*"
```

十几秒跑完，改完识别算法先看它有没有红。

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
│       ├── ImageSignals            图片地址载体判定（img / data-* / 背景图共用一套语义）
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
│   │   ├── Dom                    结构分析底层工具
│   │   └── RuleEvaluator          select/attr 语义的唯一实现
│   ├── remote/                     JsoupHttpClient（严格 TLS + 明文闸门）、MangaScraper、PageParser
│   │   └── TlsDiagnosis            证书错误分类 + 只读探针（读证书，不取内容）
│   ├── repository/                 MangaRepository（选站点、统一异常、首页缓存）
│   ├── history/                    ReadingRecord、HistoryRepository（进度 + 浏览记录）
│   ├── settings/                   SettingsRepository（DataStore）
│   └── image/                      图片请求层
│       ├── MangaImageLoader        全局 Coil 配置：LRU 上限、解码闸门、磁盘缓存
│       ├── ImageRequestManager     请求去重 / 重试 / 章节释放 / 缓存 key
│       ├── PageBoundsDecoder       探原始尺寸 → 像素预算 → 交给 Coil 解码
│       ├── PageSampling            采样与预算的纯函数（单测覆盖）
│       ├── RetryPolicy             哪些错误值得重试（纯函数）
│       ├── PageSizeCache           宽高比 LRU，图片到达前占好高度
│       ├── ImageErrorText          失败原因的人话文案（纯函数）
│       ├── MangaImageRequests      封面等非页面图的请求工厂
│       └── CoilPagePreloader       阅读引擎的预加载/释放适配器
└── ui/
    ├── home/ detail/ reader/ search/ history/ source/
    ├── components/                 SourceDrawer、SourceSwitchSheet、ContinueReadingCard…
    ├── navigation/ theme/
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
