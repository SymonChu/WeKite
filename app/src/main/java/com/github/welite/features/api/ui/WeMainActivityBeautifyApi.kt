package com.github.welite.features.api.ui

import com.github.welite.dexkit.abc.IResolveDex
import com.github.welite.dexkit.dsl.dexMethod
import com.github.welite.features.core.ApiFeature
import com.github.welite.features.core.Feature

@Feature(name = "微信主屏幕美化服务", categories = ["API"], description = "提供美化微信主屏幕的能力")
object WeMainActivityBeautifyApi : ApiFeature(), IResolveDex {

    val methodDoOnCreate by dexMethod {
        matcher {
            declaredClass = "com.tencent.mm.ui.MainTabUI"
            usingEqStrings("MicroMsg.LauncherUI.MainTabUI", "doOnCreate")
        }
    }
}
