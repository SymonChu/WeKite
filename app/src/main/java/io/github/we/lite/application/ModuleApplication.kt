package io.github.we.lite.application

import android.app.Application
import io.github.we.lite.utils.HostInfo

class ModuleApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        HostInfo.init(this)
    }
}
