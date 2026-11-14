package com.example.hellocompose.core

/**
 * 全局兜底配置。
 *
 * 站点地址、请求头、选择器这类「换个网站就变」的东西已经全部搬到
 * assets/sources 目录下的 JSON，这里只保留所有站点共用、且极少改动的默认值。
 */
object AppConfig {

    /** 站点未单独配置 User-Agent 时使用 */
    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"

    const val TIMEOUT_MS = 15_000L

    const val DEFAULT_PRELOAD = 4
    const val MAX_PRELOAD = 15

    /** 内置站点配置所在的 assets 子目录 */
    const val ASSETS_SOURCE_DIR = "sources"

    /** 用户自定义配置目录（应用私有目录下的同名子目录，会覆盖同 id 的内置配置） */
    const val USER_SOURCE_DIR = "sources"
}
