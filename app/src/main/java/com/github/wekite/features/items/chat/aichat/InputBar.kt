package com.github.wekite.features.items.chat.aichat

import android.widget.EditText
import com.github.wekite.features.api.core.WeMessageApi
import com.github.wekite.features.api.ui.WeCurrentConversationApi
import com.github.wekite.utils.WeLogger
import dev.ujhhgtg.reflekt.reflekt

/**
 * 聊天输入栏读写：填入建议（用户可改后自己发）与直接发送。
 *
 * 定位 EditText 复用 [com.github.wekite.features.api.ui.WeChatInputBarApi] 的判据：
 * ChatFooter 里**类型是接口且该接口声明了 addTextChangedListener** 的非空字段即真正的输入框
 * （按字段名/MMEditText 类型找会命中语音转文字弹窗里的那个，实战踩过）。
 */
object InputBar {
    private const val TAG = "AiInputBar"

    /** 把文本填进当前聊天输入框；返回是否成功。 */
    fun fill(text: String): Boolean {
        val footer = WeCurrentConversationApi.chatFooter
        if (footer == null) {
            WeLogger.w(TAG, "fill failed: no chat footer")
            return false
        }
        val edit = editTextOf(footer) ?: run {
            WeLogger.w(TAG, "fill failed: input field not found")
            return false
        }
        return try {
            edit.setText(text)
            edit.setSelection(edit.text?.length ?: 0)
            true
        } catch (e: Exception) {
            WeLogger.e(TAG, "fill failed", e)
            false
        }
    }

    /** 直接发送（走微信原声发送队列）。 */
    fun send(talker: String, text: String): Boolean {
        if (talker.isBlank() || text.isBlank()) return false
        return try {
            WeMessageApi.sendText(talker, text)
        } catch (e: Exception) {
            WeLogger.e(TAG, "send failed", e)
            false
        }
    }

    private fun editTextOf(footer: Any): EditText? = try {
        val field = footer.reflekt().firstField {
            type { clazz ->
                clazz.isInterface && clazz.declaredMethods.any { it.name == "addTextChangedListener" }
            }
        }.get()
        field as? EditText
    } catch (e: Exception) {
        WeLogger.w(TAG, "editTextOf failed: ${e.javaClass.simpleName}")
        null
    }
}
