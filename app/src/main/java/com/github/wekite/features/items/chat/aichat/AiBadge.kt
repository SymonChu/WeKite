package com.github.wekite.features.items.chat.aichat

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.RelativeLayout
import android.widget.TextView
import java.util.Collections
import java.util.WeakHashMap

/**
 * 「AI」小徽标：挂在**全自动回复发出**的消息气泡旁（仅本机可见，视图层注入，对方看不到）。
 * 手动确认发送的建议回复不打标。
 */
object AiBadge {
    private val attached = Collections.synchronizedMap(WeakHashMap<View, View>())

    fun attach(anchor: View) {
        if (attached.containsKey(anchor)) return
        val parent = anchor.parent as? ViewGroup ?: return
        val density = anchor.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val badge = TextView(anchor.context).apply {
            text = "AI"
            textSize = 9f
            includeFontPadding = false
            setTextColor(0xFF9ECBFF.toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(6).toFloat()
                setColor(0x269ECBFF)
                setStroke(1, 0x449ECBFF)
            }
            setPadding(dp(5), dp(2), dp(5), dp(2))
        }
        val added = when (parent) {
            is RelativeLayout -> {
                if (anchor.id == View.NO_ID) return
                parent.addView(
                    badge,
                    RelativeLayout.LayoutParams(
                        RelativeLayout.LayoutParams.WRAP_CONTENT, RelativeLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        addRule(RelativeLayout.ALIGN_PARENT_RIGHT)
                        addRule(RelativeLayout.ALIGN_TOP, anchor.id)
                        topMargin = dp(-6)
                        marginEnd = dp(4)
                    }
                )
                true
            }
            is FrameLayout -> {
                parent.addView(
                    badge,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.TOP or Gravity.END
                    ).apply { topMargin = dp(-6); marginEnd = dp(4) }
                )
                true
            }
            else -> false
        }
        if (added) attached[anchor] = badge
    }

    fun detach(anchor: View) {
        attached.remove(anchor)?.let { (it.parent as? ViewGroup)?.removeView(it) }
    }
}
