package com.example.hellocompose.engine.source

import com.example.hellocompose.data.remote.MangaScraper
import com.example.hellocompose.data.source.SourceConfig
import com.example.hellocompose.data.source.SourceRepository
import java.util.concurrent.ConcurrentHashMap

/**
 * 某个站点的专用实现工厂。
 *
 * 扩展点：站点结构特殊到 JSON 规则表达不了时（比如正文走 WebSocket、需要登录态、
 * 或者有私有 API），写一个 [MangaSource] 实现并通过 [SourceEngine.register] 挂上来。
 * 返回 null 表示「这个配置我不管」，引擎会退回通用的 JSON 实现。
 */
fun interface SourceFactory {
    fun create(config: SourceConfig): MangaSource?
}

/**
 * 站点适配引擎。
 *
 * 上层的唯一入口：给一个配置，拿一个 [MangaSource]。它负责三件事：
 * 1. **实例缓存**：同一份配置只构建一次，翻页、切章不会反复创建；
 * 2. **扩展点路由**：先问注册过的 [SourceFactory]，没人认领再退回 JSON 实现；
 * 3. **配置变更失效**：配置被编辑后自动重建，避免继续用旧选择器抓页面。
 *
 * 引擎不感知当前选中的是哪个站点——那是偏好设置的事，由 Repository 决定问谁要数据。
 */
class SourceEngine(
    private val repository: SourceRepository,
    private val scraper: MangaScraper
) {

    private val cache = ConcurrentHashMap<String, MangaSource>()

    private val factories = mutableListOf<SourceFactory>()

    /** 注册专用实现。后注册的优先匹配，方便本地覆盖内置的通用实现 */
    @Synchronized
    fun register(factory: SourceFactory) {
        factories.add(0, factory)
        cache.clear()
    }

    /** 按配置取实现。同 id 但内容变了（用户编辑过）会重新构建 */
    fun of(config: SourceConfig): MangaSource {
        val existing = cache[config.id]
        if (existing != null && !isStale(existing, config)) return existing

        val created = create(config)
        cache[config.id] = created
        return created
    }

    /** 当前选中的站点 */
    suspend fun current(): MangaSource = of(repository.current())

    /** 全部启用的站点，顺序与抽屉里一致 */
    suspend fun active(): List<MangaSource> = repository.bundle().enabled.map(::of)

    /** 配置增删改之后调用，丢掉所有旧实例（选择器可能已经变了） */
    fun invalidate() {
        cache.clear()
    }

    private fun create(config: SourceConfig): MangaSource {
        for (factory in factories) {
            factory.create(config)?.let { return it }
        }
        return JsonMangaSource(config, scraper)
    }

    /**
     * 只有通用实现能比较配置内容——专用实现由工厂自己决定要不要复用，
     * 引擎不替它猜。
     */
    private fun isStale(source: MangaSource, config: SourceConfig): Boolean =
        source is JsonMangaSource && source.config != config
}
