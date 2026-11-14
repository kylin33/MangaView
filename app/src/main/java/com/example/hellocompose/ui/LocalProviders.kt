package com.example.hellocompose.ui

import androidx.compose.runtime.staticCompositionLocalOf
import coil.ImageLoader
import com.example.hellocompose.di.AppContainer

/** 依赖容器，由 MainActivity 在根节点注入，页面内通过 LocalAppContainer.current 取用 */
val LocalAppContainer = staticCompositionLocalOf<AppContainer> {
    error("AppContainer 未提供：请确认已在 setContent 中通过 CompositionLocalProvider 注入")
}

/** 全局 Coil 实例，保证预加载与展示共用同一份缓存 */
val LocalImageLoader = staticCompositionLocalOf<ImageLoader> {
    error("ImageLoader 未提供：请确认已在 setContent 中通过 CompositionLocalProvider 注入")
}
