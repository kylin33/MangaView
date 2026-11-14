package com.example.hellocompose.data.history

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.IOException

private val Context.historyDataStore by preferencesDataStore(name = "manga_history")

/**
 * 阅读历史 / 进度的唯一存储。
 *
 * 为什么是 DataStore 里存一段 JSON，而不是 Room：
 * - 记录只有「按更新时间倒序的一张列表」，没有关联查询、没有分页，Room 的建表和迁移成本不值；
 * - 写盘只有一处（阅读器翻页时 upsert 一条），DataStore 的 edit 天然串行，
 *   不需要自己处理并发覆盖。
 *
 * 读改写全部在 `edit {}` 内部完成，这正是 DataStore 保证原子性的地方：
 * 先读旧列表、替换同名书、再写回，中间不会被另一次写入插进来。
 */
class HistoryRepository(context: Context) {

    private val appContext = context.applicationContext
    private val recordsKey = stringPreferencesKey("records")

    /**
     * 独立于页面生命周期的作用域。
     *
     * 用户按返回退出阅读器时 ViewModel 会立刻销毁，viewModelScope 里的写盘会被取消，
     * 最后几页的进度就丢了。进度是「必须落盘」的数据，所以走这个自己管的作用域。
     */
    private val writeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 全部记录，按最近阅读时间倒序 */
    val records: Flow<List<ReadingRecord>> = appContext.historyDataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs -> ReadingRecordJson.decode(prefs[recordsKey]) }
        .distinctUntilChanged()

    /** 最近读的一本，首页「继续阅读」卡片用它 */
    val lastRead: Flow<ReadingRecord?> = records.map { it.firstOrNull() }

    suspend fun find(detailUrl: String): ReadingRecord? =
        records.first().firstOrNull { it.detailUrl == detailUrl }

    /**
     * 写入一本漫画的最新状态。
     *
     * 已读章节列表由仓库自己维护而不是让调用方传：阅读器只知道「我现在在哪一章」，
     * 一章一章攒起来的集合只有这里看得到。
     */
    suspend fun save(record: ReadingRecord) {
        if (record.detailUrl.isBlank()) return

        appContext.historyDataStore.edit { prefs ->
            val current = ReadingRecordJson.decode(prefs[recordsKey])
            val previous = current.firstOrNull { it.detailUrl == record.detailUrl }

            val chapters = buildList {
                previous?.let { addAll(it.readChapterTitles) }
                if (record.chapterTitle.isNotBlank()) add(record.chapterTitle)
            }.distinct().takeLast(MAX_CHAPTERS)

            val merged = record.copy(
                readChapterTitles = chapters,
                updatedAt = record.updatedAt.takeIf { it > 0 } ?: System.currentTimeMillis()
            )

            val next = (current.filterNot { it.detailUrl == record.detailUrl } + merged)
                .sortedByDescending { it.updatedAt }
                .take(MAX_RECORDS)

            prefs[recordsKey] = ReadingRecordJson.encode(next)
        }
    }

    /** 不阻塞调用方、也不怕调用方被销毁的写入，给 onCleared 这类「最后一刻」用 */
    fun saveAsync(record: ReadingRecord) {
        writeScope.launch {
            runCatching { save(record) }
                .onFailure { Log.w(TAG, "保存阅读进度失败: ${record.detailUrl}", it) }
        }
    }

    suspend fun remove(detailUrl: String) {
        appContext.historyDataStore.edit { prefs ->
            val current = ReadingRecordJson.decode(prefs[recordsKey])
            prefs[recordsKey] = ReadingRecordJson.encode(
                current.filterNot { it.detailUrl == detailUrl }
            )
        }
    }

    suspend fun clear() {
        appContext.historyDataStore.edit { prefs -> prefs.remove(recordsKey) }
    }

    private companion object {
        const val TAG = "HistoryRepository"

        /** 最多留多少本书。每条约几百字节，两百条也就几十 KB */
        const val MAX_RECORDS = 200

        /** 单本书记住多少个章节名，用来在目录里打「已读」标记 */
        const val MAX_CHAPTERS = 500
    }
}
