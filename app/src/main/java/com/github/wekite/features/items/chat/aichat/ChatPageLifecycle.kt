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
                    if (!AiChatAssistant.isEnabled) { ChatHeaderStatus.remove(); return }
                    // 只 bind 聊天宿主：LauncherUI（主界面；Fragment 级切聊天不触发 Activity resume，
                    // 全靠会话事件）或带 Chat_User extra 的聊天 Activity。Splash/设置页混进来会
                    // 污染 ChatUi.activityRef，之后事件路径的 sync 拿错 decorView，findHeader
                    // 必然失败（2026-09-29 19:25 窗口徽标静默消失的成因之一）。
                    // ⚠️ 群/联系人设置页**也带 Chat_User extra**（要显示群成员），必须按类名显式
                    // 排除（2026-09-30 16:34/16:59 真机日志：act=ChatroomInfoUI 被 bind 后在其上
                    // findHeader 必败 → header not ready 重试链；返回聊天前徽标处于断挂状态）。
                    if (isNonChatHost(act)) return
                    val isChatHost = act is com.tencent.mm.ui.LauncherUI ||
                        act.intent?.hasExtra("Chat_User") == true
                    if (!isChatHost) return
                    ChatUi.installListener()
                    ChatUi.bind(act)
                } catch (e: Throwable) {
                    WeLogger.e(TAG, "onActivityResumed sync failed", e)
                }
            }

            override fun onActivityPaused(act: Activity) {
                // 只摘显示、**不清会话绑定**（切页瞬间清空会导致回来时状态/弹窗全没）
                if (act === ChatUi.activity) ChatHeaderStatus.remove()
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

    /**
     * 「带 Chat_User extra 但不是聊天页」的宿主：群/联系人设置页（它们要显示成员/资料，
     * intent 同样带 Chat_User）。bind 到它们之上 findHeader 必败。按**类名字符串**比对，
     * 不引用宿主类（v3.50 教训：类字面引用在类缺失时 NoClassDefFoundError 炸掉整个回调）。
     */
    private fun isNonChatHost(act: Activity): Boolean {
        val name = act.javaClass.name
        return NON_CHAT_HOSTS.any { name.endsWith(it) }
    }

    private val NON_CHAT_HOSTS = listOf(
        "chatroom.ui.ChatroomInfoUI",       // 群设置页
        "profile.ui.ContactInfoUI",         // 联系人资料页
        "ui.chatting.ChattingInfoUI",       // 单聊聊天详情页（用户宿主可能不存在，防御性排除）
    )
}
