package com.github.wekite.features.items.chat.aichat

import android.app.Activity
import android.app.AlertDialog
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import com.github.wekite.utils.WeLogger
import java.util.Collections
import java.util.WeakHashMap

/**
 * 聊天页右上角「AI」入口（学 yanwai HostUi 的思路，独立实现）。
 *
 * 形态：`[AI 文字] [开关]` 一行贴在标题栏右侧、避开原生菜单按钮。
 * - **点「AI」文字 = 打开助手设置**（v3.46：原来只有长按，而 Switch 会吃掉长按事件 → 用户反馈进不去）
 * - 点开关 = 开关本聊天的自动分析（每聊天独立，聊天会话跟随器保证绑定正确）
 * - 长按整块 = 快捷菜单（全自动回复 / 群里所有消息也处理 / 重新分析 / 助手设置）
 *
 * 只追加控件；切聊天时 [sync] 重新绑定，[remove] 摘除并恢复标题宽度。
 */
object ChatHeaderToggle {
    private const val TAG = "AiHeaderToggle"

    private var container: LinearLayout? = null
    private var switchRef: Switch? = null
    private var title: TextView? = null
    private var oldTitleWidth = Int.MAX_VALUE
    private var oldEllipsize: TextUtils.TruncateAt? = null
    private var syncing = false
    private var currentTalker: String? = null
    private val populated = Collections.synchronizedMap(WeakHashMap<Activity, Boolean>())

    /** 当前绑定到哪个聊天（供设置弹窗取用）。 */
    fun talker(): String? = currentTalker

    /** 每次聊天会话变化 / resume 时调用。talker=null 表示不在聊天页。 */
    fun sync(activity: Activity, talker: String?) {
        currentTalker = talker
        val header = findHeader(activity) ?: run { remove(); return }
        if (talker == null) { remove(); return }

        if (container?.parent === header && container?.isShown == true) {
            // 已挂着：只刷新状态（切会话走这里，必须按新 talker 重新读）
            val on = AiChatStore.isAnalyzeOn(talker)
            syncing = true
            switchRef?.isChecked = on
            syncing = false
            WeLogger.i(TAG, "sync existing toggle talker=$talker on=$on")
            return
        }
        remove()

        val density = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        // 「AI」文字：点它进设置（比长按可靠）
        val label = TextView(activity).apply {
            text = "AI"
            textSize = 12f
            includeFontPadding = false
            setTextColor(0xFF9ECBFF.toInt())
            setPadding(dp(2), dp(4), dp(2), dp(4))
            contentDescription = "打开 AI 聊天助手设置"
            setOnClickListener {
                val t = currentTalker
                if (t != null) AiChatAssistant.openSettingsDialog(activity, t)
            }
        }
        val toggle = Switch(activity).apply {
            switchPadding = dp(3)
            minHeight = dp(44)
            setPadding(dp(2), 0, dp(2), 0)
            isChecked = AiChatStore.isAnalyzeOn(talker)
            setOnCheckedChangeListener { _, checked ->
                if (syncing) return@setOnCheckedChangeListener
                val t = currentTalker ?: return@setOnCheckedChangeListener
                AiChatStore.setAnalyzeOn(t, checked)
                WeLogger.i(TAG, "toggle clicked talker=$t enabled=$checked")
                if (!checked) { ChatAiEngine.clear(t); SuggestionPanel.refresh(t) }
            }
            setOnLongClickListener {
                val t = currentTalker
                if (t != null) showActions(activity, t)
                true
            }
        }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(label)
            addView(toggle)
            isLongClickable = true
            setOnLongClickListener {
                val t = currentTalker
                if (t != null) showActions(activity, t)
                true
            }
        }
        container = box
        switchRef = toggle

