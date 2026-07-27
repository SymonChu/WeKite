package io.github.we.lite.features.items.system

import android.content.Context
import android.widget.Button
import androidx.compose.material3.Text
import androidx.core.view.isGone
import androidx.core.view.isVisible
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.toClass
import io.github.we.lite.dexkit.abc.IResolveDex
import io.github.we.lite.dexkit.dsl.dexMethod
import io.github.we.lite.features.core.Feature
import io.github.we.lite.features.core.SwitchFeature
import io.github.we.lite.ui.content.AlertDialogContent
import io.github.we.lite.ui.content.Button
import io.github.we.lite.ui.content.TextButton
import io.github.we.lite.ui.utils.showComposeDialog

@Feature(name = "强制平板模式", categories = ["系统与隐私"], description = "让微信将当前设备识别为平板")
object ForceTabletMode : SwitchFeature(), IResolveDex {

    private val methodIsTablet by dexMethod {
        matcher {
            usingEqStrings("Lenovo TB-9707F", "eebbk")
        }
    }
    private val methodIsTablet2 by dexMethod {
        matcher {
            usingEqStrings("MicroMsg.UIUtils", "isRoyoleFoldableDevice!!!")
        }
    }
    private val methodOtherDeviceLoginButtonIsVisible by dexMethod {
        matcher {
            usingEqStrings("loginAsOtherDeviceBtn")
        }
    }
    private val methodCgiCheckLoginAsPad by dexMethod {
        matcher {
            usingEqStrings("MicroMsg.CgiCheckLoginAsPad", "/cgi-bin/micromsg-bin/checkloginaspad")
        }
    }

    override fun onEnable() {
        methodIsTablet.hookBefore {
            result = true
        }

        methodIsTablet2.hookBefore {
            result = true
        }

        methodOtherDeviceLoginButtonIsVisible.hookBefore {
            val view = args[0] as? Button? ?: return@hookBefore
            if (view.isGone) view.isVisible = true
        }

        "com.tencent.mm.plugin.account.ui.LoginHistoryUI".toClass().reflekt().firstMethod("initView").hookAfter {
            val btn = thisObject!!.reflekt().firstField {
                type = Button::class
            }.get()!! as Button
            btn.isVisible = true
        }

        methodCgiCheckLoginAsPad.hookBefore {
            result = true
        }
    }

    override fun onBeforeToggle(newState: Boolean, context: Context): Boolean {
        if (newState) {
            showComposeDialog(context) {
                AlertDialogContent(
                    title = { Text(text = "警告") },
                    text = { Text(text = "此功能可能导致账号异常, 确定要启用吗?") },
                    confirmButton = {
                        Button(onClick = {
                            applyToggle(true)
                            onDismiss()
                        }) {
                            Text("确定")
                        }
                    },
                    dismissButton = {
                        TextButton(onDismiss) {
                            Text("取消")
                        }
                    }
                )
            }
            return false
        }

        return true
    }
}
