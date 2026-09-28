package com.github.wekite.features.items.chat.aichat

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.github.wekite.features.api.ui.WeCurrentConversationApi
import com.github.wekite.utils.WeLogger
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 聊天页生命周期监听：每次 Activity resume 时刷新右上角「AI」开关
 * （在聊天页 → toggle 绑定当前 talker；不在聊天页 → 摘除）。
 *
 * 判定「是聊天页」用 [WeCurrentConversationApi.value] 非空（ChatFooter.setUserName 已被 WeKite hook）。
 */
object ChatPageLifecycle {
    private const val TAG = "AiChatLifecycle"
    private val registered = AtomicBoolean(false)

    fun install(activity: Activity) {
        if (!registered.compareAndSet(false, true)) return
        val app = activity.application ?: return
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(act: Activity) {
                try {
                    if (!AiChatAssistant.isEnabled) {
                        ChatHeaderToggle.remove(); SuggestionPanel.detach(); ChatSessionWatcher.stop(); return
                    }
                    // 聊天页内部切换会话不会重新 resume ⇒ 靠 watcher 持续跟随当前聊天
                    ChatSessionWatcher.start(act)
                } catch (e: Throwable) {
                    WeLogger.e(TAG, "onActivityResumed sync failed", e)
                }
            }

            override fun onActivityPaused(act: Activity) {
                ChatSessionWatcher.stop()
                ChatHeaderToggle.remove()
                SuggestionPanel.detach()
            }

            override fun onActivityDestroyed(act: Activity) {
                try { app.unregisterActivityLifecycleCallbacks(this) } catch (_: Exception) {}
            }

            override fun onActivityCreated(act: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(act: Activity) {}
            override fun onActivityStopped(act: Activity) {}
            override fun onActivitySaveInstanceState(act: Activity, outState: Bundle) {}
        })
        WeLogger.i(TAG, "chat page lifecycle installed")
    }
}
