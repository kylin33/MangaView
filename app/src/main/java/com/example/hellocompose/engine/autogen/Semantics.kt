package com.example.hellocompose.engine.autogen

import org.jsoup.nodes.Element

/**
 * 语义特征分析：不靠 class 名的「长相」，而靠它想表达什么。
 *
 * 现实里大量站点的 class 是无意义的（`item-123`、`list_456`、`box-a1`），
 * 但语义信息并没有消失，它散落在别的地方：`alt`、`title`、链接文字、
 * URL 路径、`itemprop`、以及偶尔还能读懂的 `cover` / `chapter` 这类命名。
 *
 * 所以这里把「一个节点在说什么」抽象成若干角色，每个角色给一个 0..1 左右的强度，
 * 由调用方决定怎么用：字段选择时做同分时的偏向，列表评分时做小幅加成或惩罚。
 * 强度都很轻，语义只用来打破平局，不足以单独把一个错误候选抬上来 ——
 * 语义判断本身也会错（比如广告位的 class 叫 `book-item`）。
 */
internal object Semantics {

    enum class Role {
        /** 作品标题 */
        TITLE,

        /** 封面图 */
        COVER,

        /** 章节 / 目录 */
        CHAPTER,

        /** 作品列表容器或条目 */
        LIST,

        /** 作者、绘师 */
        AUTHOR,

        /** 最新更新 */
        LATEST,

        /** 导航、分页、页脚 */
        NAV,

        /** 广告、推广位 */
        AD,

        /** 正文内容区 */
        CONTENT
    }

    /** 允许出现在选择器里的安全 token（识别器可能把它拼进 CSS） */
    private val SEPARATOR = Regex("[^\\p{L}\\p{N}]+")
    private val CAMEL = Regex("([a-z0-9])([A-Z])")

    /**
     * 精确匹配的关键词。短词只做精确匹配：
     * `ad`、`gg`、`pic` 这类做包含匹配会误伤 `load`、`aggressive`、`picture`。
     */
    private val EXACT: Map<Role, Set<String>> = mapOf(
        Role.TITLE to setOf(
            "title", "tit", "name", "bookname", "comicname", "mangatitle", "caption", "subject"
        ),
        Role.COVER to setOf(
            "cover", "covers", "thumb", "thumbs", "thumbnail", "poster", "pic", "pics", "img", "image",
            "photo", "jacket", "artwork"
        ),
        Role.CHAPTER to setOf(
            "chapter", "chapters", "chap", "episode", "episodes", "ep", "catalog", "catalogue",
            "directory", "section", "volume", "vol", "mulu"
        ),
        Role.LIST to setOf(
            "list", "lists", "grid", "items", "item", "card", "cards", "book", "books", "comic",
            "comics", "manga", "mangas", "vod", "vodlist", "goods", "album", "albums", "rank",
            "ranking", "result", "results", "feed", "waterfall"
        ),
        Role.AUTHOR to setOf("author", "authors", "writer", "artist", "creator", "penname"),
        Role.LATEST to setOf("latest", "update", "updates", "new", "newest", "recent", "recently"),
        Role.NAV to setOf(
            "nav", "navbar", "menu", "header", "footer", "sidebar", "breadcrumb", "crumbs",
            "pagination", "pager", "paging", "toolbar", "topbar", "tabbar"
        ),
        Role.AD to setOf("ad", "ads", "advert", "adverts", "advertisement", "promo", "promos", "sponsor", "gg"),
        Role.CONTENT to setOf(
            "content", "article", "reader", "view", "chaptercontent", "main", "detail", "info", "intro"
        )
    )

    /** 做包含匹配的关键词：长词不容易误伤 */
    private val CONTAINS: Map<Role, List<String>> = mapOf(
        Role.TITLE to listOf("booktitle", "comictitle", "mangatitle", "book-name", "comic-name"),
        Role.COVER to listOf("cover", "thumb", "poster", "avatar", "artwork"),
        Role.CHAPTER to listOf("chapter", "episode", "catalog", "chapterlist", "chaplist"),
        Role.LIST to listOf("booklist", "comiclist", "mangalist", "itemlist", "cardlist", "gridlist", "vodlist"),
        Role.AUTHOR to listOf("author", "writer", "artist"),
        Role.LATEST to listOf("latest", "update", "recent"),
        Role.NAV to listOf("breadcrumb", "pagination", "navbar", "sidebar", "footer", "header", "menu"),
        Role.AD to listOf("advert", "promo", "sponsor", "tuiguang", "guanggao"),
        Role.CONTENT to listOf("content", "reader")
    )

