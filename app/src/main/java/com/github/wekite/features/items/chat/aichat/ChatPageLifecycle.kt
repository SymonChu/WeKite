package com.github.wekite.features.items.chat.aichat

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.github.wekite.features.api.ui.WeCurrentConversationApi
import com.github.wekite.utils.WeLogger
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 聊天页生命周期监听。
 *
 * 每次 resume：把当前聊天（会话 ID 取自**页面 Intent** 的 Chat_User，见 [ChatUi]）绑定好、
 * 刷新右上角「AI 状态」显示、必要时收起分析弹窗。
 * ⚠️ 不再轮询「当前会话」——切换会话瞬间读到旧值会导致开关/设置写错聊天（用户实测指出）。
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
                    if (!AiChatAssistant.isEnabled) { ChatHeaderStatus.remove(); ChatUi.unbind(); return }
                    ChatUi.bind(act)
                    ChatHeaderStatus.sync(act, ChatUi.talker)
                } catch (e: Throwable) {
                    WeLogger.e(TAG, "onActivityResumed sync failed", e)
                }
            }

            override fun onActivityPaused(act: Activity) {
                if (act === ChatUi.activity) { ChatHeaderStatus.remove(); ChatUi.unbind() }
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