        // 右侧菜单按钮的左缘（宽度在 header 1/3 以内的可点击可见控件里，x > 70% 的最左者）
        val headerPos = IntArray(2).also { header.getLocationOnScreen(it) }
        val menuLeftX = descendants(header)
            .filter { it.isShown && it.isClickable && it.width in 1..(header.width / 3) }
            .mapNotNull { v ->
                val p = IntArray(2).also { v.getLocationOnScreen(it) }
                val x = p[0] - headerPos[0]
                x.takeIf { it > header.width * 0.7 }
            }.minOrNull()
        val menuSpace = menuLeftX?.let { header.width - it + dp(4) } ?: dp(60)

        header.addView(
            box,
            FrameLayout.LayoutParams(-2, dp(44), Gravity.END or Gravity.CENTER_VERTICAL).apply {
                rightMargin = menuSpace
            }
        )

        // 标题让宽，避免和入口重叠
        box.post {
            if (container !== box || !box.isAttachedToWindow) return@post
            val t = descendants(header).filterIsInstance<TextView>().firstOrNull {
                it !== label && it.isShown && it.text.isNotBlank() && it.width > dp(90)
            }
            title = t
            t?.let {
                oldTitleWidth = it.maxWidth
                oldEllipsize = it.ellipsize
                it.maxWidth = (header.width - 2 * (box.width + menuSpace)).coerceAtLeast(dp(60))
                it.ellipsize = TextUtils.TruncateAt.END
            }
            populated[activity] = true
            WeLogger.i(TAG, "toggle attached talker=$talker menuSpace=$menuSpace")
        }
    }

    fun remove() {
        container?.let { (it.parent as? ViewGroup)?.removeView(it) }
        container = null
        switchRef = null
        title?.let { it.maxWidth = oldTitleWidth; it.ellipsize = oldEllipsize }
        title = null
    }

    private fun showActions(activity: Activity, talker: String) {
        val isGroup = ContextBuilder.isGroupTalker(talker)
        val items = if (isGroup) arrayOf(
            "全自动回复：${if (AiChatStore.isAutoReplyOn(talker)) "已开" else "关"}",
            "群里所有消息也处理：${if (AiChatStore.isGroupAllMessages(talker)) "开" else "关（只回@我）"}",
            "重新分析最新一条",
            "助手设置",
        ) else arrayOf(
            "全自动回复：${if (AiChatStore.isAutoReplyOn(talker)) "已开" else "关"}",
            "重新分析最新一条",
            "助手设置",
        )
        AlertDialog.Builder(activity)
            .setTitle("AI 聊天助手 · ${talker.substringBefore("@")}")
            .setItems(items) { _, which ->
                when {
                    which == 0 -> {
                        if (!AiChatConfig.autoReplyConsent) {
                            confirmAutoReplyConsent(activity) { AiChatStore.setAutoReplyOn(talker, true) }
                        } else {
                            AiChatStore.setAutoReplyOn(talker, !AiChatStore.isAutoReplyOn(talker))
                        }
                    }
                    isGroup && which == 1 ->
                        AiChatStore.setGroupAllMessages(talker, !AiChatStore.isGroupAllMessages(talker))
                    isGroup && which == 2 -> ChatAiEngine.retry(talker)
                    isGroup && which == 3 -> AiChatAssistant.openSettingsDialog(activity, talker)
                    !isGroup && which == 1 -> ChatAiEngine.retry(talker)
                    !isGroup && which == 2 -> AiChatAssistant.openSettingsDialog(activity, talker)
                }
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun confirmAutoReplyConsent(activity: Activity, onAgree: () -> Unit) {
        AlertDialog.Builder(activity)
            .setTitle("开启全自动回复")
            .setMessage(
                "AI 将在本对话自动生成并直接发送回复（有延迟窗口，关掉开关即撤回待发消息），" +
                    "发出的消息带「AI」标记（仅你可见）。存在误回复与账号风险，确定开启？"
            )
            .setPositiveButton("确定开启") { _, _ ->
                AiChatConfig.autoReplyConsent = true
                onAgree()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** ActionBarContainer：聊天页顶部标题栏容器（androidx 公开类名，不混淆）。 */
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
