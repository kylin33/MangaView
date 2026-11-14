package com.example.hellocompose.data.image

import com.example.hellocompose.engine.reader.PagePreloader
import com.example.hellocompose.engine.source.Page

/**
 * 阅读引擎的预加载实现：把标准化页面交给图片请求层。
 *
 * 引擎只决定「预哪几页」，怎么下、怎么解、缓存在哪、并发多少由图片层负责。
 * 换图片库只需要换这个适配器，阅读引擎一行都不用动。
 */
class CoilPagePreloader(
    private val images: ImageRequestManager
) : PagePreloader {

    override fun preload(page: Page) = images.preload(page)

    override fun retainOnly(urls: Collection<String>) = images.retainOnly(urls)
}
