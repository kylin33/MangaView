package com.example.hellocompose

import android.app.Application
import com.example.hellocompose.di.AppContainer

class MangaApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    /**
     * 系统内存吃紧时把图片缓存让出来。
     *
     * 不改这里的话，后台留下的长图位图会一直占着堆，
     * 用户切回阅读页时反而更容易被系统杀掉——看起来像闪退。
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (::container.isInitialized) container.imageRequests.onTrimMemory(level)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        if (::container.isInitialized) container.imageRequests.onLowMemory()
    }
}
