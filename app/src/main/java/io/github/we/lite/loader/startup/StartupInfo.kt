package io.github.we.lite.loader.startup

import io.github.we.lite.loader.abc.IHookBridge
import io.github.we.lite.loader.abc.ILoaderService

object StartupInfo {

    lateinit var modulePath: String
    lateinit var loaderService: ILoaderService
    var hookBridge: IHookBridge? = null
}
