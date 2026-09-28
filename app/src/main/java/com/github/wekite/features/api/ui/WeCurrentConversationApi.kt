package com.github.wekite.features.api.ui

import com.tencent.mm.pluginsdk.ui.chat.ChatFooter
import dev.ujhhgtg.reflekt.reflekt
import com.github.wekite.features.core.ApiFeature
import com.github.wekite.features.core.Feature
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList

@Feature(name = "当前聊天服务", categories = ["API"], description = "提供当前界面所在的聊天")
object WeCurrentConversationApi : ApiFeature() {

    var value: String = ""

    val chatFooter: ChatFooter?
        get() = chatFooterRef?.get()

    private var chatFooterRef: WeakReference<ChatFooter>? = null

    /** 会话变化监听（由 ChatFooter.setUserName 触发）——用它做事件驱动绑定，别轮询。 */
    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()

    fun addListener(listener: (String) -> Unit) {
        if (!listeners.contains(listener)) listeners.add(listener)
    }

    fun removeListener(listener: (String) -> Unit) {
        listeners.remove(listener)
    }

    override fun onEnable() {
        ChatFooter::class.reflekt()
            .firstMethod {
                name = "setUserName"
            }.hookAfter {
                chatFooterRef = WeakReference(thisObject as ChatFooter)
                val conv = args[0] as? String
                if (!conv.isNullOrEmpty()) {
                    value = conv
                    for (l in listeners) {
                        try { l(conv) } catch (_: Throwable) {}
                    }
                }
            }
    }
}
