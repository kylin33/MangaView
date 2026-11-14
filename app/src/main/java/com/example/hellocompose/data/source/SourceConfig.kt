package com.example.hellocompose.data.source

import com.example.hellocompose.core.AppConfig

/**
 * 配置的来源。决定「能不能改、能不能删」：
 * 内置配置打包在 APK 里改不了，编辑时会在用户目录生成同 id 的副本覆盖它，
 * 删掉副本就等于恢复内置配置。
 */
enum class SourceOrigin {
    /** 随 APK 打包，只读 */
    BUILTIN,

    /** 用户目录下由 App 写入的配置 */
    USER
}

/**
 * 单个字段的取值规则。JSON 里写成 `{ "select": "...", "attr": "..." }`。
 *
 * select：
 * - `""`  取元素自身
 * - `"^"` 向上找最近的 `<a>`（列表页封面图被 `<a>` 包住时用）
 * - 其他  当作 CSS 选择器，在当前元素内部查找第一个匹配
 *
 * attr：
 * - `text` / `html` 取文本或内部 HTML
 * - `abs:href` 用 jsoup 的绝对地址能力直接补全
 * - `data-original|src` 用 `|` 分隔多个候选属性，按顺序取第一个非空值
 */
data class FieldRule(
    val select: String = "",
    val attr: String = "text"
) {
    companion object {
        val LINK = FieldRule(select = "^", attr = "href")
        val TEXT = FieldRule(select = "", attr = "text")
    }
}

/**
 * 从页面内联脚本里的 JSON 取数据。
 *
 * 有些站点把列表和章节都写进 `<script>` 变成 JS 变量：
 * ```
 * var serchArry = [{"id":9174,"title":"姨见钟情","cover":"https://..."}];
 * initIntroData([{"title":"连载","data":[{"id":211921,"comic_id":9174,"chapter_name":"第1话 …"}]}]);
 * ```
 * 页面上对应的只是 `<div id="serch"></div>` 这样的空容器，CSS 选择器一个都抓不到。
 * 这类站浏览器里完全正常，但用 DOM 规则只会得到「没有列表」。
 *
 * 所以另开一条取值通道：用正则从脚本文本里把 JSON 抠出来，再按字段组装数据。
 */
data class ScriptRule(
    /** 提取 JSON 的正则，必须有一个捕获组包住 JSON 数组或对象 */
    val pattern: String,
    /** 在 JSON 里定位数组的点路径，如 `"0.data"`；空串表示根本身就是数组 */
    val listPath: String = "",
    /**
     * 条目地址模板，用 JSON 字段名填充，如 `"/view/{comic_id}/{id}"`。
     * 留空表示条目本身就是完整地址（取 [urlField]）。
     */
    val urlTemplate: String = "",
    /** 地址字段名，[urlTemplate] 为空时用它 */
    val urlField: String = "url",
    /** 标题字段名 */
    val titleField: String = "title",
    /** 封面对应字段名，列表类才有 */
    val coverField: String = "cover"
)

/** 一个列表页（首页 / 搜索结果页）的抓取规则 */
data class ListRule(
    val path: String,
    val item: String,
    val link: FieldRule = FieldRule.LINK,
    val cover: FieldRule = FieldRule(select = "", attr = "data-original|src"),
    val title: FieldRule = FieldRule(select = "", attr = "alt"),
    /** 非空时优先走脚本取数，DOM 规则只作兜底 */
    val script: ScriptRule? = null
)

/** 详情页：拿到章节列表 */
data class DetailRule(
    val item: String,
    val url: FieldRule = FieldRule(select = "", attr = "href"),
    val title: FieldRule = FieldRule.TEXT,
    /** 非空时优先走脚本取数 */
    val script: ScriptRule? = null
)

/** 阅读页：拿到本章所有图片，以及图片防盗链需要的 Referer 模板 */
data class ReaderRule(
    val item: String,
    val image: FieldRule = FieldRule(select = "", attr = "data-original|data-src|src"),
    val referer: String = "{pageUrl}"
)

