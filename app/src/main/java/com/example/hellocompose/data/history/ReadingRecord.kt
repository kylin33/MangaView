package com.example.hellocompose.data.history

import org.json.JSONArray
import org.json.JSONObject

/**
 * 一本书的阅读状态。
 *
 * 「历史记录」和「阅读进度」不是两件事：用户想看的是「我看过哪些书、每本看到哪」，
 * 拆成两张表只会让同一个动作写两次、还容易互相不同步。所以一条记录就是
 * 一本漫画的全部状态：最后一次读的章节 + 页码 + 已读章节集合。
 *
 * [detailUrl] 是主键：同一本书在同一站点下详情页地址唯一，换站点才算另一本书。
 * 这也是「站点关了还能拿书名去别处搜」能成立的前提——书名、封面、章节名都留在本地，
 * 不依赖原站点的任何一次网络请求。
 */
data class ReadingRecord(
    val mangaTitle: String,
    val coverUrl: String,
    val detailUrl: String,
    /** 记录时生效的站点，续读前要先把它切回来 */
    val sourceId: String,
    /** 站点名只用来展示：站点配置被删掉后仍然能在列表里认出这本书来自哪 */
    val sourceName: String,
    val chapterTitle: String,
    val chapterUrl: String,
    /** 已经滑到第几张（0 基）。-1 表示这一章还没真正打开过 */
    val pageIndex: Int,
    val pageCount: Int,
    /** 当前章在目录里的位置，-1 表示目录没拿到 */
    val chapterIndex: Int,
    val chapterCount: Int,
    /** 这本书读过的章节标题（按时间顺序去重），详情页据此标记「已读」 */
    val readChapterTitles: List<String> = emptyList(),
    val updatedAt: Long = 0L
) {
    /** 主键 */
    val key: String get() = detailUrl

    fun hasRead(chapterTitle: String): Boolean = chapterTitle in readChapterTitles

    /** 「第 3 / 12 页」；页码未知时为空串，由调用方决定要不要显示 */
    val pageLabel: String
        get() = if (pageCount > 0 && pageIndex >= 0) {
            "第 ${(pageIndex + 1).coerceAtMost(pageCount)} / $pageCount 页"
        } else {
            ""
        }

    /** 「第 8 话 · 第 3 / 12 页」，历史列表和首页卡片共用一句话 */
    val progressLabel: String
        get() = listOfNotNull(
            chapterTitle.ifBlank { null },
            pageLabel.ifBlank { null }
        ).joinToString(" · ")
}

/**
 * 记录的磁盘格式。
 *
 * 用 org.json 而不是再引一个序列化库：项目里站点配置已经是手写 JSON，
 * 多一个注解处理器只为存几十条记录不划算。字段名取短，因为每次翻页都要整份重写。
 *
 * 解析全程容错：一条记录坏了就跳过，不能因为历史里有一条脏数据导致整个列表打不开。
 */
internal object ReadingRecordJson {

    fun encode(list: List<ReadingRecord>): String {
        val array = JSONArray()
        list.forEach { record ->
            array.put(
                JSONObject().apply {
                    put("t", record.mangaTitle)
                    put("cv", record.coverUrl)
                    put("d", record.detailUrl)
                    put("sid", record.sourceId)
                    put("sn", record.sourceName)
                    put("ct", record.chapterTitle)
                    put("cu", record.chapterUrl)
                    put("p", record.pageIndex)
                    put("pc", record.pageCount)
                    put("ci", record.chapterIndex)
                    put("cc", record.chapterCount)
                    put("rc", JSONArray(record.readChapterTitles))
                    put("u", record.updatedAt)
                }
            )
        }
        return array.toString()
    }

    fun decode(text: String?): List<ReadingRecord> {
        if (text.isNullOrBlank()) return emptyList()

        return runCatching {
            val array = JSONArray(text)
            (0 until array.length()).mapNotNull { index ->
                val obj = array.optJSONObject(index) ?: return@mapNotNull null
                val detailUrl = obj.optString("d").trim()
                // 没有详情页地址的记录既不能续读也不能搜索，直接丢弃
                if (detailUrl.isEmpty()) return@mapNotNull null

                ReadingRecord(
                    mangaTitle = obj.optString("t"),
                    coverUrl = obj.optString("cv"),
                    detailUrl = detailUrl,
                    sourceId = obj.optString("sid"),
                    sourceName = obj.optString("sn"),
                    chapterTitle = obj.optString("ct"),
                    chapterUrl = obj.optString("cu"),
                    pageIndex = obj.optInt("p", -1),
                    pageCount = obj.optInt("pc", 0),
                    chapterIndex = obj.optInt("ci", -1),
                    chapterCount = obj.optInt("cc", 0),
                    readChapterTitles = obj.optJSONArray("rc").toStringList(),
                    updatedAt = obj.optLong("u", 0L)
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { optString(it).takeIf(String::isNotBlank) }
    }
}
