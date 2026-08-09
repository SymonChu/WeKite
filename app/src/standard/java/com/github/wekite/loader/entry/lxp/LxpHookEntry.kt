@file:Suppress("unused")

package com.github.wekite.loader.entry.lxp

import androidx.annotation.Keep
import com.github.wekite.constants.PackageNames
import com.github.wekite.loader.entry.common.ModuleLoader
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

@Keep
class LxpHookEntry : XposedModule() {

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        LxpHookImpl.init(this)
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (PackageNames.isWeChat(param.packageName)) {
            if (param.isFirstPackage) {
                val ai = param.applicationInfo
                ModuleLoader.init(
                    ai.dataDir,
                    param.classLoader,
                    LxpHookImpl,
                    LxpHookImpl,
                    this.moduleApplicationInfo.sourceDir,
                    true
                )
            }
        }
    }
}