/**
 * 一个站点的完整定义，与 assets/sources 目录下的 JSON 一一对应。
 *
 * 模板占位符：
 * - `{baseUrl}` 站点根地址（自动去掉末尾斜杠）
 * - `{pageUrl}` 当前请求的页面地址（如阅读页的 Referer 就用它）
 */
data class SourceConfig(
    val id: String,
    val name: String,
    val baseUrl: String,
    val enabled: Boolean = true,
    val description: String = "",
    val userAgent: String = AppConfig.USER_AGENT,
    val timeoutMs: Long = AppConfig.TIMEOUT_MS,
    val referer: String = "{baseUrl}/",
    /**
     * 关闭证书校验。**这是显式例外，不是默认行为**：只有用户在识别阶段
     * 看过证书错误详情后点过「仍然继续」才会为 true，UI 必须给出醒目警告。
     */
    val trustAllCerts: Boolean = false,
    /**
     * 允许以明文 HTTP 访问。默认 false，任何 http 地址都会被拦下。
     * 同样只有在用户确认过「该站点不支持 HTTPS」之后才会被写入。
     */
    val allowPlainHttp: Boolean = false,
    val home: ListRule,
    val search: ListRule? = null,
    val detail: DetailRule,
    val reader: ReaderRule,
    /**
     * 自动识别算出来的置信度。手写/导入的配置没有这一项。
     * 它记录的只是「识别那一刻」的把握，用户后续手改选择器不会更新它，
     * 所以 UI 上要说明来源，别让人误以为是实时校验结果。
     */
    val confidence: SourceConfidence? = null,
    /**
     * 以下两个字段不来自 JSON，由 SourceRepository 在读取时填充。
     * 放在数据类里而不是单独的包装类，是为了让 UI 拿到配置就知道该不该显示「删除」。
     */
    val origin: SourceOrigin = SourceOrigin.BUILTIN,
    /** 对应的 JSON 文件名，编辑和删除时直接定位，不用再扫一遍目录 */
    val fileName: String = ""
) {
    /** 把配置里的相对路径拼成绝对地址；本身就是绝对地址时原样返回 */
    fun url(path: String): String {
        val p = path.trim()
        if (p.isEmpty()) return baseUrl
        return when {
            p.startsWith("http://") || p.startsWith("https://") -> p
            p.startsWith("/") -> baseUrl.trimEnd('/') + p
            else -> baseUrl.trimEnd('/') + "/" + p
        }
    }

    /**
     * 这份配置里的安全例外。非空就意味着"内容可能被第三方看到或改写"，
     * 界面上必须显示成警告而不是普通说明。
     */
    val securityWarnings: List<String>
        get() = buildList {
            if (trustAllCerts) add("已关闭证书校验（trustAllCerts=true）：无法防范中间人，页面和图片都可能被篡改")
            if (allowPlainHttp) add("允许明文 HTTP（allowPlainHttp=true）：链路上的任何人都能读取和修改内容")
        }

    /** 展开模板占位符 */
    fun expand(template: String, pageUrl: String = ""): String =
        template
            .replace("{baseUrl}", baseUrl.trimEnd('/'))
            .replace("{pageUrl}", pageUrl)

    fun refererFor(pageUrl: String = ""): String = expand(referer, pageUrl)

    /**
     * 这份配置是不是「只能靠搜索」。
     *
     * 首页列表抓不到时（整屏脚本渲染、或压根没有排行榜），自动识别会写入一个
     * 永不匹配的占位选择器 —— 此时首页必然为空，UI 应该引导用户去搜索，
     * 而不是干巴巴地说一句「这里还没有漫画」。
     */
    val isSearchOnly: Boolean get() = home.item == NO_HOME_LIST_SELECTOR
}

/** 与 SourceAutoGenerator 生成的占位选择器保持一致 */
const val NO_HOME_LIST_SELECTOR = ".__no_home_list__"
