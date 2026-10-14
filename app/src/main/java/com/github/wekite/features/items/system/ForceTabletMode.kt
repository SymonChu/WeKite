package com.github.wekite.features.items.system

import android.content.Context
import android.widget.Button
import androidx.compose.material3.Text
import androidx.core.view.isGone
import androidx.core.view.isVisible
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.toClass
import com.github.wekite.dexkit.abc.IResolveDex
import com.github.wekite.dexkit.dsl.dexMethod
import com.github.wekite.features.core.Feature
import com.github.wekite.features.core.SwitchFeature
import com.github.wekite.ui.content.AlertDialogContent
import com.github.wekite.ui.content.Button
import com.github.wekite.ui.content.TextButton
import com.github.wekite.ui.utils.showComposeDialog
import org.luckypray.dexkit.DexKitBridge

@Feature(name = "强制平板模式", categories = ["系统与隐私"], description = "让微信将当前设备识别为平板")
object ForceTabletMode : SwitchFeature(), IResolveDex {

    /**
     * 「本机是不是平板」的判定方法。**不能只用一个锚点**：8.0.78 起微信把这个判定重构了。
     *
     * 2026-10-14 实测（微信 8.0.79 build 3200，18 个 dex，离线字节级扫描 `dex_anchor_atlas.py`）：
     * - 老锚点 `usingEqStrings("Lenovo TB-9707F", "eebbk")` **零命中** —— 两个串都还在字符串池里，
     *   但已不在同一个方法体：`Lenovo TB-9707F` 落到 `Lcom/tencent/mm/ui/j9;::a`，
     *   `eebbk` 落到 `Lcom/tencent/mm/ui/ok;::C`（DexKit 的 usingEqStrings 要求同处一个方法体）；
     * - 8.0.77(3141) 上老锚点命中唯一：`Lcom/tencent/mm/ui/fk;::B(Lns5/w0;)Z`；
     * - 新锚点 `usingEqStrings("MicroMsg.UIUtils", "inTabletEnv, no tablet condition matched, return false")`
     *   在 8.0.79 上命中唯一：`Lcom/tencent/mm/ui/ok;::C(Lpv5/w0;)Z`（8.0.77 上也存在）。
     *
     * 所以：**先试老锚点（≤8.0.77 行为逐字节不变），零命中再退到新锚点（8.0.78+）**。
     * 上游同样在 8.0.78 的适配里换用了新锚点（`058db842`），这里只是把「换」改成「按版本退」。
     *
     * ⚠️ 锚点失效是 **strict delegate 抛异常 → 整个 feature 不加载**（`DexDelegates.kt:297`），
     * 用户侧表现为「设置里这个开关整块失灵 + DEX 扫描报告里一条 `No method found for key`」。
     */
    private val methodIsTablet by dexMethod()

    override fun resolveDex(dexKit: DexKitBridge) {
        val legacy = dexKit.findMethod {
            matcher {
                usingEqStrings("Lenovo TB-9707F", "eebbk")
            }
        }
        when (legacy.size) {
            1 -> {
                methodIsTablet.setDescriptor(legacy.single())
                return
            }

            0 -> Unit // 8.0.78+ 已拆家，落到下面的新锚点
            else -> error(
                "multiple legacy tablet methods found: " +
                    legacy.joinToString { it.descriptor }
            )
        }

        val modern = dexKit.findMethod {
            matcher {
                returnType = "boolean"
                usingEqStrings(
                    "MicroMsg.UIUtils",
                    "inTabletEnv, no tablet condition matched, return false",
                )
            }
        }
        when (modern.size) {
            1 -> methodIsTablet.setDescriptor(modern.single())
            else -> error(
                "expected exactly one modern tablet environment method, got ${modern.size}: " +
                    modern.joinToString { it.descriptor }
            )
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
