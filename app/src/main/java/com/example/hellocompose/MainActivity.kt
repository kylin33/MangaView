package com.example.hellocompose

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import com.example.hellocompose.ui.LocalAppContainer
import com.example.hellocompose.ui.LocalImageLoader
import com.example.hellocompose.ui.navigation.MangaNavHost
import com.example.hellocompose.ui.theme.MangaTheme

/**
 * 只做三件事：接管窗口、注入依赖、挂载导航。
 * 页面逻辑一律在各自的 Screen + ViewModel 里，Activity 不参与业务。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val container = (application as MangaApp).container

        setContent {
            CompositionLocalProvider(
                LocalAppContainer provides container,
                LocalImageLoader provides container.imageLoader
            ) {
                MangaTheme {
                    MangaNavHost()
                }
            }
        }
    }
}
