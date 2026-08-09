package com.github.wekite.features.items.miniapps

import com.github.wekite.dexkit.abc.IResolveDex
import com.github.wekite.dexkit.dsl.dexMethod
import com.github.wekite.features.core.Feature
import com.github.wekite.features.core.SwitchFeature
import com.github.wekite.utils.TargetProcesses

@Feature(name = "跳过启动页面", categories = ["小程序"], description = "跳过小程序启动页面, 变相去广告 (实验性)")
object SkipSplash : SwitchFeature(), IResolveDex {

    private val methodShowSplash by dexMethod {
        searchPackages("com.tencent.mm.plugin.appbrand")
        matcher {
            declaredClass = "com.tencent.mm.plugin.appbrand.AppBrandRuntime"
            returnType = "void"
            paramCount = 0
            usingEqStrings(
                "public:prepare",
                "Loading页展示",
                "MicroMsg.AppBrandRuntime",
                "showSplash[AppBrandSplashAd], appId:%s, splash:%s"
            )
        }
    }

    override val shouldLoadInCurrentProcess get() = TargetProcesses.isInMain || TargetProcesses.currentType == TargetProcesses.PROC_APPBRAND

    override fun onEnable() {
        methodShowSplash.hookBefore { result = null }
    }
}
