package com.oprecorder

import android.app.Application
import com.oprecorder.data.AppDatabase

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // 预初始化数据库
        AppDatabase.getInstance(this)
    }
}
