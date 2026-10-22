package com.github.wekite.features.items.notifications

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.os.SystemClock
import com.github.wekite.preferences.WePrefs
import com.github.wekite.utils.TargetProcesses
import com.github.wekite.utils.WeLogger

/**
 * 回复/标已读后「幽灵通知」的立即拔除器 (v3.93)。
 *
 * 背景: 微信有一条不走 `NotificationManager.notify` 的幽灵重发路径, 模块的 notify 钩子
 * 看不见它 (零日志), v3.92 只能靠 5 秒一档的定时复查去"看到再拔"——幽灵最多活 5 秒。
 * 这个 [NotificationListenerService] 把"看到"变成**事件驱动**: 系统在通知出现的那一刻回调
 * [onNotificationPosted], 我们在同一毫秒级把它 cancel 掉, 幽灵几乎无存活时间。
 *
 * ## 只认被标记过的通知, 不做任何别的
 *
 * 判据与定时复查同源: 该 (tag,id) 是「回复/标已读/点开聊天后我们刚取消过的」
 * ([NotificationsEvolved.rememberCancelledKey] 写入的跨进程登记), 且该会话未读为 0
 * (有未读=真有新消息, 绝不碰)。三个条件缺一不可, 所以:
 * - 对用户日常的任何其他通知 (微信的、其他应用的) 都是零干预——回调里做三次 MMKV 读,
 *   不匹配就直接返回;
 * - 「通知使用权」这个系统权限本身能读到所有通知内容, 但本服务**只**用 key 匹配、
 *   **只**调 cancel, 不读取/存储/上报任何通知内容。
 *
 * ## 生命周期与降级
 *
 * - 需要用户在 系统设置→通知使用权 里手动授予; 未授权时服务不运行,
 *   一切退回 v3.92 的定时复查兜底, 功能完整只是幽灵最多多活 5 秒。
 * - 系统可能在低内存时解绑本服务 (onDestroy), 重新授权/重启微信后会自动重绑;
 *   轮询兜底始终在, 不存在"监听器死了就没人管"的窗口。
 * - LSPosed 环境下服务跑在宿主进程里, 进程模型上双进程都能被系统绑定;
 *   与定时复查一样只在主进程做真正的取消动作 (见 onStartCommand 前的进程判断)。
 *
 * ⚠️ Android 13+ 授权时系统会要求"允许通知使用权"的二次确认; 首次连接成功打一行日志, 便于用户确认已生效。
 */
class NotificationRepostListener : NotificationListenerService() {

    private val tag = "NotificationRepostListener"

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // 只关心微信包名; 其他应用的通知连键都不用查。
        if (sbn.packageName != "com.tencent.mm") return

        val key = sbn.tag ?: return
        val stored = runCatching {
            WePrefs.getString(NotificationsEvolved.CANCELLED_KEY_PREFIX + key + "/" + sbn.id)
        }.onFailure { WeLogger.w(tag, "failed to read cancelled key $key/${sbn.id}", it) }
            .getOrNull() ?: return

        val sep = stored.indexOf('|')
        if (sep <= 0 || sep >= stored.length - 1) return
        val cancelledAt = stored.substring(0, sep).toLongOrNull() ?: return
        val talker = stored.substring(sep + 1)
        if (talker.isEmpty()) return

        val cancelledAgoMs = android.os.SystemClock.elapsedRealtime() - cancelledAt
        // 过期的登记不再作为拔除依据 (与轮询的 TTL 语义一致)。
        if (cancelledAgoMs > NotificationsEvolved.CANCELLED_KEY_TTL_MS) return

        // 读后即清: 一个 key 只拔一次, 与 consumeCancelledKey 的语义相同。
        // 真有新消息时 scheduleNotification 正常路径会重新发布并覆盖。
        runCatching { WePrefs.remove(NotificationsEvolved.CANCELLED_KEY_PREFIX + key + "/" + sbn.id) }
            .onFailure { WeLogger.w(tag, "failed to clear cancelled key $key/${sbn.id}", it) }

        // 有未读 = 真有新消息, 通知本来就该在; 读不到 (数据库未就绪) 时只拔 15s 内的。
        val unread = NotificationsEvolved.unreadCountOfPublic(talker)
        val shouldKill = unread == 0 || (unread < 0 && cancelledAgoMs <= NotificationsEvolved.SUPPRESS_UNREAD_UNKNOWN_WINDOW_MS)
        if (!shouldKill) {
            WeLogger.i(
                tag,
                "listener saw ghost for $talker key=$key/${sbn.id} but unread=$unread; keeping it",
            )
            return
        }

        runCatching { cancelNotification(sbn.packageName, sbn.tag, sbn.id) }
            .onFailure { WeLogger.w(tag, "failed to cancel ghost $key/${sbn.id}", it) }
        WeLogger.i(
            tag,
            "listener killed ghost notification for $talker key=$key/${sbn.id} " +
                "(unread=$unread, cancelled ${cancelledAgoMs}ms ago, " +
                "posted ${(System.currentTimeMillis() - sbn.postTime) / 1000.0}s ago)",
        )
    }

    override fun onListenerConnected() {
        WeLogger.i(
            tag,
            "notification listener connected (proc=${TargetProcesses.currentName}) — " +
                "ghost notifications will now be killed the instant they reappear",
        )
    }
}
