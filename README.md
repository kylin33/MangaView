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
├── data/
│   ├── model/                      Manga、Chapter
│   ├── source/                     站点配置与自动识别
│   │   ├── SourceConfig/Parser/Repository/Json
│   │   ├── SourceAutoDetector      结构识别：HTML → 候选选择器
│   │   ├── SourceAutoGenerator     三步识别编排 + 降级重试
│   │   ├── ScriptData              内联脚本 JSON 定位与配平
│   │   ├── ChapterMatch            跨站章节匹配
│   │   └── RuleEvaluator           select/attr 语义的唯一实现
│   ├── remote/                     JsoupHttpClient、MangaScraper、PageParser
│   ├── repository/                 MangaRepository（按当前站点取数、统一异常）
│   ├── history/                    ReadingRecord、HistoryRepository（进度 + 浏览记录）
│   ├── settings/                   SettingsRepository（DataStore）
│   └── image/                      MangaImageRequests
└── ui/
    ├── home/ detail/ reader/ search/ history/ source/
    ├── components/                 SourceDrawer、SourceSwitchSheet、ContinueReadingCard…
    ├── navigation/ theme/
```

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
