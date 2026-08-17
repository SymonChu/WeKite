package com.github.wekite.features.items.beautify

import android.content.res.Resources
import android.view.View
import android.view.ViewGroup
import com.github.wekite.dexkit.abc.IResolveDex
import com.github.wekite.dexkit.dsl.dexMethod
import com.github.wekite.features.core.Feature
import com.github.wekite.features.core.SwitchFeature
import com.github.wekite.utils.WeLogger

@Feature(name = "隐藏对话列表分割线", categories = ["界面美化"], description = "隐藏主页对话列表里对话间的分割线")
object HideConversationListDividers : SwitchFeature(), IResolveDex {

    private const val TAG = "HideConversationListDividers"

    private val methodConversationWithCacheAdapterGetView by dexMethod(allowFailure = true) {
        searchPackages("com.tencent.mm.ui.conversation")
        matcher {
            name = "getView"
            usingEqStrings("MicroMsg.ConversationWithCacheAdapter", "Get Item duplicated: positionMaps: %s username [%s, %d] Map: %s datas: %d")
        }
    }

    private val methodMvvmConversationAdapterGetView by dexMethod(allowFailure = true) {
        matcher {
            declaredClass {
                usingEqStrings("MicroMsg.ConversationAdapter.MvvmConversationAdapter", "Get Item duplicated: positionMaps: %s username [%s, %d] Map: %s datas: %d")
            }
            name = "getView"
        }
    }

    override fun onEnable() {
        var armed = false
        if (!methodConversationWithCacheAdapterGetView.isPlaceholder) {
            methodConversationWithCacheAdapterGetView.hookAfter {
                val viewGroup = result as? ViewGroup? ?: return@hookAfter
                handleViewGroup(viewGroup)
            }
            armed = true
        }

        if (!methodMvvmConversationAdapterGetView.isPlaceholder) {
            methodMvvmConversationAdapterGetView.hookAfter {
                val viewGroup = result as? ViewGroup? ?: return@hookAfter
                handleViewGroup(viewGroup)
            }
            armed = true
        }

        WeLogger.i(TAG, if (armed) "divider hook armed" else "no adapter matched (placeholder) — feature inactive")
    }

    private fun handleViewGroup(viewGroup: ViewGroup) {
        // getView 刚返回时 item 尚未 measure/layout, 子 view 高度为 0, 无法按高度
        // 特征识别分割线; post 到下一帧 (layout 完成后) 再处理。
        viewGroup.post { hideDividers(viewGroup) }
    }

    private fun hideDividers(v: View) {
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                hideDividers(v.getChildAt(i))
            }
        }
        if (v.visibility != View.GONE && isDivider(v)) {
            v.visibility = View.GONE
        }
    }

    // 分割线特征: 普通 View (非 ViewGroup, 无子视图), 高度极小 (<=6dp),
    // 宽度接近列表宽度 (>= 屏宽 2/3)。
    // 不依赖固定子索引——旧实现 findViewByChildIndexes(0,1,1,1) 在微信改版布局
    // (8.0.7x 等) 后索引失效, 分割线隐藏完全没有作用; 递归 + 形态特征对布局
    // 变化免疫, 只要分割线仍是 item 内的一个细长 View 就能命中。
    private fun isDivider(v: View): Boolean {
        if (v is ViewGroup) return false
        val h = v.height
        if (h <= 0 || h > maxDividerHeightPx) return false
        val w = v.width
        return w >= v.rootView.width * 2 / 3
    }

    private val maxDividerHeightPx: Int
        get() = (6f * Resources.getSystem().displayMetrics.density).toInt()
}
