package com.example.mangacolorizer

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class MangaColorizerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        com.example.mangacolorizer.utils.Logger.init(this)
    }
}