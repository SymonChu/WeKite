package io.github.we.lite.features.api.ui

import io.github.we.lite.dexkit.abc.IResolveDex
import io.github.we.lite.dexkit.dsl.dexMethod
import io.github.we.lite.features.core.ApiFeature
import io.github.we.lite.features.core.Feature

@Feature(name = "微信主屏幕美化服务", categories = ["API"], description = "提供美化微信主屏幕的能力")
object WeMainActivityBeautifyApi : ApiFeature(), IResolveDex {

    val methodDoOnCreate by dexMethod {
        matcher {
            declaredClass = "com.tencent.mm.ui.MainTabUI"
            usingEqStrings("MicroMsg.LauncherUI.MainTabUI", "doOnCreate")
        }
    }
}
