package com.example.hellocompose.data.source

import android.content.Context
import android.util.Log
import com.example.hellocompose.core.AppConfig
import com.example.hellocompose.core.NoSourceAvailableException
import com.example.hellocompose.data.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** 一次加载的结果：成功的配置 + 解析失败的文件说明（用于排查配置写错） */
data class SourceBundle(
    val configs: List<SourceConfig>,
    val errors: List<String>,
    /** 内置配置的 id 集合：用来判断某个用户配置是不是在覆盖内置配置 */
    val builtinIds: Set<String> = emptySet()
) {
    val enabled: List<SourceConfig> get() = configs.filter { it.enabled }

    /** 该 id 是否存在内置版本（删除用户配置后会回退到它） */
    fun hasBuiltin(id: String): Boolean = id in builtinIds
}

/** 保存时发现同 id 配置已存在，需要用户确认是否覆盖 */
class SourceConflictException(val sourceId: String) :
    Exception("已存在 id 为「$sourceId」的站点配置")

/**
 * 站点配置仓库。
 *
 * 来源有两处，后者覆盖前者（同 id 覆盖）：
 * 1. `assets/sources` 目录下的 JSON —— 随 APK 打包，只读
 * 2. `filesDir/sources` 目录下的 JSON —— App 里「添加站点」写入的位置，也兼容 adb 手工推送
 *
 * 内置配置改不了，所以「编辑内置配置」的语义是：把内容读出来，
 * 保存时写入用户目录、用同一个 id 覆盖它。删掉这份覆盖就等于恢复内置。
 *
 * 单个文件解析失败不会拖垮启动，只会记进 [SourceBundle.errors]。
 */
