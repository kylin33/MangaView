# 站点配置

一份配置就是一个站点的抓取规则。它不属于代码，随时能改、能分享、能替换，
自动识别产出的也是这个格式。

## 放在哪

| 位置 | 用途 | 优先级 |
| --- | --- | --- |
| `app/src/debug/assets/sources/*.json` | 随 **debug** APK 打包的配置（仓库里只放一份模板） | 低 |
| 设备上 `filesDir/sources/*.json` | App 内添加/导入的配置 | 高（同 id 覆盖内置） |

模板只进 debug 源集，所以 release 包里 `assets/sources` 是空的：分发出的 APK
不含任何站点配置，第一条配置只能由用户填首页地址生成。

App 启动时扫描两个目录全部加载进左侧抽屉。单个文件写错不影响其它站点，
错误会显示在抽屉底部。

## 顶层字段

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
| `trustAllCerts` | 否 | 默认 `false`。关闭证书校验，**显式例外**，见 [ARCHITECTURE.md](ARCHITECTURE.md#连接安全) |
| `allowPlainHttp` | 否 | 默认 `false`。允许明文 HTTP，`baseUrl` 是 `http://` 时必填 |
| `home` / `search` / `detail` / `reader` | 前三个必填 | 四组抓取规则，见下 |

## 四组规则

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
"detail": {          // 详情页 -> 章节列表  "item": "ul#chapter-list li a",
  "chapterUrl":   { "select": "", "attr": "href" },
  "chapterTitle": { "select": "", "attr": "text" }
},
"reader": {          // 阅读页 -> 本章图片
  "item": "div.reader img",
  "image":   { "select": "", "attr": "data-original|data-src|src" },
  "referer": "{pageUrl}"                    // 图片防盗链的 Referer，默认本章地址
}
```

搜索表单不是「GET + `?keyword=`」的站点，`search` 还能多写三项。默认值就是最常见的那种形态，
所以一般配置里不会出现它们：

```jsonc
"search": {
  // 表单是 <form action="/search.php" method="post"><input name="searchword">
  // 关键字只能放请求体，所以 path 里没有 {keyword}，由 keywordParam 指名参数
  "path": "/search.php",
  "method": "post",              // 默认 get
  "keywordParam": "searchword",  // 默认 keyword；path 里有 {keyword} 时用不到

  // 表单带隐藏域时（苹果CMS 一类模板少一个参数就被重定向回首页）：
  //   <form action="/index.php" method="get">
  //     <input type="hidden" name="m" value="search">
  //     <input type="hidden" name="a" value="init">
  //     <input type="text" name="wd">
  // GET 可以直接把这些参数写进 path（"/index.php?m=search&a=init&wd={keyword}"），
  // POST 必须用 fields 表达，它们会进请求体：
  "fields": { "m": "search", "a": "init" },

  "item": "div.comic-item", "link": { ... }, "cover": { ... }, "title": { ... }
}
```

自动识别会优先读页面上的搜索表单（`<form>` 的 `action` / `method`、输入框的 `name`），
连 POST 和隐藏域一起写成上面的形态；页面上找不到表单时，才退到「试常见搜索地址」
和「用生僻词探测哪个参数真的生效」。识别报告里的「搜索：可用」后面会注明是哪一种。

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

## `select` 的三种写法

| 写法 | 含义 |
| --- | --- |
| `""` | 元素自身（`item` 命中的那个节点） |
| `"^"` | 向上找最近的 `<a>`，适合「封面图被链接包住」的列表 |
| 其它 | CSS 选择器，在该元素内部找第一个匹配，如 `"a"`、`".title"`、`"h3 a"` |

## `attr` 的写法

| 写法 | 含义 |
| --- | --- |
| `text` | 文本内容 |
| `html` | 内部 HTML |
| `href` / `src` / `data-original` | 取该属性 |
| `data-original\|src` | 多个候选，按顺序取第一个非空 |
| `abs:href` | 让 jsoup 补全成绝对地址（页面有 `<base>` 标签时比手写拼接更准） |

取值链的细节比这里写的多一层：如果链上取到的地址是占位图（`data:`、`blank.gif`、1×1），
运行时会回头在这个元素上重新挑一次最可信的地址。原因与优先级表见
[ARCHITECTURE.md 的「图片地址解析」](ARCHITECTURE.md#图片地址解析)。

## 模板占位符

| 占位符 | 展开为 |
| --- | --- |
| `{baseUrl}` | 站点根地址，末尾不带斜杠 |
| `{pageUrl}` | 当前请求的页面地址（图片 Referer 就靠它） |
| `{keyword}` | 仅 `search.path` 支持，搜索词经 URL 编码 |

## 一份完整示例

仓库里能跑的那份在 [`app/src/debug/assets/sources/example-template.json`](../app/src/debug/assets/sources/example-template.json)，
复制改名、改 `id` 与 `baseUrl`、按目标站点写好四组选择器，再把 `enabled` 改成 `true` 即可。

```json
{
  "schemaVersion": 1,
  "id": "example-template",
  "name": "示例模板（未启用）",
  "baseUrl": "https://example.com",
  "enabled": false,
  "referer": "{baseUrl}/",
  "timeoutMs": 15000,
  "trustAllCerts": false,
  "home": {
    "path": "/",
    "item": "div.comic-item",
    "link": { "select": "a", "attr": "href" },
    "cover": { "select": "img", "attr": "data-src|src" },
    "title": { "select": "h3", "attr": "text" }
  },
  "search": {
    "path": "/search?q={keyword}",
    "item": "div.comic-item",
    "link": { "select": "a", "attr": "href" },
    "cover": { "select": "img", "attr": "data-src|src" },
    "title": { "select": "h3", "attr": "text" }
  },
  "detail": {
    "item": "ul#chapter-list li a",
    "chapterUrl": { "select": "", "attr": "href" },
    "chapterTitle": { "select": "", "attr": "text" }
  },
  "reader": {
    "item": "div.reader img",
    "image": { "select": "", "attr": "src" },
    "referer": "{pageUrl}"
  }
}
```

## 导入

抽屉 → **添加站点**，四条通道，都不需要 adb、也不需要存储权限：
粘贴 JSON（会自动剥掉 markdown 代码围栏）、选文件、从链接、编辑已有配置。

**保存前先点「试抓首页」**：用未保存的配置真跑一次首页，显示解析到几部作品 + 前三条标题。
抓不出东西说明选择器不对。这是判断配置能不能用的唯一可靠方式 —— 看 JSON 看不出来。

## 命令行导入

```bash
adb push my-site.json /data/local/tmp/
adb shell run-as com.kylin.mangaview cp /data/local/tmp/my-site.json files/sources/
adb shell am force-stop com.kylin.mangaview
```

## 加载时的校验

- `baseUrl` 是 `http://` 但没写 `"allowPlainHttp": true` → 拒绝加载，不会静默降级。
- `timeoutMs` 超出 3000–60000 → 夹到边界值。
- `home` / `detail` / `reader` 缺失，或 `item` 为空又没有 `script` 节点 → 该文件解析失败，
  错误进抽屉底部的列表，其它站点照常加载。
