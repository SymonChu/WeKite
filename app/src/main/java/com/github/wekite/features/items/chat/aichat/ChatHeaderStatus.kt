package com.github.wekite.features.items.chat.aichat

import android.app.Activity
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.github.wekite.utils.WeLogger
import java.util.Collections
import java.util.WeakHashMap

/**
 * 聊天页右上角「AI 状态」显示（**只显示状态，不是开关**）。
 *
 * 用户 2026-09-28 定案：开关放到「群详情 / 联系人详情」页，头部只保留一个状态提示。
 * - 灰 `AI` = 本聊天未开启
 * - 蓝 `AI` = 自动分析已开
 * - 蓝 `AI…` = 正在分析
 * - 蓝 `AI+` = 全自动回复已开（优先级最高）
 * - **点它 = 打开助手设置**
 *
 * 状态来源：`AiChatStore` 按 [ChatUi.talker]（页面 Intent 自带）读，不会错位。
 */
object ChatHeaderStatus {
    private const val TAG = "AiHeaderStatus"
    private val BLUE = 0xFF1F6FEB.toInt()
    private val GREY = 0xFF9AA3B2.toInt()

    private var label: TextView? = null
    private var title: TextView? = null
    private var oldTitleWidth = Int.MAX_VALUE
    private var oldEllipsize: TextUtils.TruncateAt? = null
    private var boundTalker: String? = null
    private val populated = Collections.synchronizedMap(WeakHashMap<Activity, Boolean>())

    /** resume 时调用：把状态贴到标题栏右侧。 */
    fun sync(activity: Activity, talker: String?) {
        if (talker.isNullOrBlank()) { remove(); return }
        boundTalker = talker
        val header = findHeader(activity) ?: run { remove(); return }
        val density = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val existing = label
        if (existing?.parent === header && existing.isShown) {
            refreshText()
            return
        }
        remove()

        val tv = TextView(activity).apply {
            textSize = 12f
            includeFontPadding = false
            setPadding(dp(4), dp(6), dp(4), dp(6))
            isClickable = true
            setOnClickListener {
                val t = boundTalker
                if (t != null) AiChatAssistant.openSettingsDialog(activity, t)
            }
        }
        label = tv
        header.addView(
            tv,
            FrameLayout.LayoutParams(-2, dp(44), Gravity.END or Gravity.CENTER_VERTICAL).apply {
                rightMargin = menuSpaceFor(header, density)
            }
        )
        tv.post {
            if (label !== tv || !tv.isAttachedToWindow) return@post
            val t = descendants(header).filterIsInstance<TextView>().firstOrNull {
                it !== tv && it.isShown && it.text.isNotBlank() && it.width > dp(90)
            }
            title = t
            t?.let {
                oldTitleWidth = it.maxWidth
                oldEllipsize = it.ellipsize
                it.maxWidth = (header.width - 2 * (tv.width + dp(56))).coerceAtLeast(dp(60))
                it.ellipsize = TextUtils.TruncateAt.END
            }
            populated[activity] = true
        }
        refreshText()
        WeLogger.i(TAG, "status attached talker=$talker")
    }

    /** 状态变化时刷新文字/颜色（分析开始/结束、设置里改开关后调用）。 */
    fun refreshText() {
        val tv = label ?: return
        val talker = boundTalker ?: return
        val analyzing = ChatAiEngine.stateOf(talker) is ChatAiEngine.State.Working
        val auto = AiChatStore.isAutoReplyOn(talker)
        val on = AiChatStore.isAnalyzeOn(talker)
        val (text, color) = when {
            auto -> "AI+" to BLUE
            analyzing -> "AI…" to BLUE
            on -> "AI" to BLUE
            else -> "AI" to GREY
        }
        tv.text = text
        tv.setTextColor(color)
        tv.contentDescription = "AI 聊天助手状态：$text（点击打开设置）"
    }

    fun remove() {
        label?.let { (it.parent as? ViewGroup)?.removeView(it) }
        label = null
        boundTalker = null
        title?.let { it.maxWidth = oldTitleWidth; it.ellipsize = oldEllipsize }
        title = null
    }

    private fun menuSpaceFor(header: FrameLayout, density: Float): Int {
        fun dp(v: Int) = (v * density).toInt()
        val headerPos = IntArray(2).also { header.getLocationOnScreen(it) }
        val menuLeftX = descendants(header)
            .filter { it.isShown && it.isClickable && it.width in 1..(header.width / 3) }
            .mapNotNull { v ->
                val p = IntArray(2).also { v.getLocationOnScreen(it) }
                (p[0] - headerPos[0]).takeIf { it > header.width * 0.7 }
            }.minOrNull()
        return menuLeftX?.let { header.width - it + dp(4) } ?: dp(60)
    }

    private fun findHeader(activity: Activity): FrameLayout? {
        val decor = activity.window?.decorView ?: return null
        return descendants(decor).firstOrNull {
            it.isShown && it.javaClass.name == "androidx.appcompat.widget.ActionBarContainer"
        } as? FrameLayout
    }

    private fun descendants(root: View): List<View> {
        val result = mutableListOf<View>()
        fun walk(view: View, depth: Int) {
            if (depth > 40 || result.size > 4000 || view.visibility != View.VISIBLE) return
            result += view
            if (view is ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i), depth + 1)
        }
        walk(root, 0)
        return result
    }
}
