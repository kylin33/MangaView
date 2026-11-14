package com.example.hellocompose.data.image

import android.content.Context
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.example.hellocompose.core.AppConfig

/**
 * 图片请求工厂。漫画站普遍有防盗链，必须带上 Referer 才能拿到图，
 * 所以所有图片请求都从这里出，避免各处漏掉请求头。
 */
object MangaImageRequests {

    fun build(context: Context, imageUrl: String, referer: String): ImageRequest =
        ImageRequest.Builder(context)
            .data(imageUrl)
            // 站点没配 Referer 时不要发空头，某些 CDN 会把空 Referer 当盗链
            .apply { if (referer.isNotBlank()) addHeader("Referer", referer) }
            .addHeader("User-Agent", AppConfig.USER_AGENT)
            .diskCachePolicy(CachePolicy.ENABLED)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .crossfade(true)
            .build()
}
