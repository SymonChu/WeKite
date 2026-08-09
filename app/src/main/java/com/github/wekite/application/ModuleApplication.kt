package com.github.wekite.application

import android.app.Application
import com.github.wekite.utils.HostInfo

class ModuleApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        HostInfo.init(this)
    }
}
