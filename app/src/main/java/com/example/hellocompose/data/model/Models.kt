package com.example.hellocompose.data.model

/** 列表页的一本漫画 */
data class Manga(
    val title: String,
    val coverUrl: String,
    val detailUrl: String
)

/** 详情页的一个章节 */
data class Chapter(
    val title: String,
    val url: String
)
