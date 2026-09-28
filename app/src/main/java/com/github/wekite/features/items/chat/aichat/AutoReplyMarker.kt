package com.github.wekite.features.items.chat.aichat

import com.github.wekite.preferences.WePrefs

/**
 * 自动回复消息的「仅自己可见」标记表。
 *
 * 全自动模式（AI 自己发送）成功发出的 msgSvrId 记在这里；
 * 聊天气泡渲染时命中即挂「AI」小徽标（视图层注入，对方完全不可见）。
 * 手动确认发送的建议回复**不记**入此表。
 *
 * 存储：MMKV StringSet（msgSvrId 十进制字符串）。超过上限时整体裁剪，
 * 因为早期标记大概率已滚出屏幕，无需精确 LRU。
 */
object AutoReplyMarker {
    private const val KEY = "ai_chat_auto_marks"
    private const val MAX = 500

    private var marks by WePrefs.prefOption(KEY, emptySet<String>())

    @Synchronized
    fun mark(svrId: Long) {
        if (svrId <= 0) return
        val next = (marks + svrId.toString())
        marks = if (next.size > MAX) next.drop(next.size - MAX).toSet() else next
    }

    fun isMarked(svrId: Long): Boolean =
        svrId > 0 && marks.contains(svrId.toString())

    @Synchronized
    fun unmark(svrId: Long) {
        marks = marks - svrId.toString()
    }
}
