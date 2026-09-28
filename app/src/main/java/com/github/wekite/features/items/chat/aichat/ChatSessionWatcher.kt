package com.github.wekite.features.items.chat.aichat

import android.app.Activity
import android.os.Handler
import android.os.Looper
import com.github.wekite.features.api.ui.WeCurrentConversationApi
import com.github.wekite.utils.WeLogger
import java.lang.ref.WeakReference

/**
 * 聊天会话跟随器（v3.45 新增）。
 *
 * ⚠️ 为什么需要它（用户实测：「开关不能每个聊天单独控制，开一个全都开」）：
 * 旧实现只在 Activity `onResume` 后 300ms 读一次当前聊天。但微信**在聊天页内部切换会话
 * 不会重新 resume**（同一个 Activity 复用），于是「当前聊天」一直停在上一个会话上 ——
 * 开关显示的状态、以及点击写入的 key 全都落在那个旧会话上，看起来就是「所有聊天共用一个开关」。
 *
 * 做法：聊天页 resume 期间每 800ms 轻量轮询 [WeCurrentConversationApi.value]（该值由 WeKite
 * 既有 hook 在 ChatFooter.setUserName 时更新），一旦变化就重新同步开关与面板；pause 时停止。
 */
object ChatSessionWatcher {
    private const val TAG = "AiChatSession"
    private const val INTERVAL_MS = 800L

    private val main = Handler(Looper.getMainLooper())
    private var activityRef: WeakReference<Activity>? = null
    private var running = false
    private var lastTalker: String? = null
    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            val activity = activityRef?.get()
            if (activity == null || activity.isFinishing || activity.isDestroyed) { stop(); return }
            val current = WeCurrentConversationApi.value.takeIf { it.isNotBlank() }
            if (current != lastTalker) {
                WeLogger.i(TAG, "chat session changed: ${lastTalker ?: "-"} -> ${current ?: "-"}")
                lastTalker = current
                ChatHeaderToggle.sync(activity, current)
                SuggestionPanel.attach(activity, current)
            }
            main.postDelayed(this, INTERVAL_MS)
        }
    }

    fun start(activity: Activity) {
        activityRef = WeakReference(activity)
        if (running) return
        running = true
        lastTalker = null      // 强制下一次 tick 重新同步
        main.post(tick)
        WeLogger.i(TAG, "watcher started")
    }

    fun stop() {
        if (!running) return
        running = false
        main.removeCallbacks(tick)
        WeLogger.i(TAG, "watcher stopped")
    }

    /** 当前跟踪到的聊天（供日志/设置弹窗取用）。 */
    fun currentTalker(): String? = lastTalker
}
