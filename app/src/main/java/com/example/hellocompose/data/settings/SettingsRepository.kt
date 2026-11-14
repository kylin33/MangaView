package com.example.hellocompose.data.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.hellocompose.core.AppConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "manga_settings")

/**
 * 用户偏好。原来用 SharedPreferences 手工读写，这里换成 DataStore：
 * 读取是 Flow，改动会自动推送到所有订阅页面（首页和阅读器的预载数保持一致）。
 */
class SettingsRepository(private val context: Context) {

    private val preloadKey = intPreferencesKey("preload_count")
    private val selectedSourceKey = stringPreferencesKey("selected_source_id")

    val preloadCount: Flow<Int> = context.settingsDataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs -> prefs[preloadKey] ?: AppConfig.DEFAULT_PRELOAD }

    /** 上次选中的站点 id；为空表示还没选过，由 SourceRepository 决定默认值 */
    val selectedSourceId: Flow<String?> = context.settingsDataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs -> prefs[selectedSourceKey] }

    suspend fun setPreloadCount(count: Int) {
        context.settingsDataStore.edit { prefs ->
            prefs[preloadKey] = count.coerceIn(0, AppConfig.MAX_PRELOAD)
        }
    }

    suspend fun setSelectedSourceId(id: String) {
        context.settingsDataStore.edit { prefs ->
            prefs[selectedSourceKey] = id
        }
    }
}
