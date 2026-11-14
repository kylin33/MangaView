package com.example.hellocompose.ui.navigation

import android.net.Uri
import com.example.hellocompose.data.history.ReadingRecord
import com.example.hellocompose.data.model.Chapter
import com.example.hellocompose.data.model.Manga

/**
 * 路由表。
 *
 * 参数里的 URL 必须 Uri.encode，否则 http:// 里的斜杠会被当成路径分隔符，
 * 导航时直接抛 IllegalArgumentException。取出时 NavController 会自动解码。
 */
object Destinations {

    const val HOME = "home"

    /** 搜索。q 用来从历史记录「用书名再搜一次」时预填并直接出结果 */
    const val SEARCH_ROUTE = "search?q={q}"

    /** 阅读历史 / 进度 */
    const val HISTORY = "history"

    const val DETAIL_ROUTE = "detail?url={url}&title={title}&cover={cover}"

    /**
     * 阅读器。
     *
     * 除了章节地址，还要带上详情页地址：阅读器据此拿到整份目录，
     * 才能实现「读完这一章直接下一章」，而不是让用户退回目录手动选。
     *
     * title / cover 是漫画名和封面：阅读器要拿它们写历史记录，
     * 站点哪天关掉了，历史页里这本书的名字和封面还在。
     */
    const val READER_ROUTE =
        "reader?url={url}&chapterTitle={chapterTitle}&detail={detail}&title={title}&cover={cover}"

    /** 添加 / 编辑站点。id 为空表示新建 */
    const val SOURCE_EDITOR_ROUTE = "source_editor?id={id}"

    fun detail(manga: Manga): String =
        "detail?url=${Uri.encode(manga.detailUrl)}" +
            "&title=${Uri.encode(manga.title)}" +
            "&cover=${Uri.encode(manga.coverUrl)}"

    fun search(keyword: String = ""): String = "search?q=${Uri.encode(keyword)}"

    fun reader(
        chapter: Chapter,
        detailUrl: String = "",
        mangaTitle: String = "",
        coverUrl: String = ""
    ): String =
        "reader?url=${Uri.encode(chapter.url)}" +
            "&chapterTitle=${Uri.encode(chapter.title)}" +
            "&detail=${Uri.encode(detailUrl)}" +
            "&title=${Uri.encode(mangaTitle)}" +
            "&cover=${Uri.encode(coverUrl)}"

    /** 从一条历史记录直接回到上次停下的那一页 */
    fun resume(record: ReadingRecord): String = reader(
        chapter = Chapter(record.chapterTitle, record.chapterUrl),
        detailUrl = record.detailUrl,
        mangaTitle = record.mangaTitle,
        coverUrl = record.coverUrl
    )

    fun sourceEditor(id: String = ""): String =
        if (id.isBlank()) "source_editor" else "source_editor?id=${Uri.encode(id)}"
}
