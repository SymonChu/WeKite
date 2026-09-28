package com.github.wekite.features.items.chat.aichat

import android.app.Activity
import com.github.wekite.features.api.ui.WeCurrentConversationApi
import com.github.wekite.utils.WeLogger
import java.lang.ref.WeakReference

/**
 * 当前聊天页的绑定（Activity + 会话 ID）。
 *
 * ⚠️ 会话 ID 取自**页面自己的 Intent**（`Chat_User`），不靠轮询「当前会话」——
 * 轮询在切换会话的瞬间会读到旧值，那时若用户点开关/写入设置就会**落到错误的聊天**上
 * （2026-09-28 用户指出「跟着会话走，会错位」）。页面 Intent 是这个页面自带的、不会错位。
 *
 * 由 [ChatPageLifecycle] 在 resume/pause 时维护。
 */
object ChatUi {
    private const val TAG = "AiChatUi"
    private var activityRef: WeakReference<Activity>? = null

    var talker: String? = null
        private set

    val activity: Activity?
        get() = activityRef?.get()?.takeIf { !it.isFinishing && !it.isDestroyed }

    fun bind(act: Activity) {
        val t = act.intent?.getStringExtra("Chat_User")?.takeIf { it.isNotBlank() }
            ?: WeCurrentConversationApi.value.takeIf { it.isNotBlank() }
        if (t == null) { unbind(); return }
        if (talker != null && talker != t) {
            WeLogger.i(TAG, "chat page changed: $talker -> $t")
            AnalysisDialog.close()
        }
        activityRef = WeakReference(act)
        talker = t
    }

    fun unbind() {
        activityRef = null
        talker = null
    }

    /** 输入框实测高度（弹窗贴它上方用）。 */
    fun footerHeightPx(): Int = WeCurrentConversationApi.chatFooter?.height ?: 0
}
