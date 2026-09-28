package com.github.wekite.features.items.chat.aichat

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import com.github.wekite.utils.WeLogger
import java.util.Collections
import java.util.WeakHashMap

/**
 * 「AI」小徽标：挂在**全自动回复发出**的消息气泡旁（仅本机可见，视图层注入，对方看不到）。
 * 手动确认发送的建议回复不打标。
 *
 * ⚠️ 只能挂进**消息行 View 自身**（或它的子容器）；挂到 RecyclerView 上无效
 * （列表子项由 LayoutManager 管，外部 addView 不参与布局、看不见）。
 */
object AiBadge {
    private const val TAG = "AiBadge"
    private val attached = Collections.synchronizedMap(WeakHashMap<View, View>())

    fun attach(anchor: View) {
        if (attached.containsKey(anchor)) return
        val host = anchor as? ViewGroup ?: run {
            WeLogger.i(TAG, "skip badge: message view is not a ViewGroup (${anchor.javaClass.simpleName})")
            return
        }
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
        val ok = try {
            when (host) {
                is RelativeLayout -> {
                    host.addView(
                        badge,
                        RelativeLayout.LayoutParams(
                            RelativeLayout.LayoutParams.WRAP_CONTENT,
                            RelativeLayout.LayoutParams.WRAP_CONTENT,
                        ).apply {
                            addRule(RelativeLayout.ALIGN_PARENT_END)
                            addRule(RelativeLayout.ALIGN_PARENT_TOP)
                            marginEnd = dp(4)
                        }
                    )
                    true
                }
                is LinearLayout -> {
                    // 竖直列表：贴到最上面一行、靠右
                    host.addView(
                        badge,
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        ).apply { gravity = Gravity.END }
                    )
                    true
                }
                else -> {
                    host.addView(
                        badge,
                        FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.WRAP_CONTENT,
                            FrameLayout.LayoutParams.WRAP_CONTENT,
                            Gravity.TOP or Gravity.END,
                        ).apply { marginEnd = dp(4) }
                    )
                    true
                }
            }
        } catch (e: Exception) {
            WeLogger.w(TAG, "attach failed: ${e.javaClass.simpleName}")
            false
        }
        if (ok) {
            attached[anchor] = badge
            WeLogger.i(TAG, "badge attached (host=${host.javaClass.simpleName})")
        }
    }

    fun detach(anchor: View) {
        attached.remove(anchor)?.let { (it.parent as? ViewGroup)?.removeView(it) }
    }
}