class SourceRepository(
    context: Context,
    private val settings: SettingsRepository
) {

    private val appContext = context.applicationContext

    private val mutex = Mutex()

    @Volatile
    private var cached: SourceBundle? = null

    /**
     * 配置集合的版本号。增删改之后自增，首页据此重新加载列表，
     * 避免用户保存完回到首页还看到旧的站点列表。
     */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    suspend fun load(): List<SourceConfig> = bundle().configs

    suspend fun bundle(): SourceBundle = mutex.withLock {
        cached ?: withContext(Dispatchers.IO) { readAll() }.also { cached = it }
    }

    /** 外部改动了目录内容（如 adb 推文件）后调用，下次读取重新扫描 */
    suspend fun invalidate() = mutex.withLock {
        cached = null
        _version.value += 1
    }

    /**
     * 当前生效的站点：优先用户选中的，其次第一个启用的配置。
     * 一个启用的都没有时抛 [NoSourceAvailableException]，由上层转成 CONFIG 错误提示。
     */
    suspend fun current(): SourceConfig {
        val available = bundle().enabled
        if (available.isEmpty()) throw NoSourceAvailableException()
        val selectedId = settings.selectedSourceId.first()
        return available.firstOrNull { it.id == selectedId } ?: available.first()
    }

    suspend fun select(id: String) = settings.setSelectedSourceId(id)

    /**
     * 切到指定站点，用于「续读上次那本书」这类需要跨站点跳转的场景。
     *
     * 直接 [select] 是不安全的：记录里的站点可能已经被用户删掉或停用了，
     * 那样选中的 id 会指向一份不存在的配置，后续抓取要么报错要么悄悄用回默认站点。
     * 所以这里先校验再切，站点不可用时返回 false，由调用方决定怎么提示。
     */
    suspend fun activate(id: String): Boolean {
        if (id.isBlank()) return false
        val available = bundle().enabled
        val target = available.firstOrNull { it.id == id } ?: return false
        if (settings.selectedSourceId.first() != target.id) {
            settings.setSelectedSourceId(target.id)
        }
        return true
    }


    /**
     * 读出某份配置的原始 JSON 文本。
     * 编辑页需要它来保留用户自己的排版，而不是把解析后的对象重新序列化一遍。
     */
    suspend fun rawText(source: SourceConfig): String? = withContext(Dispatchers.IO) {
        when (source.origin) {
            SourceOrigin.USER -> File(userDir(), source.fileName)
                .takeIf { it.isFile }
                ?.readText()

            SourceOrigin.BUILTIN -> runCatching {
                appContext.assets
                    .open("${AppConfig.ASSETS_SOURCE_DIR}/${source.fileName}")
                    .bufferedReader()
                    .use { it.readText() }
            }.getOrNull()
        }
    }

    /**
     * 保存一份用户配置。
     *
     * 先解析校验，通过后才落盘 —— 宁可直接报错，也不要往目录里塞一个读不出来的文件。
     * [overwrite] 为 false 且同 id 已存在时抛 [SourceConflictException]，交给 UI 询问用户。
     */
    suspend fun save(json: String, overwrite: Boolean = false): SourceConfig = mutex.withLock {
        val parsed = withContext(Dispatchers.IO) {
            SourceParser.parse(json, fallbackId = "custom")
        }
        val dir = userDir().apply { if (!isDirectory) mkdirs() }
        val target = File(dir, fileNameFor(parsed.id))

        if (target.exists() && !overwrite) throw SourceConflictException(parsed.id)

        withContext(Dispatchers.IO) { writeAtomically(target, json) }

        cached = null
        _version.value += 1
        Log.i(TAG, "保存站点配置 ${parsed.id} -> ${target.name}")
        parsed.copy(origin = SourceOrigin.USER, fileName = target.name)
    }

    /**
     * 删除一份用户配置。内置配置不可删（返回 false）。
     * 如果删掉的是覆盖内置的副本，下次读取会自动回退到内置版本。
     */
    suspend fun delete(source: SourceConfig): Boolean = mutex.withLock {
        if (source.origin != SourceOrigin.USER) return@withLock false

        val file = File(userDir(), source.fileName)
        val deleted = withContext(Dispatchers.IO) {
            !file.exists() || file.delete()
        }
        if (deleted) {
            cached = null
            _version.value += 1
            Log.i(TAG, "删除站点配置 ${source.id}")
        }
        deleted
    }

    /** 用户目录：App 内添加的配置都落在这里，adb 推送也放这里 */
    private fun userDir() = File(appContext.filesDir, AppConfig.USER_SOURCE_DIR)

    /**
     * id 转文件名。
     *
     * 不能直接拿 id 当文件名：id 可能含中文、斜杠、`..` 或超长，
     * 拼路径时会越界或互相覆盖。合法字符直接用（便于人工辨认），否则退化成哈希。
     */
    private fun fileNameFor(id: String): String {
        val clean = id.trim()
        return if (SAFE_NAME.matches(clean)) {
            "$clean.json"
        } else {
            "src_${clean.hashCode().toUInt().toString(16)}.json"
        }
    }

    /**
     * 先写临时文件再改名。
     * 直接覆盖原文件时若进程被杀，会留下半截 JSON，下次读取就多一条解析失败。
     */
    private fun writeAtomically(target: File, text: String) {
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            target.writeText(text)
            tmp.delete()
        }
    }

    private fun readAll(): SourceBundle {
        val errors = mutableListOf<String>()
        // LinkedHashMap 保证顺序稳定，且后面写入的同 id 配置会覆盖前面的
        val merged = LinkedHashMap<String, SourceConfig>()

        val builtins = readAssets(errors)
        builtins.forEach { merged[it.id] = it }
        readUserDir(errors).forEach { merged[it.id] = it }

        val sorted = merged.values.sortedWith(
            compareByDescending<SourceConfig> { it.enabled }.thenBy { it.name }
        )
        if (sorted.isEmpty()) {
            Log.w(TAG, "没有任何可用的站点配置，errors=$errors")
        }
        return SourceBundle(
            configs = sorted,
            errors = errors,
            builtinIds = builtins.map { it.id }.toSet()
        )
    }

    private fun readAssets(errors: MutableList<String>): List<SourceConfig> {
        val assets = appContext.assets
        val names = runCatching { assets.list(AppConfig.ASSETS_SOURCE_DIR) }.getOrNull()
            ?: return emptyList()

        return names.filter { it.endsWith(".json", ignoreCase = true) }.mapNotNull { fileName ->
            runCatching {
                val text = assets.open("${AppConfig.ASSETS_SOURCE_DIR}/$fileName")
                    .bufferedReader().use { it.readText() }
                SourceParser.parse(text, fallbackId = fileName.removeSuffix(".json"))
                    .copy(origin = SourceOrigin.BUILTIN, fileName = fileName)
            }.getOrElse { e ->
                errors += "内置 $fileName：${e.message}"
                Log.w(TAG, "解析内置配置失败: assets/$fileName", e)
                null
            }
        }
    }

    private fun readUserDir(errors: MutableList<String>): List<SourceConfig> {
        val dir = userDir()
        if (!dir.isDirectory) return emptyList()

        return dir.listFiles { f -> f.isFile && f.name.endsWith(".json", ignoreCase = true) }
            .orEmpty()
            .sortedBy { it.name }
            .mapNotNull { file ->
                runCatching {
                    SourceParser.parse(file.readText(), fallbackId = file.nameWithoutExtension)
                        .copy(origin = SourceOrigin.USER, fileName = file.name)
                }.getOrElse { e ->
                    errors += "${file.name}：${e.message}"
                    Log.w(TAG, "解析自定义配置失败: ${file.name}", e)
                    null
                }
            }
    }

    private companion object {
        const val TAG = "SourceRepository"

        /** 允许直接当文件名的 id：字母数字下划线中划线，1~40 位 */
        val SAFE_NAME = Regex("[A-Za-z0-9_-]{1,40}")
    }
}
