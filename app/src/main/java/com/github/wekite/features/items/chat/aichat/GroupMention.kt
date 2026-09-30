package com.github.wekite.features.items.chat.aichat

import com.github.wekite.features.api.core.WeApi
import com.github.wekite.features.api.core.WeDatabaseApi
import com.github.wekite.features.api.core.WeMessageApi
import com.github.wekite.features.api.core.models.MessageInfo
import com.github.wekite.utils.WeLogger

/**
 * 群聊「是否在跟我说话」判断。
 *
 * 判据顺序（2026-09-30 重做）：
 * 1. **微信自己记的 at 名单**（最可靠）：消息对象 `lvbuffer` 里的
 *    `<msgsource><atuserlist>wxid_a,wxid_b</atuserlist></msgsource>`，
 *    经 [MessageInfo.isAtMe] / [MessageInfo.isNotifyAll] 判断；
 *    与「消息时间打 @我 标签」（MessageTimeEnhancements）同一数据源。
 *    ⚠️ 旧实现是 `SELECT msgsource FROM message`：实测 962 行引擎日志 **0 命中**
 *    （该列在那个时机读不到值），已废弃。
 * 2. 正文含 `@我的群昵称`（群昵称取该群内我的显示名）
 * 3. 正文含 `@我的微信昵称` —— **没设群昵称时微信 @ 用的就是昵称**。旧实现只认群昵称，
 *    而 [WeDatabaseApi.getGroupMemberDisplayName] 在「未设置群昵称」时返回空串，
 *    整条判据被跳过 ⇒ 没设群昵称的群里 @ 我从不触发。这是本次修复的根因。
 * 4. 正文含 `@所有人`
 *
 * 判据全落空时返回 false —— 群里**宁可漏回也不乱插话**。
 */
object GroupMention {
    private const val TAG = "AiGroupMention"

    /** 取消息对象所需的最小列集（与 AntiMessageRecall 同口径，含 lvbuffer）。 */
    private const val MSG_COLUMNS = "type,content,talker,createTime,lvbuffer,msgId,msgSvrId,isSend"

    /**
     * 主判据：直接查微信的 at 名单（[MessageInfo.isAtMe]）。
     * 调用方在 DB 插入回调里拿得到 `ContentValues` 时走这条，省一次查询。
     */
    fun isAddressedToMe(msgInfo: MessageInfo, groupId: String): Boolean = try {
        if (msgInfo.isAtMe) {
            WeLogger.i(TAG, "addressed to me (atuserlist) group=$groupId")
            true
        } else if (msgInfo.isNotifyAll || msgInfo.isAnnounceAll) {
            WeLogger.i(TAG, "addressed to all (atuserlist) group=$groupId")
            true
        } else {
            false
        }
    } catch (e: Throwable) {
        // lvbuffer 解不出 at 名单时不能把消息判死，交给文本兜底
        WeLogger.e(TAG, "read at-userlist failed group=$groupId", e)
        false
    }

    /**
     * 兜底判据（手头只有 msgId 的场合，如自动回复发送前的复核）：
     * 先按 msgId 取消息对象走 [isAddressedToMe]，再退到正文文本匹配。
     */
    fun isAddressedToMe(groupId: String, msgId: Long, body: String): Boolean {
        val self = runCatching { WeApi.selfWxId }.getOrDefault("")
        if (self.isBlank()) {
            WeLogger.w(TAG, "selfWxId blank, cannot judge mention group=$groupId msgId=$msgId")
            return false
        }

        if (msgId > 0) {
            val mi = messageInfoByMsgId(msgId)
            if (mi != null && isAddressedToMe(mi, groupId)) return true
        }

        val names = nameCandidates(groupId, self)
        for (name in names) {
            if (body.contains("@$name")) {
                WeLogger.i(TAG, "addressed to me (nickname=$name) group=$groupId msgId=$msgId")
                return true
            }
        }

        if (body.contains("@所有人") || body.contains("@All")) {
            WeLogger.i(TAG, "addressed to all group=$groupId msgId=$msgId")
            return true
        }

        // 诊断（I 级）：排查「@ 我不触发」就看这行 —— 候选名 + 正文开头足够定案
        WeLogger.i(
            TAG,
            "not addressed to me group=$groupId msgId=$msgId names=$names bodyHead=${body.take(24)}",
        )
        return false
    }

    /** 群里对方 @ 我时正文可能用的名字：群昵称 → 微信昵称（未设群昵称时为空，自动跳过）。 */
    fun nameCandidates(groupId: String, self: String): List<String> = buildList {
        add(runCatching { WeDatabaseApi.getGroupMemberDisplayName(groupId, self) }.getOrDefault(""))
        add(runCatching { WeDatabaseApi.getDisplayName(self) }.getOrDefault(""))
    }.map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    /** 按 msgId 取消息对象（含 lvbuffer，能解出 at 名单）；取不到返回 null。 */
    private fun messageInfoByMsgId(msgId: Long): MessageInfo? = try {
        WeDatabaseApi.rawQuery(
            "SELECT $MSG_COLUMNS FROM message WHERE msgId = ? LIMIT 1",
            arrayOf<Any>(msgId),
        ).use { c ->
            if (c.moveToFirst()) {
                MessageInfo(WeMessageApi.convertMsgInfoInstanceFromCursor(c))
            } else null
        }
    } catch (e: Throwable) {
        WeLogger.e(TAG, "messageInfoByMsgId failed msgId=$msgId", e)
        null
    }
}
