package com.example.hellocompose.data.image

/**
 * 页面宽高比缓存（url → width / height）。
 *
 * 只解决一个体验问题：长图是连续滚动的，LazyColumn 在图片加载出来之前不知道
 * 每一页有多高。不知道高度就按占位高度排版，图一到就撑开，滚动位置会突然跳一下，
 * 章末尤其明显。
 *
 * 记下宽高比之后，第二次进同一章就能**先把高度占好**，图片加载只是填充内容，
 * 不再引发布局抖动。数据本身只有 4 字节，上限几百条，不需要落盘。
 */
class PageSizeCache(private val capacity: Int = 400) {

    private val ratios = object : LinkedHashMap<String, Float>(capacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Float>?): Boolean =
            size > capacity
    }

    /** 传原始尺寸，内部按 Compose 的 [androidx.compose.foundation.layout.aspectRatio] 约定存 */
    @Synchronized
    fun put(url: String, width: Int, height: Int) {
        if (url.isBlank() || width <= 0 || height <= 0) return
        ratios[url] = width.toFloat() / height
    }

    /** width / height，没有记录返回 null */
    @Synchronized
    fun aspectOf(url: String): Float? = ratios[url]

    @Synchronized
    fun clear() = ratios.clear()
}
