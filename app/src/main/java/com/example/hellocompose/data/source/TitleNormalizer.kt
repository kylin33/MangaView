package com.example.hellocompose.data.source

/**
 * 「这段文字到底是不是书名 / 章节名」的唯一答案。
 *
 * 真实站点上，一段可见文字很少是干净的标题：
 *
 * - 卡片上叠着 `14小时前`、`3天前`、`已完结` 这类徽章，`<a>` 的 `text()` 会把它们
 *   一起带出来，于是书名变成「14小时前 姨见钟情」——搜索按这个名字发出去必然搜不到；
 * - 章节名太长时站点会用 CSS/服务端截断，可见文本只剩「第1话-阿姨，我」，
 *   完整名写在 `<a title="第1话-阿姨，我们结婚吧">` 上。用截断名做章节标题，
 *   用户看不出是哪一话，换源时按章节名对齐也会失配；
 * - 反过来也有站点把书名缀进章节链接的 `title`（`书名-第1话`），这时**不能**用
 *   `title` 属性替换可见文本，否则每一行章节都会重复显示书名。
 *
 * 所以这里不做「有 title 属性就用 title 属性」这种一刀切，而是按证据判断：
 * 只有当 `title` 属性明显更完整、且多出来的部分不像「另一段结构信息」时才采信。
 *
 * 识别阶段（`SourceAutoDetector.isUsableTitle`）和运行时（`PageParser`）都走这里，
 * 保证「评分看到的文字」和「用户看到的文字」是同一个，不会出现识别时判为合格、
 * 跑起来却是脏标题的情况。
 */
internal object TitleNormalizer {

    /** 相对时间徽章：14小时前 / 3天前 / 刚刚 / 昨天 */
    private val RELATIVE_TIME = Regex(
        "^\\s*(\\d+\\s*(秒|秒钟|分钟|分|小时|小時|钟头|天|日|周|週|星期|个月|月|年)\\s*(前|以前|ago)" +
            "|\\d+\\s*[smhdw]\\s*ago|刚刚|剛才|刚才|今天|今日|昨天|昨日|前天|今早|昨晚)",
        RegexOption.IGNORE_CASE
    )

    /** 绝对日期徽章：2026-11-20、11/20、11月20日、2026.11.20 */
    private val ABSOLUTE_DATE = Regex(
        "^\\s*(\\d{4}\\s*[-/.]\\s*\\d{1,2}\\s*[-/.]\\s*\\d{1,2}" +
            "|\\d{1,2}\\s*[-/]\\s*\\d{1,2}(\\s+\\d{1,2}:\\d{2})?" +
            "|\\d{1,2}\\s*月\\s*\\d{1,2}\\s*日?)"
    )

    /** 状态徽章：已完结 / 连载中 / 最新 / 独家 */
    private val STATUS = Regex(
        """^\s*(已完结|已完結|连载中|連載中|完结|完結|更新中|连载|連載|新作|独家|獨家|最新|热门|熱門|推荐|推薦|TOP|New)""",
        RegexOption.IGNORE_CASE
    )

    /** 方括号/【】包起来的状态徽章：[连载] 【独家】 */
    private val BRACKET_STATUS = Regex(
        """^\s*[\[【(（]\s*(连载中?|連載中?|已完结|已完結|完结|完結|独家|獨家|最新|热门|熱門|推荐|推薦|新|new)\s*[\]】)）]""",
        RegexOption.IGNORE_CASE
    )

    /** 尾部状态：更新至第12话 / 共45话 / 全12话 */
    private val TAIL_STATUS = Regex(
        """\s*[·\-|]?\s*(更新至.*|共\s*\d+\s*[话話章卷]|全\s*\d+\s*[话話章卷]|已完结|已完結|连载中|連載中)$"""
    )

    /**
     * 标题后面接的整块元信息：`作者：xxx`、`更新：第30回`、`人气：1234`。
     *
     * 老式站点一行里塞满这些字段，「书名 + 空格 + 作者 + 更新」全在一个 `<td>` 里。
     * 从第一个这样的标记处截断，剩下的就是书名 —— 但只在**截断后仍有内容**时生效，
     * 免得把本来就叫「更新日志」的条目截成空。
     */
    private val META_SPLIT = Regex(
        """\s+(作者|作\s*者|更新至|更新|状态|人气|评分|点击|收藏|分类|地区|年份|最新章节)\s*[:：]"""
    )

    /** 徽章之间常见的分隔符，剥完徽章要一并去掉 */
    private val SEPARATORS = Regex("""^[\s\-–—_|·:：,，.、]+|[\s\-–—_|·:：,，.、]+$""")