    /** 中文关键词：class 与正文里都可能出现 */
    private val CHINESE: Map<Role, List<String>> = mapOf(
        Role.TITLE to listOf("标题", "书名", "書名", "名称", "名稱", "漫画名", "漫畫名"),
        Role.COVER to listOf("封面", "缩略图", "縮略圖"),
        Role.CHAPTER to listOf("章节", "章節", "目录", "目錄", "话", "話", "卷"),
        Role.LIST to listOf("列表", "作品", "漫画", "漫畫", "排行"),
        Role.AUTHOR to listOf("作者", "绘师", "繪師"),
        Role.LATEST to listOf("最新", "更新"),
        Role.NAV to listOf("导航", "導航", "首页", "首頁", "分页", "分頁"),
        Role.AD to listOf("广告", "廣告", "推广", "推廣")
    )

    fun tokens(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        return raw.split(SEPARATOR)
            .filter { it.isNotBlank() }
            .flatMap { part -> CAMEL.replace(part, "$1 $2").split(' ') }
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() && it.length <= 40 }
    }

    /**
     * 一组 token 的角色强度。
     *
     * 精确命中记 1.0，包含命中记 0.6 —— 包含判定更容易误伤，
     * 但像 `book-title` 被切成 `book` + `title` 之后精确匹配已经够用了，
     * 包含只是给 `booktitle` 这种连写补一刀。中文关键词因为不存在分词歧义，
     * 命中即 1.0。
     */
    fun roleScores(tokens: List<String>): Map<Role, Double> {
        if (tokens.isEmpty()) return emptyMap()
        val scores = HashMap<Role, Double>()

        for (token in tokens) {
            for ((role, words) in EXACT) {
                if (token in words) scores[role] = (scores[role] ?: 0.0) + 1.0
            }
            for ((role, words) in CONTAINS) {
                if (words.any { token.contains(it) }) {
                    scores[role] = (scores[role] ?: 0.0) + 0.6
                }
            }
            for ((role, words) in CHINESE) {
                // 中文关键词命中一次就算确认：`章节列表` 这种写法里没有分词歧义，
                // 而拉丁词要靠精确/包含两档来区分「像」和「确定是」
                if (words.any { token.contains(it) }) {
                    scores[role] = (scores[role] ?: 0.0) + 1.0
                }
            }
        }
        return scores
    }

    /**
     * 元素自身的语义 token：class/id/itemprop/aria-label/alt/title/data-*。
     *
     * 刻意把 `alt` 和 `title` 也算进来：class 不可靠时，`alt="封面"`、
     * `title="书名的封面"` 往往是页面上仅剩的语义线索。
     */
    fun tokensOf(el: Element): List<String> {
        val out = ArrayList<String>()
        out += tokens(el.className())
        out += tokens(el.id())
        if (el.hasAttr("itemprop")) out += tokens(el.attr("itemprop"))
        if (el.hasAttr("role")) out += tokens(el.attr("role"))
        if (el.hasAttr("aria-label")) out += tokens(el.attr("aria-label"))
        if (el.tagName() == "img" || el.tagName() == "a") {
            out += tokens(el.attr("alt"))
            out += tokens(el.attr("title"))
        }
        return out
    }

    fun scoresOf(el: Element): Map<Role, Double> = roleScores(tokensOf(el))

    fun score(el: Element, role: Role): Double = scoresOf(el)[role] ?: 0.0

    fun has(el: Element, role: Role): Boolean = score(el, role) >= 1.0

    // ==================== URL 路径语义 ====================

    /**
     * URL 路径的语义 token。
     *
     * 路径比 class 稳定得多：`/comic/123`、`/chapter/45`、`/author/abc` 这些
     * 约定几乎跨站通用，而且改版时最先保留的就是它（否则外部链接全断）。
     */
    fun urlTokens(url: String): List<String> {
        val path = url.substringAfter("://", url).substringAfter('/', "")
            .substringBefore('?')
            .substringBefore('#')
        return tokens(path)
    }

    fun urlRoles(url: String): Map<Role, Double> = roleScores(urlTokens(url))

    /** 像不像一个「作品详情页」的地址 */
    fun looksLikeDetailUrl(url: String): Boolean {
        val roles = urlRoles(url)
        if ((roles[Role.NAV] ?: 0.0) > 0.0) return false
        if ((roles[Role.AD] ?: 0.0) > 0.0) return false
        if ((roles[Role.AUTHOR] ?: 0.0) > 0.0) return false
        val content = (roles[Role.CONTENT] ?: 0.0) + (roles[Role.LIST] ?: 0.0) +
            (roles[Role.TITLE] ?: 0.0)
        return content > 0.0
    }

    /** 阅读页/章节地址里常见的词。/read/、/view/ 这两种写法比 /chapter/ 更常见 */
    private val READER_TOKENS = setOf(
        "read", "reader", "view", "watch", "play", "chapter", "chap", "ch", "ep", "episode",
        "episodes", "vol", "volume"
    )

    /** 像不像一个「章节地址」 */
    fun looksLikeChapterUrl(url: String): Boolean =
        urlTokens(url).any { it in READER_TOKENS } ||
            (urlRoles(url)[Role.CHAPTER] ?: 0.0) > 0.0

    /**
     * 成员地址的语义加成。
     *
     * 这是「class 全叫 item-123」时最管用的一条：推广位的卡片和作品卡片长得一模一样，
     * 数量还可能更多，唯一稳定的差别是链接指向 `/promo/…` 而不是 `/comic/…`。
     * 幅度同样很轻（最多 ±0.10），只在分数接近时改变结论。
     */
    fun urlBonus(urls: List<String>, chapterMode: Boolean = false): Double {
        if (urls.isEmpty()) return 0.0
        val sample = urls.take(8)
        var good = 0
        var bad = 0
        for (url in sample) {
            val roles = urlRoles(url)
            val isAd = (roles[Role.AD] ?: 0.0) > 0.0
            val isAuthor = (roles[Role.AUTHOR] ?: 0.0) > 0.0
            if (isAd || isAuthor) {
                bad++
                continue
            }
            val ok = if (chapterMode) looksLikeChapterUrl(url) else looksLikeDetailUrl(url)
            if (ok) good++
        }
        val goodRatio = good.toDouble() / sample.size
        val badRatio = bad.toDouble() / sample.size
        return (goodRatio * 0.05 - badRatio * 0.10).coerceIn(-0.12, 0.06)
    }

    // ==================== 列表加成 ====================

    /**
     * 列表候选的语义加成，直接叠加到 [ListScorer] 的总分上。
     *
     * 正向只认「明确写着内容列表」的容器名（book-list / chapter-list / 目录），
     * 不认 `list`、`item`、`card`、`grid` 这类到处都在用的通用词 ——
     * 真实站点里每个板块都这么叫，给它们加分等于给噪声加分。
     *
     * 负向（广告、导航）才是可靠信号：名字里写着 promo 的板块基本不可能是目录。
     * 幅度也压得比正向大，因为「看错成广告」比「没看出来是列表」代价更高。
     */
    fun listBonus(container: Element, members: List<Element>): Double {
        val containerRoles = scoresOf(container)
        val memberRoles = roleScores(members.take(5).flatMap { tokensOf(it) })

        var bonus = 0.0

        val containerName = container.className() + " " + container.id()
        if (CONTENT_LIST_HINT.containsMatchIn(containerName)) bonus += 0.02
        if ((memberRoles[Role.CHAPTER] ?: 0.0) > 0.0) bonus += 0.02

        if ((containerRoles[Role.AD] ?: 0.0) > 0.0) bonus -= 0.15
        if ((memberRoles[Role.AD] ?: 0.0) > 0.0) bonus -= 0.08
        if ((containerRoles[Role.NAV] ?: 0.0) > 0.0) bonus -= 0.08

        return bonus.coerceIn(-0.20, 0.05)
    }

    /** 明确表示「这是一个内容列表」的容器命名 */
    private val CONTENT_LIST_HINT = Regex(
        "(book|comic|manga|vod|chapter|item|card)[-_]?list|catalog|mulu|章节|章節|目录|目錄",
        RegexOption.IGNORE_CASE
    )
}
