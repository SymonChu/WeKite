package com.github.wekite.features.items.chat.aichat

import android.app.Activity
import android.os.Handler
import android.os.Looper
import com.github.wekite.features.api.ui.WeCurrentConversationApi
import com.github.wekite.utils.WeLogger
import java.lang.ref.WeakReference

/**
 * 当前聊天页的绑定（Activity + 会话 ID）。
 *
 * ⚠️ 两条实测教训（2026-09-28 v3.46 真机日志）：
 * 1. **不能只信 Activity Intent 的 `Chat_User`**：本机微信的聊天页取不到该 extra ⇒ 绑成空
 *    ⇒ 头部状态与弹窗全部消失（日志 `AiAnalysisDialog: no activity bound`）。
 * 2. **不能靠轮询「当前会话」**：切会话瞬间会读到旧值，写入就会落到错误聊天。
 *
 * 现在的做法：**事件驱动 + 多源取值 + 重试**
 * - 首选 `WeCurrentConversationApi.value`（由 ChatFooter.setUserName hook 维护，v3.45 日志已证明其正确）；
 *   Intent 的 `Chat_User` 作为补充来源。
 * - 会话变化时由 [WeCurrentConversationApi.addListener] 立即回调 → 重新绑定（无轮询、无错位窗口）。
 * - 一次取不到**不清空**，按 400ms 重试若干次。
 */
object ChatUi {
    private const val TAG = "AiChatUi"
    private const val MAX_RETRY = 12
    private const val RETRY_MS = 400L

    private val main = Handler(Looper.getMainLooper())
    private var activityRef: WeakReference<Activity>? = null
    private var retries = 0
    private var listenerInstalled = false
    private val onConversationChanged: (String) -> Unit = { talker ->
        main.post {
            if (talker.isNotBlank() && talker != this.talker) {
                WeLogger.i(TAG, "conversation event: ${this.talker ?: "-"} -> $talker")
            }
            this.talker = talker
            // activity 为空时降 D（2026-09-30 定案：会话页滚动/小程序窗口常态产生 conversation event，
            // 非异常；实测一天 724 条 W。徽标消失类问题仍可开「详细日志」排查）
            activity?.let { ChatHeaderStatus.sync(it, talker) }
                ?: WeLogger.d(TAG, "no activity bound, skip header sync talker=$talker")
        }
    }

    var talker: String? = null
        private set

    val activity: Activity?
        get() = activityRef?.get()?.takeIf { !it.isFinishing && !it.isDestroyed }

    fun installListener() {
        if (listenerInstalled) return
        listenerInstalled = true
        WeCurrentConversationApi.addListener(onConversationChanged)
        WeLogger.i(TAG, "conversation listener installed")
    }

    fun uninstallListener() {
        if (!listenerInstalled) return
        listenerInstalled = false
        WeCurrentConversationApi.removeListener(onConversationChanged)
    }

    /** 聊天页 resume 时调用：绑定 Activity 并解析会话（取不到就重试，不清空）。 */
    fun bind(act: Activity) {
        activityRef = WeakReference(act)
        val t = resolve(act)
        if (t != null) {
            retries = 0
            if (talker != null && talker != t) {
                WeLogger.i(TAG, "chat page changed: $talker -> $t")
            }
            talker = t
            ChatHeaderStatus.sync(act, t)
            return
        }
        if (retries++ < MAX_RETRY) {
            WeLogger.i(TAG, "talker not ready, retry $retries/$MAX_RETRY")
            main.postDelayed({ activity?.let { bind(it) } }, RETRY_MS)
        } else {
            // 最终仍取不到：保留上次绑定（不清空），只记日志
            WeLogger.w(TAG, "talker unavailable after $MAX_RETRY retries; keeping ${talker ?: "-"}")
            ChatHeaderStatus.sync(act, talker)
        }
    }

    /** 会话来源：聊天页 Intent → WeKite 的当前会话值。 */
    private fun resolve(act: Activity): String? =
        act.intent?.getStringExtra("Chat_User")?.takeIf { it.isNotBlank() }
            ?: WeCurrentConversationApi.value.takeIf { it.isNotBlank() }
            ?: talker     // 兜底：沿用上次绑定（避免瞬时无值把状态清掉）

    fun unbind() {
        activityRef = null
        talker = null
        retries = 0
    }

    /** 输入框实测高度（弹窗贴它上方用）。 */
    fun footerHeightPx(): Int = WeCurrentConversationApi.chatFooter?.height ?: 0
}
