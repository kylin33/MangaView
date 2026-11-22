package com.example.hellocompose.di

import android.content.Context
import coil.ImageLoader
import com.example.hellocompose.data.history.HistoryRepository
import com.example.hellocompose.data.image.ImageLoadStats
import com.example.hellocompose.data.image.ImageRequestManager
import com.example.hellocompose.data.image.MangaImageLoader
import com.example.hellocompose.data.remote.JsoupHttpClient
import com.example.hellocompose.data.remote.MangaScraper
import com.example.hellocompose.data.repository.MangaRepository
import com.example.hellocompose.data.settings.SettingsRepository
import com.example.hellocompose.data.source.SourceProbe
import com.example.hellocompose.data.source.SourceRepository
import com.example.hellocompose.engine.autogen.SourceAutoGenerator
import com.example.hellocompose.engine.source.SourceEngine

/**
 * 手动依赖容器。
 *
 * 依赖仍不多、无复杂生命周期，用 Hilt 属于杀鸡用牛刀，还会引入注解处理器的构建开销。
 * 等模块拆分、需要按屏幕作用域注入时，再整体替换成 Hilt 也不影响上层代码。
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    val settingsRepository: SettingsRepository = SettingsRepository(appContext)

    /** 阅读历史与进度，全 App 只有这一份 */
    val historyRepository: HistoryRepository = HistoryRepository(appContext)

    /** 站点配置的唯一来源，Repository 与各 ViewModel 共用 */
    val sourceRepository: SourceRepository = SourceRepository(appContext, settingsRepository)

    /** 抓取与识别共用一个 HTTP 客户端，证书工厂只建一次 */
    private val httpClient = JsoupHttpClient()

    /** 与正式抓取共用同一个实例，保证「试抓取」和实际运行行为一致 */
    private val scraper = MangaScraper(httpClient)

    /**
     * 站点适配引擎。站点特判实现（如果有）在这里注册：
     * `sourceEngine.register { config -> if (config.id == "xxx") MyCustomSource(config) else null }`
     */
    val sourceEngine: SourceEngine = SourceEngine(
        repository = sourceRepository,
        scraper = scraper
    )

    val mangaRepository: MangaRepository = MangaRepository(
        engine = sourceEngine,
        sources = sourceRepository
    )

    /** 添加站点时用未保存的配置试抓首页 */
    val sourceProbe: SourceProbe = SourceProbe(scraper)

    /** 只给一个首页地址，自动推出一整份站点配置 */
    val sourceAutoGenerator: SourceAutoGenerator = SourceAutoGenerator(httpClient)

    /** 全局唯一的 Coil 实例，内存与磁盘缓存共享，预加载和展示用同一个 */
    val imageLoader: ImageLoader = MangaImageLoader.create(appContext)

    /** 本机解码档位：面板要显示「为什么给了几路并发」 */
    val imageDeviceProfile = MangaImageLoader.deviceProfile(appContext)

    /** 图片加载统计，全 App 一份；面板读它，重启清零 */
    val imageStats = ImageLoadStats()

    /**
     * 图片请求层。阅读页拿它出请求、预加载也拿它出请求，
     * 两边同源才不会出现「预加载下一章，翻过去还是白屏」。
     */
    val imageRequests: ImageRequestManager = ImageRequestManager(
        context = appContext,
        imageLoader = imageLoader,
        stats = imageStats,
        decodeConcurrency = imageDeviceProfile.concurrency
    )
}
