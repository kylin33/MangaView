package com.example.hellocompose.data.source

import com.example.hellocompose.core.AppResult
import com.example.hellocompose.core.EmptyPageException
import com.example.hellocompose.core.appRunCatching
import com.example.hellocompose.data.model.Manga
import com.example.hellocompose.data.remote.MangaScraper

/**
 * 用一份「还没保存」的配置试抓一次首页。
 *
 * 这是添加站点流程的胜负手：字段填满分不出对错，只有真的抓一次才知道
 * 选择器是不是套在别的站点上。所以保存前必须让用户看到「解析到几部、长什么样」。
 *
 * 复用 [MangaScraper] 而不是另写一套抓取逻辑，保证试抓和正式运行的行为完全一致 ——
 * 否则会出现「试抓通过、保存后空白」这种最坑的情况。
 */
class SourceProbe(private val scraper: MangaScraper) {

    suspend fun run(config: SourceConfig): AppResult<List<Manga>> = appRunCatching {
        val items = scraper.fetchHome(config)
        if (items.isEmpty()) {
            throw EmptyPageException(
                "页面能打开，但 ${config.home.item} 一个都没匹配到，请检查 home.item 选择器"
            )
        }
        items
    }
}
