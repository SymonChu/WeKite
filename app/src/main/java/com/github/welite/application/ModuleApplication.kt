package com.github.welite.application

import android.app.Application
import com.github.welite.utils.HostInfo

class ModuleApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        HostInfo.init(this)
    }
}
