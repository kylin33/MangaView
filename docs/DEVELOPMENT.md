# 开发与构建

## 环境

| 项 | 要求 |
| --- | --- |
| JDK | 17 以上（AGP 9 的硬要求；Android Studio 自带的 JBR 即可） |
| Android SDK | compileSdk 37.2（`androidx.core 1.19.x` 要求 ≥ 37） |
| Gradle | 用仓库自带 wrapper 9.8.2，不要用本机 Gradle |
| 设备 | Android 7.0（API 24）以上 |

## 常用命令

```bash
./gradlew :app:assembleDebug       # 产物 app/build/outputs/apk/debug/app-debug.apk（约 15MB）
./gradlew :app:assembleRelease     # 未签名的 release 包（当前未接 signingConfig，也没开 minify）
./gradlew :app:testDebugUnitTest   # 142 个单元测试，默认不联网
```

## 工具链的坑

- **AGP 9 已内置 Kotlin 支持**，不要再应用 `org.jetbrains.kotlin.android` 插件。
- **`androidx.core 1.19.x` 要求 `compileSdk >= 37`**，所以这里是 `compileSdk = 37`
  加 `compileSdkMinor = 2`（AGP 9 的次版本号单独一个字段）。
- **Kotlin 的块注释可以嵌套**。注释里写 `sources/*.json` 这种路径时，`/*` 会被当成嵌套注释
  的开头，触发「Unclosed comment」。要么写 `sources/∗.json`，要么避开。
- **`-D` 属性要显式透传进测试 JVM**。Gradle 守护进程读得到、fork 出来的测试进程读不到，
  于是 `-DliveSite=...` 看起来设了、测试永远走「跳过」，一片绿但什么都没跑。
  `app/build.gradle.kts` 里的 `systemProperty(...)` 就是为此存在的。

## 测试分四层

识别算法是启发式的，读代码判断不了对不对。四层测试各回答一个问题：

| 层 | 回答的问题 | 数量 | 联网 |
| --- | --- | --- | --- |
| 单元测试 | 纯函数与引擎逻辑对不对 | 142 | 否 |
| 实连测试 | 识别结果拿去做正式抓取真能用吗 | 1（按需） | 是 |
| 回归夹具 `fixtures/` | 这类结构还认得出来吗 | 14 组 | 否 |
| 准确率语料 `corpus/` | 有多准，哪一关退化 | 9 组 | 否 |

### 实连测试

`SourceAutoGeneratorLiveTest` 会真的去抓一个站点。仓库里不写死任何站点，
域名从系统属性传入，不传就跳过：

```bash
./gradlew testDebugUnitTest --tests "*SourceAutoGeneratorLiveTest*" -DliveSite=https://example.com
```

改完识别算法，最好拿一个真实站点跑一遍 —— 这是唯一能碰到「真实站点的脏 HTML」的地方。

### 回归夹具

实连测试能给出真实反馈，但站点一改版、一限流就红，跑几次之后人就学会无视它了。
所以 `app/src/test/resources/fixtures/` 下维护一组固定 HTML，每组模拟一类结构：

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
| `site-opaque-classes` | class 全是 `g-1` / `i-9` / `q-1`，26 条推广位排在 20 部作品前面，只能靠 URL 语义 |
| `site-image-variants` | 同列表混排 `srcset` / 背景图 / 协议相对 / 1×1 与 base64 占位，正文整页是背景图 |
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

### 准确率语料

夹具回答的是「机制对不对」（懒加载有没有取到真地址、拆成两块的目录有没有合并），
一个样本只盯一件事。但「识别得有多准」是另一个问题：首页选错了板块，章节和图片必然
跟着错，成功率会同时掉三格，看起来像「这个站很烂」，实际只有一处要改。

所以 `app/src/test/resources/corpus/` 下另有一组**带标准答案**的样本，九个类型各一个：

