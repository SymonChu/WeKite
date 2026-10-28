package com.github.wekite.application

import android.app.Application
import com.github.wekite.loader.utils.NativeLoader
import com.github.wekite.utils.HostInfo

class ModuleApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        HostInfo.init(this)
        // This process is the module's own settings app, which never runs the hook bootstrap.
        // MMKV must be initialized here or the first Compose frame that reads WePrefs crashes.
        NativeLoader.initModuleProcess(this)
    }
}