    /** 明显不是标题的通用文案 */
    private val GENERIC = Regex(
        "^(点击查看|点击阅读|点击进入|查看详情|查看大图|立即观看|开始阅读|展开全部|加载中|加载失败" +
            "|详情|詳細|更多|点击|查看|图片|圖片|无|暂无|暂无简介|未知|未知作者|none|null|undefined)$",
        RegexOption.IGNORE_CASE
    )

    /**
     * 标题属性里掺进来的结构信息。
     *
     * 老式站点常把整行信息塞进 `<a title>`：「书名 作者：xxx 更新：第30回」。
     * 这种 `title` 比可见文字长，但**不是**被截断的完整书名，采信它会把书名搞脏。
     * 判据是「可见文字里没有、`title` 里有」—— 书名本身不会写「作者：」。
     */
    private val META_FIELD = Regex(
        "(作者|作\\s*者|更新至|更新[：:]|状态[：:]|人气|评分|点击|收藏|简介|标签|分类|地区|年份|连载至|最新章节)",
        RegexOption.IGNORE_CASE
    )

    /** 多出来的部分如果以这些开头，说明是「另一段结构信息」而不是被截断的后半句 */
    private val CHAPTER_MARKER = Regex("""^[\s\-–—_|·:：]*第\s*\d+\s*[话話章卷回集]""")

    fun isGeneric(text: String): Boolean = GENERIC.containsMatchIn(text.trim())

    /**
     * 剥掉标题前后的可见徽章。
     *
     * 只剥「明确的」徽章形态，绝不按位置乱切：书名本身可以是「14岁」「3月的狮子」
     * 这种以数字开头的，所以相对时间必须带「前 / 刚刚 / 昨天」这类词才认，
     * 绝对日期必须带日期分隔符才认。
     */
    fun stripBadges(text: String): String {
        var out = text.replace('\u00A0', ' ').trim()
        // 先按元信息截断：`书名 作者：xxx` → `书名`。放在剥徽章之前，
        // 因为时间徽章有时就长在元信息段里（`书名 更新：3小时前`）
        META_SPLIT.find(out)?.let { m ->
            if (m.range.first >= 2) out = out.substring(0, m.range.first).trim()
        }
        // 徽章可能叠着（[连载] 3小时前 书名），最多剥三层
        repeat(3) {
            val before = out
            out = out.replaceFirst(BRACKET_STATUS, "")
            out = out.replaceFirst(RELATIVE_TIME, "")
            out = out.replaceFirst(ABSOLUTE_DATE, "")
            out = out.replaceFirst(STATUS, "")
            out = out.replace(TAIL_STATUS, "")
            out = out.replace(SEPARATORS, "").trim()
            if (out == before) return out
        }
        return out
    }

    /**
     * 合并「可见文字」与「`title` 属性」，取更可信的那个。
     *
     * 三种真实情况：
     * 1. 可见文字带徽章、`title` 属性干净 → 用 `title`；
     * 2. 可见文字被截断、`title` 属性是完整名 → 用 `title`；
     * 3. 可见文字是章节名、`title` 属性是「书名-第N话」→ **保留可见文字**，
     *    否则每行章节都会重复一遍书名。
     */
    fun merge(visible: String, attrTitle: String): String {
        val cleaned = stripBadges(visible)
        val title = attrTitle.replace('\u00A0', ' ').trim()

        if (title.isEmpty()) return cleaned
        if (cleaned.isEmpty()) return if (isGeneric(title)) "" else title
        if (isGeneric(title) || title.length > 120) return cleaned
        if (title == cleaned) return cleaned

        // 情况 2：可见文字是被截断的前半句，title 属性接着它往下写。
        // 先排除「title 里接了作者/更新时间」这种长但不是书名的情形
        if (title.length > cleaned.length && title.startsWith(cleaned)) {
            val rest = title.substring(cleaned.length)
            val metaOnlyInTitle = META_FIELD.containsMatchIn(title) && !META_FIELD.containsMatchIn(cleaned)
            if (!metaOnlyInTitle && !CHAPTER_MARKER.containsMatchIn(rest.take(6))) return title
        }

        // 情况 1：可见文字前面糊着徽章，title 属性正好是去掉徽章后的那句
        if (cleaned.length > title.length && cleaned.endsWith(title)) {
            val head = cleaned.substring(0, cleaned.length - title.length).trim()
            if (head.isEmpty() || stripBadges(head).isEmpty()) return title
        }

        // 情况 3 以及其它无法判断的情形：可见文字优先。它是用户真正读到的那一行
        return cleaned
    }
}
