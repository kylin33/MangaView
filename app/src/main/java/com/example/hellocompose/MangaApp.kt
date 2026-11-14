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
}