| 样本 | 覆盖的特殊情况 |
| --- | --- |
| `site_001_standard` | 标准 DOM；顶部 4 个推广位 + 侧栏 8 条排行做干扰 |
| `site_002_korean` | 韩漫：封面与正文全是 `blank.gif` + `data-original`，正文 28 张 800×2400 长图 |
| `site_003_japanese` | 日漫：目录按卷分成三个容器，必须合并才拿到 36 话 |
| `site_004_legacy` | 2003 年模板：表格排版、大写标签、`<font>`、无 class |
| `site_005_spa` | SPA：首页与目录只有一个空壳，数据在 `var homeData` / `var chapterData` 里 |
| `site_006_wordpress` | Madara 主题：4 层包裹的列表项 + `li.wp-manga-chapter` + 懒加载正文图 |
| `site_007_gallery` | 图片站：条目没有文字（书名只在 `alt`）、正文图没有 class、容器只是 `<center>` |
| `site_008_antibot` | Cloudflare 人机验证，所有路径 403 —— 应如实拒绝，不是硬猜配置 |
| `site_009_multidomain` | 入口 portal / 作品 mirror / 图片 CDN 三个域名，图片还是协议相对地址 |

每个样本保存 `expected.json` + `home.html` + `detail_001.html` + `detail_002.html` + `reader.html`。
两个详情页是故意的：识别只会点进第一部，第二部用来抓「规则过拟合到某一页」。
`expected.json` 写的是人工确认过的正确答案：

```json
{
  "baseUrl": "https://manga-a.example",
  "routes": [{ "match": "prefix:/read/", "page": "reader.html" }],
  "home":  { "selector": "div.comic-list > div.comic-item",
             "expectedCount": 20, "titleFrom": "text", "coverAttr": "data-src" },
  "detail": { "titleSelector": "h1.comic-title",
              "chapterSelector": "ul.chapter-list > li > a",
              "pages": [{ "page": "detail_001.html", "expectedChapterCount": 48 }] },
  "reader": { "imageSelector": "div.reader-content > img",
              "imageAttr": "src", "expectedImageCount": 14 }
}
```

答案写选择器而不是抄 20 个书名：选择器是人工确认的那一件事，展开交给 jsoup，
改站点时不用重抄一遍数据。SPA 是例外 —— 页面上根本没有 DOM 列表，只能写内联变量名
（`"home": { "json": "homeData" }`），由测试侧一个「找到变量名 → 括号配平 → JSON」
的笨办法展开，故意不猜字段、不猜地址模板，避免和被测实现犯同一个错。

判定按**效果**而不是选择器字符串：`.list .item` 和 `div.list > div.item` 选中的是同一批
节点，抓到的东西一样就算对。六个阶段各自判定，并给出条目级准确率：

| 阶段 | 通过条件 |
| --- | --- |
| 首页列表 | 作品 URL 集合 Jaccard ≥ 0.90，且 `baseUrl` 落在作品链接真实域名上 |
| 标题提取 | 逐条完全一致的比例 ≥ 0.95 |
| 封面提取 | 逐条完全一致的比例 ≥ 0.95 |
| 章节提取 | 两个详情页的章节 URL 集合 Jaccard 都 ≥ 0.90 |
| 图片地址 | 数量与**顺序**完全一致（顺序错了读起来就是乱的） |
| 完整配置 | 以上五项全过 |

```bash
# 控制台会打出逐站矩阵；详细报告在 app/build/reports/autogen-corpus/
./gradlew :app:testDebugUnitTest --tests "*AutoGeneratorCorpusTest*"
```

回归策略是**棘轮**：`corpus/baseline.json` 记着每个阶段的通过站数与条目级准确率，
任何一项掉下来就红 —— 已知短板可以不修（有记录、不阻塞提交），但谁把它改差了立刻被拦下。

```bash
# 确认是预期变化（改了样本或修好了某个短板）后更新基线
./gradlew :app:testDebugUnitTest --tests "*AutoGeneratorCorpusTest*" -Dautogen.corpus.update=true
```

新增样本只需建目录并写 `expected.json`，`CorpusIntegrityTest` 会先把标准答案本身校验一遍
（条数对不对、`detail_00x` 有没有贴错书、路由指向的文件在不在），
避免「答案写错了却以为是算法退化」。

## 修改识别算法的顺序

1. 先弄清要改的是哪一关 —— 跑一遍语料，看报告里的「卡点分布」。
2. 改完先跑夹具（十几秒），确认机制没坏。
3. 再跑语料，看六阶段有没有掉，掉了就是退化，别急着更新基线。
4. 拿一个真实站点跑实连测试，确认识别出来的配置真能抓。

## 导入配置到设备

调试时不想每次手打 JSON：

```bash
adb push my-site.json /data/local/tmp/
adb shell run-as com.example.hellocompose cp /data/local/tmp/my-site.json files/sources/
adb shell am force-stop com.example.hellocompose
```

字段含义见 [CONFIG.md](CONFIG.md)。
