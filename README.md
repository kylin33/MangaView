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
| 滚动位置恢复 | 暴露 `restoreIndex`，UI 消费后调 `consumeRestore()` 复位 |

换图片库只改 `CoilPagePreloader` 一个适配器；引擎本身的「恢复位置 / 预加载窗口 / 翻章」
都能用假的 loader 在纯 JVM 里测（见 `ReadingEngineTest`）。

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
| `trustAllCerts` | 否 | 默认 `false`。站点证书链不完整时才开 |
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

页面里的列表型内容有共同特征——同一容器下有一批结构相同的子元素。所以先从 DOM 里枚举
「容器 + 子元素」的候选结构，再用**运行时的同一个解析器**（`PageParser`）逐个真的解析一遍，
按条数、标题填满率、链接有效率打分，取最高分的。

关键点是打分用的就是正式抓取那条代码路径，所以「识别通过」和「实际能抓」是同一件事，
不会出现「识别时看着对、保存后空白」。

已经处理过的情况：

| 情况 | 处理 |
| --- | --- |
| 证书链不完整 | 依次试 https → https(放宽证书) → http → http(放宽证书)，成功的方案写进 `trustAllCerts` |
| 首页域名和作品链接域名不同 | 以作品链接的实际域名为准，自动切换 `baseUrl` |
| 封面不是 `<img>`，而是 `<a data-original>` 背景图 | 把「有图」从「有 img 标签」放宽为「有图片地址载体」 |
| 首页拆成十几个小板块 | 除「单个板块」外还尝试「全站封面图」，取覆盖更全的那个 |
| 正文图每张一个容器 | 分组只按标签 + class，不掺唯一 id |
| 章节写在 `<script>` 的 JSON 里 | 从内联脚本取数组（括号配平防嵌套截断），但必须实开第一章抓到图才采信 |
| 搜索地址不固定 | 依次试 query 与路径式共 11 种常见形态，命中即停 |
| 搜索关键字被忽略、悄悄返回首页热门 | 三态判定：换个词结果不变 → 判定该地址无效，不写进配置 |
| 页面底部有几十张推荐位封面 | 用「图片文件名去重率」识别，正文图文件名互不相同 |
| 首页抓不到列表 | 降级为「仅搜索」配置，但目录和图片必须验证可用 |
| 人机验证 / 需要登录 / 正文加密接口下发 | 直接报错并说明原因，不会生成一份假配置 |

**识别不出来的情况**（明确报错，不会硬生成假配置）：整屏 JS 渲染且搜索也不可用、
正文由加密接口下发、必须登录才能看。

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
│       ├── SourceAutoGenerator     三步识别编排 + 降级重试 + 置信度汇总
│       ├── SourceAutoDetector      结构识别：HTML → 候选选择器
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
│   ├── remote/                     JsoupHttpClient、MangaScraper、PageParser
│   ├── repository/                 MangaRepository（选站点、统一异常、首页缓存）
│   ├── history/                    ReadingRecord、HistoryRepository（进度 + 浏览记录）
│   ├── settings/                   SettingsRepository（DataStore）
│   └── image/                      MangaImageRequests、CoilPagePreloader
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
