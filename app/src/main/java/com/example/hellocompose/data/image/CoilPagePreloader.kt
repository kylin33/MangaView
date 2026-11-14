package com.example.hellocompose.data.image

import android.content.Context
import coil.ImageLoader
import com.example.hellocompose.engine.reader.PagePreloader
import com.example.hellocompose.engine.source.Page

/**
 * 阅读引擎的预加载实现：把标准化页面交给 Coil。
 *
 * 引擎只决定「预哪几页」，具体怎么解码、缓存在哪、并发多少由图片库负责。
 * 换图片库只需要换这个适配器，阅读引擎一行都不用动。
 */
class CoilPagePreloader(
    private val context: Context,
    private val imageLoader: ImageLoader
) : PagePreloader {

    override fun preload(page: Page) {
        imageLoader.enqueue(
            MangaImageRequests.build(context, page.url, page.referer)
        )
    }
}
