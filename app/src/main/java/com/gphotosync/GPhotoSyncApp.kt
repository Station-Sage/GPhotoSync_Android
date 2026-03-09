package com.gphotosync

import android.app.Application

class GPhotoSyncApp : Application() {
    override fun onCreate() {
        super.onCreate()
        TokenManager.init(this)
    }
}
