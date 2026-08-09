package com.github.wekite.features.items.system

import android.content.Context
import androidx.compose.material3.Text
import com.github.wekite.dexkit.abc.IResolveDex
import com.github.wekite.dexkit.dsl.dexMethod
import com.github.wekite.features.core.Feature
import com.github.wekite.features.core.SwitchFeature
import com.github.wekite.ui.content.AlertDialogContent
import com.github.wekite.ui.content.TextButton
import com.github.wekite.ui.utils.showComposeDialog
import com.github.wekite.utils.HostInfo

@Feature(name = "禁止微信检测 Xposed", categories = ["系统与隐私"], description = "防止微信检测 Xposed 框架是否存在")
object PreventXposedDetection : SwitchFeature(), IResolveDex {

    private val methodCheckStackTraceElements by dexMethod(allowFailure = true) {
        searchPackages("com.tencent.mm.app")
        matcher {
            usingEqStrings(
                "de.robv.android.xposed.XposedBridge",
                "com.zte.heartyservice.SCC.FrameworkBridge"
            )
        }
    }

    override fun onEnable() {
        if (methodCheckStackTraceElements.isPlaceholder || HostInfo.isHostGooglePlay) return

        methodCheckStackTraceElements.hookBefore {
            result = false
        }
    }

    override fun onBeforeToggle(newState: Boolean, context: Context): Boolean {
        if (newState && HostInfo.isHostGooglePlay) {
            showComposeDialog(context) {
                AlertDialogContent(
                    title = { Text("禁止微信检测 Xposed") },
                    text = {
                        Text("Google Play 版微信无此检测, 开启可能导致闪退, 已关闭功能!")
                    },
                    confirmButton = { TextButton(onDismiss) { Text("取消") } })
            }
            return false
        }

        return true
    }
}
