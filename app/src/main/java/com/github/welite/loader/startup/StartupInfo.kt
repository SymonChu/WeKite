package com.github.welite.loader.startup

import com.github.welite.loader.abc.IHookBridge
import com.github.welite.loader.abc.ILoaderService

object StartupInfo {

    lateinit var modulePath: String
    lateinit var loaderService: ILoaderService
    var hookBridge: IHookBridge? = null
}
