package com.github.wekite.loader.startup

import com.github.wekite.loader.abc.IHookBridge
import com.github.wekite.loader.abc.ILoaderService

object StartupInfo {

    lateinit var modulePath: String
    lateinit var loaderService: ILoaderService
    var hookBridge: IHookBridge? = null
}
