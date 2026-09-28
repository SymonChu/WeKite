package com.github.wekite.features.items.chat.aichat

import com.github.wekite.preferences.WePrefs
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * 自动回复消息的「仅自己可见」标记表。
 *
 * ⚠️ 不能用 msgSvrId 做键（v3.44 实测踩坑）：**微信刚发出的消息在渲染时 msgSvrId 仍是 0**
 * （要等服务器回执才赋值），按它比对必然命中不了 → 徽标永不显示。
 * 改为：发送时按「聊天 + 正文」的哈希登记，渲染时用同一哈希比对，并限定时间窗
 * （超过 24h 的登记不再生效，避免很久以前发过同样的句子被误标）。
 *
 * 存储：MMKV StringSet，条目形如 `<hash>|<epochMs>`；超过上限整体裁剪。
 */
object AutoReplyMarker {
    private const val KEY = "ai_chat_auto_marks"
    private const val MAX = 200
    private const val WINDOW_MS = 24 * 60 * 60 * 1000L

    private var marks by WePrefs.prefOption(KEY, emptySet<String>())
    private val cache = ConcurrentHashMap<String, Long>()

    private fun hash(talker: String, content: String): String {
        val md = MessageDigest.getInstance("MD5")
        val bytes = md.digest("$talker\u0000${content.trim()}".toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }.take(16)
    }

    /** 自动发送成功后登记；[content] 为该条的正文（原样，比对时会 trim）。 */
    @Synchronized
    fun mark(talker: String, content: String) {
        if (talker.isBlank() || content.isBlank()) return
        val h = hash(talker, content)
        cache[h] = System.currentTimeMillis()
        val next = marks + "$h|${cache[h]}"
        marks = if (next.size > MAX) next.drop(next.size - MAX).toSet() else next
    }

    /** 该消息是否是我们全自动回复发出的（时间窗内）。 */
    fun isMarked(talker: String, content: String): Boolean {
        if (talker.isBlank() || content.isBlank()) return false
        val h = hash(talker, content)
        val now = System.currentTimeMillis()
        cache[h]?.let { return now - it <= WINDOW_MS }
        // 进程重启后从落盘条目里恢复
        val hit = marks.firstOrNull { it.startsWith("$h|") } ?: return false
        val ts = hit.substringAfter('|').toLongOrNull() ?: return false
        return now - ts <= WINDOW_MS
    }

    @Synchronized
    fun clear() {
        marks = emptySet()
        cache.clear()
    }
}
