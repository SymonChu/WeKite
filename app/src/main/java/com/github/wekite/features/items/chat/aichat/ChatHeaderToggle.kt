package com.github.wekite.features.items.chat.aichat

import android.app.Activity
import android.app.AlertDialog
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Switch
import com.github.wekite.utils.WeLogger
import java.util.Collections
import java.util.WeakHashMap

/**
 * 聊天页右上角「AI」开关（学 yanwai HostUi 的思路，独立实现）：
 * - 在 ActionBarContainer（聊天页顶部标题栏）里塞一个原生 Switch，右缘避开菜单按钮
 * - 开关状态 = [AiChatStore.isAnalyzeOn]（每聊天独立，MMKV 记住）
 * - 长按开关 → 快捷菜单（自动回复开关/建议回复开关/助手设置）
 * - 只追加控件；切聊天/离开聊天时 [sync] 重新定位，[remove] 摘除并恢复标题宽度
 */
object ChatHeaderToggle {
    private const val TAG = "AiHeaderToggle"

    private var control: Switch? = null
    private var title: android.widget.TextView? = null
    private var oldTitleWidth = Int.MAX_VALUE
    private var oldEllipsize: TextUtils.TruncateAt? = null
    private var syncing = false
    private var currentTalker: String? = null
    private val populated = Collections.synchronizedMap(WeakHashMap<Activity, Boolean>())

    /** 每次聊天页 resume / 切聊天时调用。talker=null 表示不在聊天页。 */
    fun sync(activity: Activity, talker: String?) {
        currentTalker = talker
        val header = findHeader(activity) ?: run { remove(); return }
        if (talker == null) { remove(); return }

        if (control?.parent === header && control?.isShown == true) {
            // 已挂着：只刷新状态（切会话时走这里，必须按新 talker 重新读）
            val on = AiChatStore.isAnalyzeOn(talker)
            syncing = true
            control?.isChecked = on
            syncing = false
            WeLogger.i(TAG, "sync existing toggle talker=$talker on=$on")
            return
        }
        remove()

        val density = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val toggle = Switch(activity).apply {
            text = "AI"
            textSize = 12f
            switchPadding = dp(3)
            minHeight = dp(48)
            setPadding(dp(4), 0, dp(4), 0)
            setOnCheckedChangeListener { _, checked ->
                if (syncing) return@setOnCheckedChangeListener
                val t = currentTalker ?: return@setOnCheckedChangeListener
                AiChatStore.setAnalyzeOn(t, checked)
                WeLogger.i(TAG, "toggle clicked talker=$t enabled=$checked")
                if (!checked) { ChatAiEngine.clear(t); SuggestionPanel.refresh(t) }
            }
            setOnLongClickListener {
                showActions(activity, talker)
                true
            }
        }
        control = toggle

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
            toggle,
            FrameLayout.LayoutParams(-2, dp(48), Gravity.END or Gravity.CENTER_VERTICAL).apply {
                rightMargin = menuSpace
            }
        )

        // 标题让宽，避免和开关重叠
        toggle.post {
            if (control !== toggle || !toggle.isAttachedToWindow) return@post
            val t = descendants(header).filterIsInstance<android.widget.TextView>().firstOrNull {
                it !== toggle && it.isShown && it.text.isNotBlank() && it.width > dp(90)
            }
            title = t
            t?.let {
                oldTitleWidth = it.maxWidth
                oldEllipsize = it.ellipsize
                it.maxWidth = (header.width - 2 * (toggle.width + menuSpace)).coerceAtLeast(dp(60))
                it.ellipsize = TextUtils.TruncateAt.END
            }
            populated[activity] = true
        }
    }

    fun remove() {
        control?.let { (it.parent as? ViewGroup)?.removeView(it) }
        control = null
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
                when (which) {
                    0 -> {
                        if (!AiChatConfig.autoReplyConsent) {
                            confirmAutoReplyConsent(activity) { AiChatStore.setAutoReplyOn(talker, true) }
                        } else {
                            AiChatStore.setAutoReplyOn(talker, !AiChatStore.isAutoReplyOn(talker))
                        }
                        sync(activity, talker)
                    }
                    1 -> if (isGroup) {
                        AiChatStore.setGroupAllMessages(talker, !AiChatStore.isGroupAllMessages(talker))
                    } else {
                        ChatAiEngine.retry(talker)
                    }
                    2 -> if (isGroup) ChatAiEngine.retry(talker) else AiChatAssistant.openSettingsDialog(activity, talker)
                    3 -> AiChatAssistant.openSettingsDialog(activity, talker)
                }
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun confirmAutoReplyConsent(activity: Activity, onAgree: () -> Unit) {
        AlertDialog.Builder(activity)
            .setTitle("开启全自动回复")
            .setMessage(
                "AI 将在本聊天自动生成并直接发送回复（延迟 ${AiChatConfig.autoReplyDelaySec}s 内可关闭开关撤回），" +
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
