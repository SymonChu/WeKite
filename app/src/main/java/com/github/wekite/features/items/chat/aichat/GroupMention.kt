package com.github.wekite.features.items.chat.aichat

import com.github.wekite.features.api.core.WeApi
import com.github.wekite.features.api.core.WeDatabaseApi
import com.github.wekite.utils.WeLogger

/**
 * 群聊「是否在跟我说话」判断。
 *
 * 多信号，任一命中即算被点名（按可靠性排序）：
 * 1. `msgsource` 列里列出被 @ 的 wxid（微信自己记的名单，最可靠；老版本无此列则跳过）
 * 2. 正文含 `@我的群昵称`（群昵称取该群内我的显示名，不是全局昵称）
 * 3. 正文含 `@所有人`
 *
 * 判断失败（拿不到昵称、无 msgsource）时返回 false —— 群里**宁可漏回也不乱插话**。
 */
object GroupMention {
    private const val TAG = "AiGroupMention"
    private val MSG_SOURCE_COLUMNS = arrayOf("msgsource", "msgSource")

    fun isAddressedToMe(groupId: String, msgId: Long, body: String): Boolean {
        val self = runCatching { WeApi.selfWxId }.getOrDefault("")

        // 1) msgsource：<atuserlist><![CDATA[wxid_xxx,wxid_yyy]]></atuserlist>
        if (self.isNotBlank() && msgId > 0) {
            for (col in MSG_SOURCE_COLUMNS) {
                val hit = runCatching {
                    WeDatabaseApi.rawQuery(
                        "SELECT $col FROM message WHERE msgId = ? LIMIT 1",
                        arrayOf<Any>(msgId)
                    ).use { c ->
                        if (c.moveToFirst()) {
                            val src = c.getString(0).orEmpty()
                            src.isNotBlank() && src.contains(self)
                        } else false
                    }
                }.getOrElse { false }   // 该列不存在时忽略
                if (hit) {
                    WeLogger.i(TAG, "addressed to me (msgsource) group=$groupId msgId=$msgId")
                    return true
                }
            }
        }

        // 2) 正文 @我的群昵称（该群内显示名）
        val myNick = runCatching {
            if (self.isBlank()) "" else WeDatabaseApi.getGroupMemberDisplayName(groupId, self)
        }.getOrDefault("")
        if (myNick.isNotBlank() && body.contains("@$myNick")) {
            WeLogger.i(TAG, "addressed to me (nickname=$myNick) group=$groupId msgId=$msgId")
            return true
        }

        // 3) @所有人
        if (body.contains("@所有人") || body.contains("@All")) {
            WeLogger.i(TAG, "addressed to all group=$groupId msgId=$msgId")
            return true
        }

        WeLogger.i(TAG, "not addressed to me group=$groupId msgId=$msgId")
        return false
    }
}
