package com.github.wekite.features.items.notifications

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.clickable
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.github.wekite.constants.PackageNames
import com.github.wekite.dexkit.abc.IResolveDex
import com.github.wekite.dexkit.dsl.dexMethod
import com.github.wekite.features.api.core.WeApi
import com.github.wekite.features.api.core.WeConversationApi
import com.github.wekite.features.api.core.WeDatabaseApi
import com.github.wekite.features.api.core.WeMessageApi
import com.github.wekite.features.api.core.models.MessageInfo
import com.github.wekite.features.api.core.models.MessageType
import com.github.wekite.features.core.ClickableFeature
import com.github.wekite.features.core.Feature
import com.github.wekite.features.items.contacts.CustomLocalFriendAvatars
import com.github.wekite.preferences.WePrefs
import com.github.wekite.ui.content.AlertDialogContent
import com.github.wekite.ui.content.DefaultColumn
import com.github.wekite.ui.content.TextButton
import com.github.wekite.ui.content.dialogListItemColors
import com.github.wekite.ui.content.dialogSwitchColors
import com.github.wekite.ui.utils.showComposeDialog
import com.github.wekite.utils.HostInfo
import com.github.wekite.utils.OriginalMethodInvoker
import com.github.wekite.utils.TargetProcesses
import com.github.wekite.utils.WeLogger
import com.github.wekite.utils.android.getSystemService
import com.github.wekite.utils.captureOriginalMethod
import com.github.wekite.utils.collections.LruCache
import com.github.wekite.utils.fs.KnownPaths
import com.github.wekite.utils.fs.createDirsSafe
import com.github.wekite.utils.strings.isGroupChatWxId
import com.github.wekite.utils.strings.replaceEmojis
import com.github.wekite.utils.strings.replaceRichContent
import dev.ujhhgtg.reflekt.reflekt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
import kotlin.io.path.div
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.time.Duration.Companion.milliseconds

@Feature(
    name = "通知进化",
    categories = ["聊天"],
    description = "增强微信新消息通知：\n1. 通知栏直接回复消息\n2. 通知栏一键标为已读\n3. 原生对话样式 (含头像/图片/表情)\n4. 每个会话的消息历史合并展示"
)
object NotificationsEvolved : ClickableFeature(), IResolveDex {

    private const val TAG = "NotificationsEvolved"

    // com.tencent.mm.booter.notification.x.d(x, String talker, String content, int, int, boolean)
    // args[1] 是会话 wxid, args[2] 是原文。锚定在该方法独有的日志串上。
    val methodDealNotify by dexMethod {
        searchPackages("com.tencent.mm.booter.notification")
        matcher {
            paramCount = 6
            usingEqStrings("jacks dealNotify, talker:%s, msgtype:%d, tipsFlag:%d, isRevokeMesasge:%B content:%s")
        }
    }

    // 微信通知头像加载器。声明类是混淆类且跨版本变化,
    // 用 NotificationAvatar 实现里的稳定日志串 + wcf://avatar/ 协议串定位。
    private val methodLoadNotificationAvatar by dexMethod {
        matcher {
            paramTypes(
                "android.content.Context",
                "java.lang.String",
                "java.lang.String",
            )
            returnType = "android.graphics.Bitmap"
            usingEqStrings("MicroMsg.NotificationAvatar", "wcf://avatar/")
        }
    }

    // 进程模型: 上游把通知链路拆在 main+push 双进程, 模块随宿主一起挂, 因此双进程都要加载。
    override val shouldLoadInCurrentProcess: Boolean
        get() = TargetProcesses.currentType == TargetProcesses.PROC_MAIN ||
                TargetProcesses.currentType == TargetProcesses.PROC_PUSH

    private enum class ImageNotificationMode {
        DISABLED,
        WAIT_THUMBNAIL,
        WAIT_LARGE,
    }

    private enum class StickerNotificationMode {
        LOADED_ONLY,
        WAIT_FOR_LOAD,
    }

    private var imageNotificationModeName by WePrefs.prefOption(
        "notifications_evolved_image_mode",
        ImageNotificationMode.WAIT_THUMBNAIL.name,
    )
    private var imageNotificationMode: ImageNotificationMode
        get() = runCatching { ImageNotificationMode.valueOf(imageNotificationModeName) }
            .getOrDefault(ImageNotificationMode.WAIT_THUMBNAIL)
        set(value) {
            imageNotificationModeName = value.name
        }

    private var stickerNotificationModeName by WePrefs.prefOption(
        "notifications_evolved_sticker_mode",
        StickerNotificationMode.WAIT_FOR_LOAD.name,
    )
    private var stickerNotificationMode: StickerNotificationMode
        get() = runCatching { StickerNotificationMode.valueOf(stickerNotificationModeName) }
            .getOrDefault(StickerNotificationMode.WAIT_FOR_LOAD)
        set(value) {
            stickerNotificationModeName = value.name
        }

    private var clearHistoryAfterQuickReply by WePrefs.prefOption(
        "notifications_evolved_clear_history_after_quick_reply",
        true,
    )

    private data class MediaAttachment(
        val mimeType: String,
        val uri: Uri,
        val cachePath: Path,
    )

    private data class PendingMediaTask(
        val deadlineElapsedRealtime: Long,
        val deferred: Deferred<MediaAttachment?>,
    )

    private data class PendingMessage(
        val talker: String,
        val senderWxId: String,
        val rawContent: String,
        val text: String,
        val timestamp: Long,
        val messageId: Long,
        val capturedAt: Long,
        val mediaTask: PendingMediaTask?,
    )

    private data class HistoryEntry(
        val senderWxId: String?,
        val senderName: String?,
        val text: String,
        val timestamp: Long,
        val messageId: Long?,
        val mediaTask: PendingMediaTask? = null,
    )

    private data class NotificationContext(
        val talker: String,
        val rawContent: String,
        val generation: Any,
    )

    private data class NotificationKey(val tag: String?, val id: Int)

    private class PendingNotification(val talker: String, val key: NotificationKey) {
        lateinit var job: Job
    }

    private val pendingNotifications = HashMap<NotificationKey, PendingNotification>()
    private val notificationTalkers = HashMap<NotificationKey, String>()
    private val conversationGenerations = HashMap<String, Any>()

    private data class CachedAvatar(val icon: Icon, val expiresAt: Long, val customAvatarKey: String)

    private val stateLock = Any()
    // 回复/标已读后给该会话打一个「已被我们清干净」的时间戳, 窗内微信按旧状态重发的同一会话通知一律压制。
    //
    // 这个标记必须**跨进程**。微信的通知链路 (dealNotify -> NotificationManager.notify) 跑在 :push 的
    // CoreService 里, 而回复/标已读广播只在主进程处理; v3.89 把静默窗存在内存 HashMap 里, 于是 :push
    // 侧永远读到 0 —— 实测日志 (2026-10-22 08:03) 里主进程 arm 了窗, 30s 后 :push 照发不误,
    // 且一行压制日志都没有。MMKV 是 MULTI_PROCESS_MODE (WePrefs, 与「隐藏联系人通知抑制」同一个
    // 跨进程通道)。时间戳统一用 elapsedRealtime: 设备级单调时钟, 两个进程读数一致。
    private const val SUPPRESS_MARK_PREFIX = "notifications_evolved_suppress_at_"
    private const val SUPPRESS_REPOST_WINDOW_MS = 120_000L
    // 未读数读不到 (-1, :push 里数据库句柄可能未就绪) 时只压一个短窗 —— 宁可少压, 也不吞真消息。
    internal const val SUPPRESS_UNREAD_UNKNOWN_WINDOW_MS = 15_000L
    // 已取消通知的 key -> 会话 跨进程映射。v3.91: 微信还有一条**不走 dealNotify** 的重发路径
    // (2026-10-22 09:22 实测: 回复后 3 次核对 active=0, +5s watch 也说不在, 但用户 10s 后仍按到了
    // 同一 key 通知上的按钮, 且全程无 intercepted 日志) —— 只有 notify-hook 无上下文分支能拦它。
    private const val CANCELLED_KEY_PREFIX = "notifications_evolved_cancelled_key_"
    private const val CANCELLED_KEY_TTL_MS = 180_000L
    private val pendingMessages = HashMap<String, ArrayDeque<PendingMessage>>()
    private val avatarLock = Any()
    private val avatarCache = LruCache<String, CachedAvatar>(maxLimit = 64)
    private val notificationContext = ThreadLocal<NotificationContext?>()
    private lateinit var mediaScope: CoroutineScope
    private val notificationMediaDir by lazy {
        (KnownPaths.moduleCache / "notification-media").createDirsSafe()
    }
    private val notificationAvatarLoader by lazy {
        methodLoadNotificationAvatar.method.declaringClass.reflekt()
            .firstConstructor { parameters(Context::class) }
            .newInstance(HostInfo.application)
    }

    // 每个会话的消息历史, 每次通知更新时重建为 MessagingStyle。
    // 会话未读被清 (打开聊天/别处标已读) 或用户标已读时丢弃, 防止重放旧消息。有界防爆量。
    private val messageHistory = LinkedHashMap<String, ArrayDeque<HistoryEntry>>()
    private const val MAX_HISTORY = 7

    private const val ACTION_REPLY = "${PackageNames.WECHAT}.ACTION_WEKIT_REPLY"
    private const val ACTION_MARK_READ = "${PackageNames.WECHAT}.ACTION_WEKIT_MARK_READ"
    private const val ACTION_NOTIFICATION_OPENED = "${PackageNames.WECHAT}.ACTION_WEKIT_NOTIFICATION_OPENED"
    private const val ACTION_NOTIFICATION_DISMISSED = "${PackageNames.WECHAT}.ACTION_WEKIT_NOTIFICATION_DISMISSED"
    private const val MAX_PENDING_MESSAGES = 16
    private const val PENDING_MESSAGE_TTL_MILLIS = 30_000L
    private const val AVATAR_CACHE_TTL_MILLIS = 5 * 60 * 1000L
    private const val SHORT_MEDIA_WAIT_MILLIS = 1_500L
    private const val STICKER_MEDIA_WAIT_MILLIS = 3_000L
    private const val LARGE_IMAGE_WAIT_MILLIS = 5_000L
    private const val MEDIA_CACHE_MAX_AGE_MILLIS = 7 * 24 * 60 * 60 * 1000L

    // 微信每会话原 contentIntent, 清历史后点击通知仍要走它打开聊天。
    private val pendingContentIntents = HashMap<String, PendingIntent>()
    // 每个会话最近一次真正发布出去的通知。快捷回复失败时要原样重发一次:
    // 否则 Android 一直停在「发送中」状态, 通知栏的回复按钮会无限转圈 (v3.84 实测)。
    private val lastPostedNotifications = HashMap<String, Pair<NotificationKey, Notification>>()
    private var receiverRegistered = false

    // 微信的回复广播会同时唤醒 main 与 push 两个进程; push 进程没有完整的网络/存储内核
    // (v3.83 实测: sendText 抛 "mCoreNetwork not initialized!", markAsRead 在存储对象上 NPE)。
    // 且广播会把进程从冷启动拉起来、动作跑在内核就绪之前。因此:
    //  1. 只在主进程处理 (push 直接让路) —— 同时消除「同一条消息发两遍」的隐患;
    //  2. 发送/标已读一律在**主线程**执行。v3.84 实测: 同一调用放到后台线程会永久卡死
    //     (12:21/12:24 三次点击只有一行 sending text message, 之后既无异常也无重试日志,
    //      goAsync 永不 finish ⇒ 通知栏回复按钮一直转圈), 而主线程上 4ms 内就抛
    //     ka6.b "mCoreNetwork not initialized!"。微信这套内核访问器 (l51.i9.e -> pq0.i1.j/q)
    //     对调用线程敏感, 必须走主线程 (上游原版也是内联在 onReceive 里);
    //  3. 内核未就绪用主线程 postDelayed 重试 (5 次 × 1.5s ≈ 6s), 不 sleep、不占线程;
    //  4. 看门狗: 无论卡在哪一步, 9s 内一定 finish() 广播 —— goAsync 的系统窗口约 10s,
    //     超时会被系统判 ANR 且转圈不一定停, 所以兜底必须早于它。
    private const val KERNEL_RETRY_MAX = 5
    private const val KERNEL_RETRY_DELAY_MS = 1_500L
    private const val REPLY_WATCHDOG_MILLIS = 9_000L
    // 取消通知的补刀时点: 广播 finish 之后 RemoteInput 的收尾可能把通知刷回来, 需要再取消几次。
    private val CANCEL_RETRY_DELAYS_MS = longArrayOf(400L, 1_200L, 2_500L)
    // 回复成功后到真正 cancel 之间的等待: 先 finish() 放掉 RemoteInput 广播, 让系统把
    // "回复中"状态那一份刷完, 再取消 —— 否则我们的 cancel 会被系统的刷回盖掉 (v3.94)。
    // 取 150ms: 够系统走完广播收尾, 又远短于用户能察觉的延迟。
    private const val CANCEL_AFTER_FINISH_DELAY_MS = 150L
    // 更长一段的复查时点: 微信 (:push) 会在回复后几十秒才把同一条通知重新发出来。
    // 2026-10-22 10:09 实测 (v3.91): 幽灵出现在回复后 +20s~+33s —— 正好落在旧 +20/+35 两档的空档里,
    // 用户在空档里看到它"还挂着"。因此加密到 5s 一档直到 +40s, 再拉长到 2 分钟收尾;
    // 且该路径是唯一可靠的兜底: 幽灵出现时没有任何 notify 进我们的钩子 (零日志),
    // 说明其发布路径在 NotificationManager.notify 之外, 按标记拦截救不了, 只能靠复查补刀。
    // v3.95: 前 5 秒按 300ms 一档密查。
    //
    // 2026-10-22 22:25 真机日志 (v3.94) 把幽灵的存活时间量出来了: 回复后 +20s 复查还是 absent,
    // 1 秒后用户却按到了同一条通知的「回复」按钮, 而这段时间**没有任何 notify 钩子日志** ——
    // 说明把带 RemoteInput 的通知画回来的是**系统侧**, 不经过微信的 notify, 只能靠复查去拔。
    // 旧档位 5s 一档 ⇒ 幽灵可见最长 5 秒(实测 +21s 出现、+25s 才被拔)。改成 300ms 一档后
    // 可见时间压到亚秒; 后续档位保持到 120s 收尾。
    private val REPOST_WATCH_DELAYS_MS = longArrayOf(
        300L, 600L, 1_000L, 1_500L, 2_000L, 3_000L, 4_000L,
        5_000L, 7_500L, 10_000L, 15_000L, 20_000L, 25_000L, 30_000L, 40_000L,
        50_000L, 60_000L, 90_000L, 120_000L,
    )

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    /** 微信内核未初始化类异常 (宿主 ka6.a 抛 "mCoreNetwork not initialized!" 等)。 */
    private fun isKernelNotReady(error: Throwable): Boolean {
        var cur: Throwable? = error
        while (cur != null) {
            if (cur.message?.contains("not initialized") == true) return true
            cur = cur.cause
        }
        return false
    }

    /**
     * 在主线程按 [KERNEL_RETRY_DELAY_MS] 间隔重试 [action], 最多 [KERNEL_RETRY_MAX] 次,
     * 结束后回调最终结果。全程不阻塞线程 (重试用 postDelayed 排队), 且每次尝试都留日志,
     * 失败时能一眼看出是内核未就绪还是别的错误。
     */
    private fun retryOnMainThread(
        what: String,
        attempt: Int = 1,
        action: () -> Boolean,
        onDone: (Boolean) -> Unit,
    ) {
        mainHandler.post {
            // 这一整段都在主线程上, 任何逃逸的 Throwable 都会被系统当成未捕获异常处理,
            // 最坏会连应用进程一起带走。全部兜住, 只留日志。
            try {
                val started = SystemClock.elapsedRealtime()
                val ok = try {
                    action()
                } catch (error: Throwable) {
                    val elapsed = SystemClock.elapsedRealtime() - started
                    if (isKernelNotReady(error)) {
                        WeLogger.w(TAG, "$what attempt $attempt/$KERNEL_RETRY_MAX: kernel not ready (${error.message}) in ${elapsed}ms")
                        false
                    } else {
                        WeLogger.e(TAG, "$what attempt $attempt/$KERNEL_RETRY_MAX failed: ${error.javaClass.name}: ${error.message} in ${elapsed}ms", error)
                        runCatching { onDone(false) }
                        return@post
                    }
                }
                val elapsed = SystemClock.elapsedRealtime() - started
                if (ok) {
                    WeLogger.i(TAG, "$what succeeded on attempt $attempt/$KERNEL_RETRY_MAX (${elapsed}ms)")
                    onDone(true)
                } else if (attempt >= KERNEL_RETRY_MAX) {
                    WeLogger.e(TAG, "$what gave up after $KERNEL_RETRY_MAX attempts (last attempt ${elapsed}ms)")
                    onDone(false)
                } else {
                    if (elapsed > 0) WeLogger.i(TAG, "$what attempt $attempt/$KERNEL_RETRY_MAX returned false in ${elapsed}ms, retrying in ${KERNEL_RETRY_DELAY_MS}ms")
                    mainHandler.postDelayed({
                        retryOnMainThread(what, attempt + 1, action, onDone)
                    }, KERNEL_RETRY_DELAY_MS)
                }
            } catch (error: Throwable) {
                WeLogger.e(TAG, "$what crashed in completion handler at attempt $attempt", error)
            }
        }
    }

    private fun storageProbe(): Boolean =
        runCatching { WeDatabaseApi.rawQuery("SELECT 1 FROM rconversation LIMIT 1").use { it.moveToFirst() } }
            .onFailure { WeLogger.w(TAG, "storage probe failed: ${it.message}") }
            .getOrDefault(false)


    private val notificationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            try {
                handleNotificationAction(context, intent)
            } catch (error: Throwable) {
                // 广播回调里逃逸的异常会被系统当成未捕获异常, 直接带走进程 ——
                // 而进程一死, 通知上的「发送中/处理中」状态就再也回不来 (v3.85 实测:
                // 标已读只留下一行日志, 紧接着进程重启, 广播丢失)。这里全兜住并留痕。
                WeLogger.e(TAG, "notification action ${intent.action} crashed", error)
            }
        }

        private fun handleNotificationAction(context: Context, intent: Intent) {
            val targetWxId = intent.getStringExtra("extra_target_wxid") ?: return
            if (TargetProcesses.currentType != TargetProcesses.PROC_MAIN) {
                WeLogger.i(
                    TAG,
                    "ignore action=${intent.action} in non-main process (${TargetProcesses.currentName})"
                )
                return
            }
            WeLogger.i(TAG, "handling action=${intent.action} for $targetWxId")
            // 按钮 Intent 里带了通知自身的 (tag,id); 冷启动进程里内存映射为空时, 靠它才能取消通知。
            val notifyKey = if (intent.hasExtra("extra_notify_id")) {
                NotificationKey(intent.getStringExtra("extra_notify_tag"), intent.getIntExtra("extra_notify_id", 0))
            } else {
                null
            }
            val notificationManager =
                context.getSystemService<NotificationManager>()

            // v3.95 取证: 用户按下按钮的那一刻, 通知栏上到底还挂着什么。
            // 2026-10-22 22:25 (v3.94) 日志里 +20s 复查报 absent, 1 秒后用户却按到了同一条通知的
            // 「回复」按钮, 而全程没有任何 notify 钩子日志 —— 只有这一行能把"通知真的还在"和
            // "系统保留了 RemoteInput 面板"分开。同时打印按钮 Intent 自带的 key 与内存里登记的 key。
            dumpActiveNotifications(notificationManager, "action=${intent.action} $targetWxId received")
            WeLogger.i(
                TAG,
                "action=${intent.action} for $targetWxId: intentKey=" +
                    (notifyKey?.let { "${it.tag}/${it.id}" } ?: "none") +
                    ", mappedKeys=" + synchronized(stateLock) {
                        notificationTalkers.filterValues { it == targetWxId }.keys
                            .joinToString { "${it.tag}/${it.id}" }
                    },
            )

            when (intent.action) {
                ACTION_REPLY -> {
                    val results = RemoteInput.getResultsFromIntent(intent) ?: return
                    val replyContent = results.getCharSequence("key_reply_content")?.toString()

                    if (replyContent.isNullOrEmpty())
                        return

                    val preservedHistory = if (clearHistoryAfterQuickReply) {
                        emptyList()
                    } else {
                        synchronized(stateLock) {
                            messageHistory[targetWxId]?.toList().orEmpty()
                        }
                    }
                    val pending = goAsync()
                    val finished = java.util.concurrent.atomic.AtomicBoolean(false)
                    fun finishOnce() {
                        if (finished.compareAndSet(false, true)) {
                            runCatching { pending.finish() }
                        }
                    }
                    // 看门狗跑在独立守护线程上: 即使主线程被微信内部调用卡住, 也必须结束广播,
                    // 否则通知栏上的回复按钮会一直转圈 (v3.84 实测的症状)。
                    Thread {
                        SystemClock.sleep(REPLY_WATCHDOG_MILLIS)
                        if (!finished.get()) {
                            WeLogger.e(TAG, "quick reply watchdog fired after ${REPLY_WATCHDOG_MILLIS}ms for $targetWxId; releasing broadcast")
                            mainHandler.post { repostLastNotification(notificationManager, targetWxId) }
                            finishOnce()
                        }
                    }.apply { isDaemon = true }.start()

                    WeLogger.i(TAG, "quick replying '$replyContent' to $targetWxId")
                    retryOnMainThread(
                        what = "quick reply to $targetWxId",
                        action = { WeMessageApi.sendTextOrThrow(targetWxId, replyContent) },
                    ) { sent ->
                        try {
                            if (sent) {
                                // 回复成功后自动收尾: 标已读 -> 清会话状态 -> 关掉这条通知。
                                val markedRead = WeConversationApi.markAsRead(targetWxId)
                                WeLogger.i(TAG, "quick reply: mark-as-read=$markedRead for $targetWxId")
                                // 压制标记先打(只是 MMKV 写, 与取消时序无关), 让 finish 之后那段
                                // 空窗里微信若按旧状态重发, scheduleNotification 也能拦住。
                                armRepostSuppression(targetWxId)
                                clearConversationState(targetWxId)
                                removeContentIntent(targetWxId)

                                // markAsRead 会经下面的 hook 清掉历史。按配置恢复, 两种模式下都补上这条快捷回复。
                                appendHistory(targetWxId, preservedHistory)
                                appendHistory(
                                    targetWxId,
                                    HistoryEntry(
                                        senderWxId = WeApi.selfWxId,
                                        senderName = null,
                                        text = replyContent,
                                        timestamp = System.currentTimeMillis(),
                                        messageId = null,
                                    ),
                                )

                                // ⭐ v3.94 定案: 取消必须**排在 RemoteInput 广播收尾之后**。
                                //
                                // 「标为已读」按钮一直好用、只有回复不好用, 两者的差别不在取消逻辑
                                // (两条分支调的是同一个 cancelConversationNotifications), 而在回复走的是
                                // RemoteInput: 广播还挂起时系统把这条通知锁在「回复中」状态, 我们在 finish()
                                // 之前 cancel, 系统随后按"通知最后的状态"把它刷回通知栏 —— 于是用户看到
                                // 「回复了但通知还在」, 而且这次重发是**系统**发的, 不经过我们的 notify 钩子
                                // (v3.92 日志里"幽灵出现零日志"就是这么来的)。
                                //
                                // 所以先 finish() 放掉广播、让系统先把它那一份刷完, 再取消。用户那句
                                // 「点发送也相当于点已读」在时序上就是这个意思: 已读按钮没有挂起的 RemoteInput,
                                // 它的 cancel 天然是"最后一句话"。
                                finishOnce()
                                mainHandler.postDelayed({
                                    val cancelled = cancelConversationNotifications(
                                        notificationManager, targetWxId, notifyKey,
                                    )
                                    watchForRepost(notificationManager, targetWxId, cancelled)
                                }, CANCEL_AFTER_FINISH_DELAY_MS)
                            } else {
                                // 发送失败: 保留通知, 用户至少能看到还有未回的消息。
                                // 必须重发一次同一通知, 否则 Android 停在「发送中」态, 回复按钮会一直转圈。
                                repostLastNotification(notificationManager, targetWxId)
                                WeLogger.w(TAG, "quick reply to $targetWxId failed after retries; notification re-posted")
                            }
                        } finally {
                            finishOnce()
                        }
                    }
                }

                ACTION_MARK_READ -> {
                    val pending = goAsync()
                    val finished = java.util.concurrent.atomic.AtomicBoolean(false)
                    fun finishOnce() {
                        if (finished.compareAndSet(false, true)) {
                            runCatching { pending.finish() }
                        }
                    }
                    Thread {
                        SystemClock.sleep(REPLY_WATCHDOG_MILLIS)
                        if (!finished.get()) {
                            WeLogger.e(TAG, "mark-as-read watchdog fired after ${REPLY_WATCHDOG_MILLIS}ms for $targetWxId; releasing broadcast")
                            finishOnce()
                        }
                    }.apply { isDaemon = true }.start()

                    WeLogger.i(TAG, "marking chat as read for $targetWxId")
                    retryOnMainThread(
                        what = "mark-as-read storage probe for $targetWxId",
                        action = { storageProbe() },
                    ) { ready ->
                        try {
                            if (!ready) {
                                WeLogger.w(TAG, "mark-as-read storage not ready for $targetWxId; re-posting notification")
                                repostLastNotification(notificationManager, targetWxId)
                            } else if (WeConversationApi.markAsRead(targetWxId)) {
                                armRepostSuppression(targetWxId)
                                clearConversationState(targetWxId)
                                removeContentIntent(targetWxId)
                                val cancelled =
                                    cancelConversationNotifications(notificationManager, targetWxId, notifyKey)
                                watchForRepost(notificationManager, targetWxId, cancelled)
                                WeLogger.i(TAG, "mark-as-read done for $targetWxId; notification cancelled")
                            } else {
                                WeLogger.w(TAG, "mark-as-read failed for $targetWxId; re-posting notification")
                                repostLastNotification(notificationManager, targetWxId)
                            }
                        } finally {
                            finishOnce()
                        }
                    }
                }

                ACTION_NOTIFICATION_OPENED -> {
                    // 用户点了通知 — 先清历史, 再交给微信自己的打开意图。
                    // 同时打压制标记: 用户已经在看这个会话了, 微信随后按旧状态刷回来的通知不该出现
                    // (2026-10-22 08:03:30 点开聊天, 13s 后 :push 又把同一条通知发了一遍)。
                    clearConversationState(targetWxId)
                    armRepostSuppression(targetWxId)
                    val contentIntent = removeContentIntent(targetWxId) ?: return
                    try {
                        contentIntent.send()
                    } catch (e: PendingIntent.CanceledException) {
                        WeLogger.w(TAG, "original content intent was canceled for $targetWxId", e)
                    }
                }

                ACTION_NOTIFICATION_DISMISSED -> {
                    // 通知被划走 — 只清历史。
                    clearConversationState(targetWxId)
                    removeContentIntent(targetWxId)
                }
            }
        }
    }

    private val MESSAGE_REGEX = Regex("""^(\[\d+条])?(.+?)?: (.*)$""", RegexOption.DOT_MATCHES_ALL)

    private fun normalizeText(text: String): String = text
        .replaceRichContent()
        .replaceEmojis()

    private fun normalizeTimestamp(timestamp: Long): Long {
        if (timestamp <= 0L) return System.currentTimeMillis()
        return if (timestamp < 100_000_000_000L) timestamp * 1000L else timestamp
    }

    private fun cleanupNotificationMediaCache() {
        val cutoff = System.currentTimeMillis() - MEDIA_CACHE_MAX_AGE_MILLIS
        runCatching {
            notificationMediaDir.listDirectoryEntries().filter { path ->
                path.isRegularFile() && path.getLastModifiedTime().toMillis() < cutoff
            }.forEach(Path::deleteIfExists)
        }.onFailure { WeLogger.w(TAG, "failed to clean notification media cache", it) }
    }

    private fun toMediaAttachment(media: WeMessageApi.NotificationMediaFile): MediaAttachment {
        val uri = FileProvider.getUriForFile(
            HostInfo.application,
            "${PackageNames.WECHAT}.external.fileprovider",
            media.path.toFile(),
        )
        return MediaAttachment(media.mimeType, uri, media.path)
    }

    private fun launchMediaTask(
        waitMillis: Long,
        startImmediately: Boolean = true,
        prepare: (Long) -> WeMessageApi.NotificationMediaFile?,
    ): PendingMediaTask {
        val deadline = SystemClock.elapsedRealtime() + waitMillis
        val deferred = mediaScope.async(
            start = if (startImmediately) CoroutineStart.DEFAULT else CoroutineStart.LAZY,
        ) {
            runCatching {
                val media = prepare(deadline) ?: return@runCatching null
                if (SystemClock.elapsedRealtime() >= deadline) null else toMediaAttachment(media)
            }
                .onFailure { WeLogger.w(TAG, "failed to prepare notification media", it) }
                .getOrNull()
        }
        return PendingMediaTask(deadline, deferred)
    }

    private fun createMediaTask(message: MessageInfo): PendingMediaTask? {
        return when {
            message.typeCode == MessageType.IMAGE.code -> when (imageNotificationMode) {
                ImageNotificationMode.DISABLED -> null
                ImageNotificationMode.WAIT_THUMBNAIL -> launchMediaTask(
                    waitMillis = SHORT_MEDIA_WAIT_MILLIS,
                ) { deadline ->
                    WeMessageApi.materializeNotificationThumbnail(
                        message,
                        notificationMediaDir / "image-thumb-v2-${message.serverId}.media",
                        deadline,
                    )
                }

                ImageNotificationMode.WAIT_LARGE -> launchMediaTask(
                    waitMillis = LARGE_IMAGE_WAIT_MILLIS,
                    startImmediately = false,
                ) { deadline ->
                    WeMessageApi.materializeNotificationLargeImage(
                        message.serverId,
                        notificationMediaDir / "image-large-${message.serverId}.media",
                        deadline,
                    )
                }
            }

            message.type?.isSticker == true -> {
                val md5 = message.stickerMd5
                    ?.lowercase()
                    ?.takeIf { it.isNotEmpty() && it.all(Char::isLetterOrDigit) }
                    ?: return null
                val wait = stickerNotificationMode == StickerNotificationMode.WAIT_FOR_LOAD
                launchMediaTask(
                    waitMillis = STICKER_MEDIA_WAIT_MILLIS,
                ) { deadline ->
                    WeMessageApi.materializeNotificationSticker(
                        md5,
                        notificationMediaDir / "sticker-$md5.media",
                        deadline,
                        wait,
                    )
                }
            }

            else -> null
        }
    }

    private suspend fun awaitMedia(task: PendingMediaTask?): MediaAttachment? {
        if (task == null) return null
        val remaining = task.deadlineElapsedRealtime - SystemClock.elapsedRealtime()
        if (remaining <= 0L && !task.deferred.isCompleted) {
            task.deferred.cancel()
            return null
        }
        task.deferred.start()

        return try {
            val media = if (task.deferred.isCompleted) {
                task.deferred.await()
            } else if (remaining > 0L) {
                withTimeoutOrNull(remaining.milliseconds) { task.deferred.await() }
            } else {
                null
            }
            if (!task.deferred.isCompleted) task.deferred.cancel()
            media
        } catch (error: CancellationException) {
            // 被取代的发布者必须停止, 但媒体任务被取消只是退回纯文本。
            currentCoroutineContext().ensureActive()
            null
        } catch (error: Exception) {
            WeLogger.w(TAG, "failed while awaiting notification media", error)
            null
        }
    }

    private fun enqueueMessage(message: MessageInfo, insertedMessageId: Long) {
        if (message.isSelfSender) return

        // 真有入站消息 => 立刻解除「回复后压制」, 该会话的通知照常发。
        // 这是跨进程压制 (见 armRepostSuppression) 的放行信号: 入库发生在本进程, 通知也在本进程拼,
        // 顺序上一定先入库后 notify, 所以不会被自己那条压制标记误吞。
        clearRepostSuppression(message.talker)

        val senderWxId = message.sender
        if (senderWxId.isEmpty()) return

        val now = System.currentTimeMillis()
        val pending = PendingMessage(
            talker = message.talker,
            senderWxId = senderWxId,
            rawContent = message.content,
            text = normalizeText(message.humanReadableRepr),
            timestamp = normalizeTimestamp(message.createTime),
            messageId = insertedMessageId.takeIf { it > 0L } ?: message.id,
            capturedAt = now,
            mediaTask = createMediaTask(message),
        )

        synchronized(stateLock) {
            val queue = pendingMessages.getOrPut(pending.talker) { ArrayDeque() }
            while (queue.firstOrNull()?.let { now - it.capturedAt > PENDING_MESSAGE_TTL_MILLIS } == true) {
                queue.removeFirst().mediaTask?.deferred?.cancel()
            }
            if (pending.messageId != 0L && queue.any { it.messageId == pending.messageId }) return
            queue.addLast(pending)
            while (queue.size > MAX_PENDING_MESSAGES) {
                queue.removeFirst().mediaTask?.deferred?.cancel()
            }
        }
    }

    private fun consumePendingMessage(context: NotificationContext): PendingMessage? {
        val now = System.currentTimeMillis()
        return synchronized(stateLock) {
            val queue = pendingMessages[context.talker] ?: return@synchronized null
            while (queue.firstOrNull()?.let { now - it.capturedAt > PENDING_MESSAGE_TTL_MILLIS } == true) {
                queue.removeFirst().mediaTask?.deferred?.cancel()
            }
            if (queue.isEmpty()) {
                pendingMessages.remove(context.talker)
                return@synchronized null
            }

            val entries = queue.toList()
            val matchIndex = entries.indexOfFirst { it.rawContent == context.rawContent }
            if (matchIndex < 0) return@synchronized null

            val matched = entries[matchIndex]
            queue.clear()
            queue.addAll(entries.filterIndexed { index, _ -> index != matchIndex })
            if (queue.isEmpty()) pendingMessages.remove(context.talker)

            matched
        }
    }

    private fun discardPendingMessage(context: NotificationContext) {
        val discarded = synchronized(stateLock) {
            val queue = pendingMessages[context.talker] ?: return
            val index = queue.indexOfFirst { it.rawContent == context.rawContent }
            var removed: PendingMessage? = null
            if (index >= 0) {
                val entries = queue.toList()
                removed = entries[index]
                queue.clear()
                queue.addAll(entries.filterIndexed { entryIndex, _ -> entryIndex != index })
            }
            if (queue.isEmpty()) pendingMessages.remove(context.talker)
            removed
        }
        discarded?.mediaTask?.deferred?.cancel()
    }

    private fun PendingMessage.toHistoryEntry(): HistoryEntry = HistoryEntry(
        senderWxId = senderWxId,
        senderName = null,
        text = text,
        timestamp = timestamp,
        messageId = messageId.takeIf { it != 0L },
        mediaTask = mediaTask,
    )

    private fun appendHistory(talker: String, vararg entries: HistoryEntry) {
        synchronized(stateLock) {
            val history = messageHistory.getOrPut(talker) { ArrayDeque() }
            entries.forEach { entry ->
                if (entry.messageId != null && history.any { it.messageId == entry.messageId }) {
                    return@forEach
                }
                history.addLast(entry)
            }
            while (history.size > MAX_HISTORY) history.removeFirst().mediaTask?.deferred?.cancel()
        }
    }

    private fun appendHistory(talker: String, entries: List<HistoryEntry>) {
        appendHistory(talker, *entries.toTypedArray())
    }

    private fun clearConversationState(talker: String) {
        val pending = synchronized(stateLock) {
            conversationGenerations.remove(talker)
            val removed = pendingMessages.remove(talker)
            messageHistory.remove(talker)?.forEach { it.mediaTask?.deferred?.cancel() }
            invalidatePendingNotifications { it.talker == talker }
            removed
        }
        pending?.forEach { it.mediaTask?.deferred?.cancel() }
    }

    private fun setContentIntent(talker: String, intent: PendingIntent) {
        synchronized(stateLock) { pendingContentIntents[talker] = intent }
    }

    private fun removeContentIntent(talker: String): PendingIntent? {
        return synchronized(stateLock) { pendingContentIntents.remove(talker) }
    }

    private fun clearState() {
        synchronized(stateLock) {
            pendingMessages.values.forEach { queue ->
                queue.forEach { it.mediaTask?.deferred?.cancel() }
            }
            messageHistory.values.forEach { history ->
                history.forEach { it.mediaTask?.deferred?.cancel() }
            }
            invalidatePendingNotifications { true }
            notificationTalkers.clear()
            conversationGenerations.clear()
            pendingMessages.clear()
            messageHistory.clear()
            pendingContentIntents.clear()
            lastPostedNotifications.clear()
        }
        notificationContext.remove()
        synchronized(avatarLock) { avatarCache.clear() }
    }

    // 须持 stateLock 调用。作废身份还能兜住正在跑宿主/位图代码、
    // 暂时观察不到 job 取消的 worker。
    private fun invalidatePendingNotifications(predicate: (PendingNotification) -> Boolean) {
        val iterator = pendingNotifications.values.iterator()
        while (iterator.hasNext()) {
            val pending = iterator.next()
            if (predicate(pending)) {
                iterator.remove()
                pending.job.cancel()
            }
        }
    }

    /**
     * 重发该会话最近一次通知。快捷回复失败/看门狗兜底时调用:
     * Android 会把带 RemoteInput 的通知停留在「发送中」状态, 只有重新 notify 同一 (tag,id)
     * 才能把这状态清掉, 否则通知栏的回复按钮会无限转圈。
     */
    private fun repostLastNotification(manager: NotificationManager, talker: String) {
        val posted = synchronized(stateLock) { lastPostedNotifications[talker] } ?: run {
            WeLogger.w(TAG, "no cached notification to re-post for $talker")
            return
        }
        val (key, notif) = posted
        runCatching { manager.notify(key.tag, key.id, notif) }
            .onFailure { WeLogger.w(TAG, "failed to re-post notification for $talker", it) }
    }

    /**
     * 取消该会话的通知, 并在随后一段时间里反复核对/补刀。
     *
     * 为什么需要补: 快捷回复走 RemoteInput, 广播还挂起(pending.finish() 之前)时调用
     * manager.cancel, Android 会按"通知最后的状态"把界面再刷回来, 取消看起来没生效
     * (v3.87 实录: cancel 了 null/-1937897901 且 key 正确, 8 秒后通知仍在)。
     * 所以 finish 之后再补几次, 并用 getActiveNotifications 核对, 结果写进日志。
     */
    private fun cancelConversationNotifications(
        manager: NotificationManager,
        talker: String,
        explicitKey: NotificationKey? = null,
    ): List<NotificationKey> {
        val keys = synchronized(stateLock) {
            invalidatePendingNotifications { it.talker == talker }
            lastPostedNotifications.remove(talker)
            buildList {
                // 按钮 Intent 带过来的那条通知优先(冷启动时内存映射是空的, 只有它靠得住)。
                explicitKey?.let { add(it) }
                addAll(notificationTalkers.filterValues { it == talker }.keys)
            }.distinct()
        }
        keys.forEach { manager.cancel(it.tag, it.id) }
        // 记住"我们刚取消过这些 key"(跨进程): 微信有一条不走 dealNotify 的重发路径,
        // 只有 notify-hook 的无上下文分支能拦 (v3.91)。
        keys.forEach { rememberCancelledKey(it, talker) }
        WeLogger.i(
            TAG,
            "cancelConversationNotifications: $talker -> ${keys.size} key(s) " +
                keys.joinToString { "${it.tag}/${it.id}" } + " (proc=${TargetProcesses.currentName})",
        )
        dumpActiveNotifications(manager, "$talker right after cancel")
        if (keys.isEmpty()) return keys
        CANCEL_RETRY_DELAYS_MS.forEachIndexed { index, delay ->
            mainHandler.postDelayed({
                val alive = keys.filter { stillActive(manager, it) }
                if (alive.isEmpty()) {
                    WeLogger.i(TAG, "notification cleared for $talker (check #${index + 1} after ${delay}ms)")
                } else {
                    WeLogger.w(
                        TAG,
                        "notification still active for $talker after ${delay}ms: " +
                            alive.joinToString { "${it.tag}/${it.id}" } + "; cancelling again",
                    )
                    alive.forEach { manager.cancel(it.tag, it.id) }
                }
                // 每次核对都把微信当前还在通知栏的条目全量打出来: 只有它能证明
                // 「我们这条没了但别的还在」还是「同一条被宿主重新发出」。
                dumpActiveNotifications(manager, "$talker check #${index + 1}")
            }, delay)
        }
        return keys
    }

    /**
     * 回复/标已读之后继续盯一段时间: 微信 (:push) 可能在几秒到几十秒后把同一条通知重新发出来
     * (2026-10-22 实测 +9s/+30s, 那时 8s 静默窗已过期)。这里按 (tag,id) 复查 ——
     * 它一旦又出现、且该会话未读为 0, 立刻再取消一次; 未读 > 0 说明确实来了新消息, 保留通知。
     */
    private fun watchForRepost(
        manager: NotificationManager,
        talker: String,
        keys: List<NotificationKey>,
    ) {
        if (keys.isEmpty()) return
        REPOST_WATCH_DELAYS_MS.forEachIndexed { index, delay ->
            mainHandler.postDelayed({
                if (!::mediaScope.isInitialized) return@postDelayed
                mediaScope.launch {
                    val alive = keys.filter { stillActive(manager, it) }
                    if (alive.isEmpty()) {
                        // 密查档位 (300/600ms…) 不逐条落日志: 一次回复会打 19 行, 反而把真事件埋了。
                        // 前 1 秒 + 10 秒以后各留一行, 足够证明"没回来"。
                        if (delay <= 1_000L || delay >= 10_000L) {
                            WeLogger.i(TAG, "repost watch #${index + 1} (+${delay}ms): $talker still absent")
                        }
                        return@launch
                    }
                    // 取证: 幽灵的发布路径绕过我们的 notify 钩子(零日志), 所以"它长什么样、
                    // 系统认为它是何时发布的"是唯一线索 —— postTime + channel + group 一起打。
                    // v3.95: 抓到的那一刻把微信全部在栏条目也 dump 一份(同一毫秒的快照)。
                    try {
                        manager.activeNotifications
                            .filter { sbn -> keys.any { it.tag == sbn.tag && it.id == sbn.id } }
                            .forEach { sbn ->
                                WeLogger.w(
                                    TAG,
                                    "repost watch #${index + 1} (+${delay}ms): $talker GHOST key=${sbn.tag}/${sbn.id} " +
                                        "channel=${sbn.notification.channelId} group=${sbn.notification.group} " +
                                        "postTime=${java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
                                            .format(java.util.Date(sbn.postTime))} (posted " +
                                        "${(System.currentTimeMillis() - sbn.postTime) / 1000.0}s ago, " +
                                        "proc=${TargetProcesses.currentName})",
                                )
                            }
                    } catch (error: Throwable) {
                        WeLogger.w(TAG, "repost watch #${index + 1}: failed to read ghost detail", error)
                    }
                    dumpActiveNotifications(manager, "$talker ghost detected #${index + 1} (+${delay}ms)")
                    val unread = unreadCountOf(talker)
                    if (unread > 0) {
                        WeLogger.i(
                            TAG,
                            "repost watch #${index + 1} (+${delay}ms): $talker re-posted but unread=$unread; keeping it",
                        )
                        return@launch
                    }
                    withContext(Dispatchers.Main) { alive.forEach { manager.cancel(it.tag, it.id) } }
                    WeLogger.w(
                        TAG,
                        "repost watch #${index + 1} (+${delay}ms): $talker notification re-appeared " +
                            "(unread=$unread); cancelled again " + alive.joinToString { "${it.tag}/${it.id}" },
                    )
                }
            }, delay)
        }
    }

    /** 打印微信当前仍在通知栏的全部条目。 */
    private fun dumpActiveNotifications(manager: NotificationManager, note: String) {
        val alive = try {
            manager.activeNotifications
        } catch (error: Throwable) {
            WeLogger.w(TAG, "dumpActiveNotifications failed ($note)", error)
            return
        }
        val wechat = alive.filter { it.packageName == PackageNames.WECHAT }
        WeLogger.i(
            TAG,
            "active notifications ($note): total=${alive.size} wechat=${wechat.size} " +
                wechat.joinToString { "${it.tag}/${it.id}(group=${it.notification.group})" },
        )
    }

    /** 给该会话打「已被我们清干净」标记: 此后 [SUPPRESS_REPOST_WINDOW_MS] 内微信重发的陈旧通知会被压制。 */
    private fun armRepostSuppression(talker: String) {
        val now = SystemClock.elapsedRealtime()
        runCatching { WePrefs.putLong(SUPPRESS_MARK_PREFIX + talker, now) }
            .onFailure { WeLogger.w(TAG, "failed to persist suppression mark for $talker", it) }
        WeLogger.i(
            TAG,
            "suppressing stale re-posts for $talker (${SUPPRESS_REPOST_WINDOW_MS}ms, proc=${TargetProcesses.currentName})",
        )
    }

    /**
     * 读该会话的压制标记 (elapsedRealtime), 0 表示没有。
     *
     * 不做内存缓存: 两个进程各有一份缓存时会读到对方已经删掉的旧值, 而这正是 v3.89 栽的坑。
     * MMKV 是 mmap 读, 每次 notify 读一次的成本可以忽略。
     */
    private fun suppressedAtOf(talker: String): Long =
        runCatching { WePrefs.getLongOrDef(SUPPRESS_MARK_PREFIX + talker, 0L) }
            .onFailure { WeLogger.w(TAG, "failed to read suppression mark for $talker", it) }
            .getOrDefault(0L)

    /** 真有新消息到达时立刻解除压制 —— 新消息的通知本来就该出现。 */
    private fun clearRepostSuppression(talker: String) {
        if (suppressedAtOf(talker) == 0L) return
        runCatching { WePrefs.remove(SUPPRESS_MARK_PREFIX + talker) }
            .onFailure { WeLogger.w(TAG, "failed to clear suppression mark for $talker", it) }
        WeLogger.i(
            TAG,
            "suppression released for $talker (new incoming message, proc=${TargetProcesses.currentName})",
        )
    }

    /** 清掉过期的压制标记: 它们只是 120s 有效的一次性时间戳, 不清会在 MMKV 里无限累积。 */
    private fun pruneSuppressionMarks() {
        val now = SystemClock.elapsedRealtime()
        runCatching {
            WePrefs.default.all.keys
                .filter { it.startsWith(SUPPRESS_MARK_PREFIX) }
                .forEach { key ->
                    val at = WePrefs.getLongOrDef(key, 0L)
                    if (at == 0L || now - at > SUPPRESS_REPOST_WINDOW_MS) WePrefs.remove(key)
                }
        }.onFailure { WeLogger.w(TAG, "failed to prune suppression marks", it) }
    }

    // ---- 已取消通知 key 的跨进程映射 (v3.91) ----
    // key 形如 "tag/id" (tag 为 null 时是 "null/id"), 值 = elapsedRealtime,会话 的复合串 "ts|talker"。
    // 解析在读取端做, 写入端只做字符串拼接 —— 任何一端格式演进都不影响旧数据可判定性。

    private fun rememberCancelledKey(key: NotificationKey, talker: String) {
        runCatching {
            WePrefs.putString(
                CANCELLED_KEY_PREFIX + (key.tag ?: "null") + "/" + key.id,
                SystemClock.elapsedRealtime().toString() + "|" + talker,
            )
        }.onFailure { WeLogger.w(TAG, "failed to remember cancelled key ${key.tag}/${key.id}", it) }
        // 登记表是 TTL 型事实而不是一次性令牌(见 readCancelledKey), 会累积; 每次登记顺手清过期的。
        pruneCancelledKeys()
    }

    /**
     * 该 (tag,id) 是否是我们取消过的通知、且 TTL 内。返回 (talker, 取消于 elapsedRealtime)。
     *
     * 过期/缺映射返回 null。
     *
     * v3.94: **不再读后即清**。v3.91 的读后即清原本是为了「一次取消别吞掉该会话 3 分钟内的全部
     * 无上下文通知」, 但代价是这条登记只能拦一次 —— 幽灵是同一条通知反复重发, 拦掉第一次之后,
     * 第二次起就再也认不出它了 (v3.92 日志里"幽灵出现时零日志"有一半是这么来的: 钩子进了无上下文
     * 分支, 但登记已被前一次读掉, 于是既不拦也不打日志)。真正该防的误伤由**未读判据**负责:
     * 对方真发新消息 → unread>0 → 所有路径一律放行。所以这里改成"TTL 内一直有效",
     * 让 notify 三个重载 + 定时复查的判定完全一致、可重复。
     */
    private fun readCancelledKey(key: NotificationKey): Pair<String, Long>? {
        val stored = runCatching {
            WePrefs.getString(CANCELLED_KEY_PREFIX + (key.tag ?: "null") + "/" + key.id)
        }.onFailure { WeLogger.w(TAG, "failed to read cancelled key ${key.tag}/${key.id}", it) }
            .getOrNull() ?: return null
        val sep = stored.indexOf('|')
        if (sep <= 0 || sep >= stored.length - 1) return null
        val at = stored.substring(0, sep).toLongOrNull() ?: return null
        val talker = stored.substring(sep + 1)
        if (talker.isEmpty()) return null
        if (SystemClock.elapsedRealtime() - at > CANCELLED_KEY_TTL_MS) return null
        return talker to at
    }

    /**
     * 无上下文的 notify 重发（拿不到 dealNotify 的会话/原文）的统一判定。
     *
     * 命中条件: 该 (tag,id) 在「我们刚取消过的」登记表内且 TTL 未过 + 该会话未读为 0。
     * 三条通知路径（[NotificationManager.notify] 的三个重载）与通知使用权监听器、
     * 定时复查共用同一份登记表与同一个未读门槛, 所以判定结果完全一致、可重复。
     *
     * 返回 true = 调用方应把这次 notify 整个吞掉。
     */
    private fun shouldSuppressContextLessRepost(key: NotificationKey, via: String): Boolean {
        val cancelled = readCancelledKey(key) ?: return false
        val (talker, cancelledAt) = cancelled
        val unread = unreadCountOf(talker)
        val cancelledAgoMs = SystemClock.elapsedRealtime() - cancelledAt
        if (unread == 0 || (unread < 0 && cancelledAgoMs <= SUPPRESS_UNREAD_UNKNOWN_WINDOW_MS)) {
            synchronized(stateLock) {
                invalidatePendingNotifications { it.key == key }
                notificationTalkers.remove(key)
            }
            // 这条也可能已经发出去过 (系统/另一个进程发的), 顺手撤掉, 别留在通知栏上。
            runCatching {
                HostInfo.application.getSystemService<NotificationManager>().cancel(key.tag, key.id)
            }.onFailure { WeLogger.w(TAG, "failed to cancel suppressed notification ${key.tag}/${key.id}", it) }
            WeLogger.i(
                TAG,
                "suppressed context-less notification re-post for $talker key=${key.tag}/${key.id} " +
                    "(unread=$unread, cancelled ${cancelledAgoMs}ms ago, via=$via, " +
                    "proc=${TargetProcesses.currentName})",
            )
            return true
        }
        WeLogger.i(
            TAG,
            "context-less notify allowed for $talker key=${key.tag}/${key.id} " +
                "(unread=$unread, cancelled ${cancelledAgoMs}ms ago, via=$via, " +
                "proc=${TargetProcesses.currentName})",
        )
        return false
    }

    /** 清掉过期的已取消 key 映射, 防累积。 */
    private fun pruneCancelledKeys() {
        val now = SystemClock.elapsedRealtime()
        runCatching {
            WePrefs.default.all.keys
                .filter { it.startsWith(CANCELLED_KEY_PREFIX) }
                .forEach { key ->
                    val stored = WePrefs.getString(key) ?: return@forEach
                    val at = stored.substringBefore('|').toLongOrNull()
                    if (at == null || now - at > CANCELLED_KEY_TTL_MS) WePrefs.remove(key)
                }
        }.onFailure { WeLogger.w(TAG, "failed to prune cancelled keys", it) }
    }

    private fun unreadCountOf(talker: String): Int = runCatching {
        WeDatabaseApi.rawQuery("SELECT unReadCount, unReadMuteCount, atCount FROM rconversation WHERE username=?", arrayOf<Any>(talker))
            .use { cursor ->
                if (!cursor.moveToFirst()) return@use -1
                maxOf(cursor.getInt(0), cursor.getInt(1), cursor.getInt(2))
            }
    }.getOrDefault(-1)

    /** 该 (tag,id) 是否仍在通知栏里。取不到状态(API 过低)时按"不确定"处理, 返回 false 不打扰。 */
    private fun stillActive(manager: NotificationManager, key: NotificationKey): Boolean = try {
        manager.activeNotifications.any { it.tag == key.tag && it.id == key.id }
    } catch (error: Throwable) {
        WeLogger.w(TAG, "getActiveNotifications failed", error)
        false
    }

    private fun scheduleNotification(
        key: NotificationKey,
        notif: Notification,
        context: NotificationContext,
        originalNotify: OriginalMethodInvoker,
    ) {
        // 每次被拦下的发布都记全: 进程 + 距上次"清干净"多久 + 宿主认为的未读数。
        // v3.89 缺了这几项, "取消了又出现"只能靠猜 —— 事后才发现发帖的是 :push。
        val interceptedAt = SystemClock.elapsedRealtime()
        val suppressedAt = suppressedAtOf(context.talker)
        val clearedAgo = if (suppressedAt == 0L) -1L else interceptedAt - suppressedAt
        val unread = unreadCountOf(context.talker)
        WeLogger.i(
            TAG,
            "notify intercepted for ${context.talker} key=${key.tag}/${key.id} group=${notif.group} " +
                "proc=${TargetProcesses.currentName} clearedAgo=${clearedAgo}ms unread=$unread",
        )
        if (clearedAgo in 0..SUPPRESS_REPOST_WINDOW_MS) {
            // 未读为 0 => 微信按旧状态刷回来的陈旧通知; 未读读不到 => 只压短窗; 有未读 => 真有新消息, 照发。
            val stale = when {
                unread == 0 -> true
                unread < 0 -> clearedAgo <= SUPPRESS_UNREAD_UNKNOWN_WINDOW_MS
                else -> false
            }
            if (stale) {
                synchronized(stateLock) { notificationTalkers.remove(key) }
                // 这条也可能已经发出去过 (重启/另一个进程发的), 顺手撤掉, 别留在通知栏上。
                runCatching {
                    HostInfo.application.getSystemService<NotificationManager>().cancel(key.tag, key.id)
                }.onFailure { WeLogger.w(TAG, "failed to cancel suppressed notification ${key.tag}/${key.id}", it) }
                WeLogger.i(
                    TAG,
                    "suppressed stale notification re-post for ${context.talker} " +
                        "(unread=$unread, cleared ${clearedAgo}ms ago, proc=${TargetProcesses.currentName})",
                )
                return
            }
            WeLogger.i(
                TAG,
                "notification re-post allowed for ${context.talker} (unread=$unread, cleared ${clearedAgo}ms ago)",
            )
        }
        val notifTitle = notif.extras.getString(Notification.EXTRA_TITLE)
            ?: "未知对话（请向模块开发者报告错误）"
        val notifText = notif.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: "未知内容（请向模块开发者报告错误）"
        val match = MESSAGE_REGEX.find(notifText)
        val fallbackGroupSenderName = match?.groupValues?.get(2)?.takeIf { it.isNotEmpty() }
        val pending = PendingNotification(context.talker, key)
        synchronized(stateLock) {
            // 未读清零可能与 dealNotify 在宿主 worker 线程上竞争。
            if (conversationGenerations[context.talker] !== context.generation) return
            val capturedMessage = consumePendingMessage(context)
            val entry = capturedMessage?.toHistoryEntry() ?: HistoryEntry(
                senderWxId = context.talker.takeUnless { it.isGroupChatWxId },
                senderName = if (context.talker.isGroupChatWxId) {
                    fallbackGroupSenderName ?: notifTitle
                } else {
                    notifTitle
                },
                text = normalizeText(match?.groupValues?.get(3) ?: notifText),
                timestamp = System.currentTimeMillis(),
                messageId = null,
            )
            invalidatePendingNotifications { it.key == key || it.talker == context.talker }
            // 启动 worker 前先入历史, 替代性发布者能保住每条消息的到达顺序。
            // 取消一个发布者不能取消其替代者还需要用的媒体任务。
            appendHistory(context.talker, entry)
            val history = messageHistory[context.talker]!!.toList()
            notif.contentIntent?.let { setContentIntent(context.talker, it) }
            notificationTalkers[key] = context.talker
            pending.job = mediaScope.launch(start = CoroutineStart.LAZY) {
                try {
                    val enhanced = try {
                        enhanceNotification(
                            notif.clone(), context.talker, notifTitle,
                            fallbackGroupSenderName, capturedMessage?.senderWxId, history, key,
                        )
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        // 渲染失败也必须把原通知发出去。
                        WeLogger.e(TAG, "failed to enhance notification for ${context.talker}", error)
                        notif
                    }
                    // 只有最终那次 notify 跑在主线程。有效期检查与发布放在一起,
                    // 读/取消/替换不能复活旧任务。
                    withContext(Dispatchers.Main) {
                        synchronized(stateLock) {
                            if (pendingNotifications[key] === pending) {
                                originalNotify(arrayOf(key.tag, key.id, enhanced))
                                pendingNotifications.remove(key)
                                lastPostedNotifications[context.talker] = key to enhanced
                                // 记录每次真正发布, 便于判断"取消后又出现"是不是宿主重新发的。
                                WeLogger.i(
                                    TAG,
                                    "posted notification for ${context.talker} key=${key.tag}/${key.id} " +
                                        "proc=${TargetProcesses.currentName}",
                                )
                            }
                        }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    WeLogger.e(TAG, "failed to publish notification for ${context.talker}", error)
                } finally {
                    synchronized(stateLock) {
                        if (pendingNotifications[key] === pending) pendingNotifications.remove(key)
                    }
                }
            }
            pendingNotifications[key] = pending
        }
        pending.job.start()
    }

    private fun loadAvatarIcon(wxId: String): Icon? {
        if (wxId.isEmpty() || wxId == "system") return null

        val now = System.currentTimeMillis()
        val customAvatarKey = CustomLocalFriendAvatars.notificationAvatarCacheKey(wxId)
        synchronized(avatarLock) {
            avatarCache[wxId]?.let { cached ->
                if (cached.expiresAt > now && cached.customAvatarKey == customAvatarKey) return cached.icon
                avatarCache.remove(wxId)
            }
        }

        val icon = runCatching {
            val bitmap = methodLoadNotificationAvatar.method.invoke(
                notificationAvatarLoader,
                HostInfo.application,
                wxId,
                "",
            ) as? Bitmap? ?: return@runCatching null
            Icon.createWithBitmap(bitmap)
        }.onFailure {
            WeLogger.w(TAG, "failed to load notification avatar for $wxId", it)
        }.getOrNull() ?: return null

        synchronized(avatarLock) {
            avatarCache[wxId] = CachedAvatar(icon, now + AVATAR_CACHE_TTL_MILLIS, customAvatarKey)
        }
        return icon
    }

    private fun resolveDisplayName(
        talker: String,
        entry: HistoryEntry,
        conversationTitle: String,
        selfName: String,
        fallbackGroupSenderWxId: String?,
        fallbackGroupSenderName: String?,
    ): String {
        val senderWxId = entry.senderWxId ?: return entry.senderName ?: conversationTitle
        if (senderWxId == WeApi.selfWxId) return selfName
        if (!talker.isGroupChatWxId) return conversationTitle
        if (senderWxId == fallbackGroupSenderWxId && !fallbackGroupSenderName.isNullOrEmpty()) {
            return fallbackGroupSenderName
        }
        if (!entry.senderName.isNullOrEmpty()) return entry.senderName

        return runCatching {
            WeDatabaseApi.getGroupMemberDisplayName(talker, senderWxId)
        }.getOrDefault("").ifEmpty {
            runCatching { WeDatabaseApi.getDisplayName(senderWxId) }
                .getOrDefault(senderWxId)
        }.ifEmpty { senderWxId }
    }

    private fun buildPerson(name: String, wxId: String?): Person {
        return Person.Builder().setName(name).apply {
            if (!wxId.isNullOrEmpty()) {
                setKey(wxId)
                loadAvatarIcon(wxId)?.let(::setIcon)
            }
        }.build()
    }

    private suspend fun enhanceNotification(
        notif: Notification,
        convWxId: String,
        notifTitle: String,
        fallbackGroupSenderName: String?,
        currentSenderWxId: String?,
        history: List<HistoryEntry>,
        notifyKey: NotificationKey,
    ): Notification {
        // 通知一被接受就先启动懒加载的大图下载,
        // 别等头像/名字处理或下一条历史消息把时限耗光。
        history.forEach { entry ->
            entry.mediaTask?.let { task ->
                if (SystemClock.elapsedRealtime() < task.deadlineElapsedRealtime) task.deferred.start()
            }
        }
        val context = HostInfo.application
        val builder = Notification.Builder.recoverBuilder(context, notif)
        val selfName = "我"
        val selfPerson = buildPerson(selfName, WeApi.selfWxId)
        val messagingStyle = Notification.MessagingStyle(selfPerson)

        if (convWxId.isGroupChatWxId) {
            messagingStyle.isGroupConversation = true
            messagingStyle.conversationTitle = notifTitle
        }

        for (entry in history) {
            val person = if (entry.senderWxId == WeApi.selfWxId) {
                selfPerson
            } else {
                buildPerson(
                    resolveDisplayName(
                        talker = convWxId,
                        entry = entry,
                        conversationTitle = notifTitle,
                        selfName = selfName,
                        fallbackGroupSenderWxId = currentSenderWxId,
                        fallbackGroupSenderName = fallbackGroupSenderName,
                    ),
                    entry.senderWxId,
                )
            }
            val message = Notification.MessagingStyle.Message(
                entry.text,
                entry.timestamp,
                person,
            )
            awaitMedia(entry.mediaTask)?.let { media ->
                message.setData(media.mimeType, media.uri)
            }
            messagingStyle.addMessage(message)
        }

        builder.style = messagingStyle
        val conversationIcon = loadAvatarIcon(convWxId)
        if (conversationIcon != null) {
            builder.setLargeIcon(conversationIcon)
        } else if (!convWxId.isGroupChatWxId) {
            builder.setLargeIcon(null as Icon?)
        }

        // 2.5. 包一层微信的 contentIntent: 点通知先清历史, 再走微信自己的打开聊天流程。
        //      另挂 deleteIntent 接住划走消除。
        val originalContentIntent = notif.contentIntent
        if (originalContentIntent != null) {
            val openIntent = Intent(ACTION_NOTIFICATION_OPENED).apply {
                setPackage(PackageNames.WECHAT)
                putExtra("extra_target_wxid", convWxId)
            }
            builder.setContentIntent(
                PendingIntent.getBroadcast(
                    context, convWxId.hashCode(), openIntent,
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
        }
        val dismissIntent = Intent(ACTION_NOTIFICATION_DISMISSED).apply {
            setPackage(PackageNames.WECHAT)
            putExtra("extra_target_wxid", convWxId)
        }
        builder.setDeleteIntent(
            PendingIntent.getBroadcast(
                context, convWxId.hashCode(), dismissIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        )

        // 3. 快捷回复按钮
        val remoteInput = RemoteInput.Builder("key_reply_content")
            .setLabel("输入回复内容…")
            .build()

        val replyIntent = Intent(ACTION_REPLY).apply {
            setPackage(PackageNames.WECHAT)
            putExtra("extra_target_wxid", convWxId)
            // 把通知的 (tag,id) 一起带给接收器: 回复/标已读广播常把微信从冷启动拉起,
            // 那时内存里的 notificationTalkers 映射是空的, 单靠它取消不到通知,
            // 结果就是"回复成功但通知还挂着、会话仍未读"。
            putExtra("extra_notify_tag", notifyKey.tag)
            putExtra("extra_notify_id", notifyKey.id)
        }
        val replyPendingIntent = PendingIntent.getBroadcast(
            context, convWxId.hashCode(), replyIntent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val replyAction = Notification.Action.Builder(
            Icon.createWithResource(context, android.R.drawable.ic_menu_send),
            "回复", replyPendingIntent
        ).addRemoteInput(remoteInput).build()

        // 4. 标为已读按钮
        val readIntent = Intent(ACTION_MARK_READ).apply {
            setPackage(PackageNames.WECHAT)
            putExtra("extra_target_wxid", convWxId)
            putExtra("extra_notify_tag", notifyKey.tag)
            putExtra("extra_notify_id", notifyKey.id)
        }
        val readPendingIntent = PendingIntent.getBroadcast(
            context, convWxId.hashCode(), readIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val readAction = Notification.Action.Builder(
            Icon.createWithResource(context, android.R.drawable.ic_menu_view),
            "标为已读", readPendingIntent
        ).build()

        // 直接把按钮装到 builder 上
        builder.addAction(replyAction)
        builder.addAction(readAction)
        return builder.build()
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var imageMode by remember { mutableStateOf(imageNotificationMode) }
            var stickerMode by remember { mutableStateOf(stickerNotificationMode) }
            var clearHistory by remember { mutableStateOf(clearHistoryAfterQuickReply) }

            fun modeLabel(mode: ImageNotificationMode): String = when (mode) {
                ImageNotificationMode.DISABLED -> "不显示图片消息"
                ImageNotificationMode.WAIT_THUMBNAIL -> "等待缩略图加载"
                ImageNotificationMode.WAIT_LARGE -> "等待大图加载"
            }

            fun modeLabel(mode: StickerNotificationMode): String = when (mode) {
                StickerNotificationMode.LOADED_ONLY -> "仅显示已加载贴纸"
                StickerNotificationMode.WAIT_FOR_LOAD -> "等待贴纸加载"
            }

            AlertDialogContent(
                title = { Text("通知进化设置") },
                text = {
                    DefaultColumn {
                        ListItem(
                            colors = dialogListItemColors(),
                            modifier = Modifier.clickable {
                                val values = ImageNotificationMode.values()
                                val next = values[(values.indexOf(imageMode) + 1) % values.size]
                                imageMode = next
                                imageNotificationMode = next
                            },
                            headlineContent = { Text("图片消息") },
                            supportingContent = { Text(modeLabel(imageMode)) },
                        )
                        ListItem(
                            colors = dialogListItemColors(),
                            modifier = Modifier.clickable {
                                val values = StickerNotificationMode.values()
                                val next = values[(values.indexOf(stickerMode) + 1) % values.size]
                                stickerMode = next
                                stickerNotificationMode = next
                            },
                            headlineContent = { Text("贴纸表情消息") },
                            supportingContent = { Text(modeLabel(stickerMode)) },
                        )
                        ListItem(
                            colors = dialogListItemColors(),
                            modifier = Modifier.clickable {
                                clearHistory = !clearHistory
                                clearHistoryAfterQuickReply = clearHistory
                            },
                            trailingContent = {
                                androidx.compose.material3.Switch(
                                    checked = clearHistory,
                                    onCheckedChange = null,
                                    colors = dialogSwitchColors(),
                                )
                            },
                            headlineContent = { Text("快速回复后清除消息历史") },
                        )

                    }
                },
                dismissButton = {
                    TextButton(onDismiss) { Text("关闭") }
                },
            )
        }
    }

    override fun onEnable() {
        clearState()
        pruneSuppressionMarks()
        pruneCancelledKeys()
        mediaScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        mediaScope.launch { cleanupNotificationMediaCache() }

        WeMessageApi.methodMsgInfoStorageInsertMessage.hookAfter {
            val insertedMessageId = result as Long
            if (insertedMessageId < 0L) return@hookAfter
            enqueueMessage(MessageInfo(args[0]!!), insertedMessageId)
        }

        // x.d -> m0.a -> e0.b -> NotificationItem -> NotificationManager.notify()
        // 在宿主主线程上同步执行。把上下文捕获到 publish 为止;
        // 下面的 notify hook 拿快照即返回, 不会等媒体下载或渲染。
        methodDealNotify.hookBefore {
            val talker = args[1] as String
            synchronized(stateLock) {
                notificationContext.set(
                    NotificationContext(
                        talker = talker,
                        rawContent = args[2] as String,
                        generation = conversationGenerations.getOrPut(talker) { Any() },
                    )
                )
            }
        }
        methodDealNotify.hookAfter {
            notificationContext.get()?.let(::discardPendingMessage)
            notificationContext.remove()
        }

        // 微信在会话未读态被别处清掉 (打开聊天/别的设备已读等) 时会调
        // ConversationStorage.updateUnreadByTalker(talker)。丢掉该会话累积的历史,
        // 避免下一条通知重放旧消息。
        WeConversationApi.methodUpdateUnreadByTalker.hookBefore {
            clearConversationState(args[0] as String)
        }

        NotificationManager::class.reflekt()
            .firstMethod {
                name = "notify"
                parameters(String::class, Int::class, Notification::class)
            }
            .hookBefore {
                val key = NotificationKey(args[0] as String?, args[1] as Int)
                val notif = args[2] as Notification
                val notifyContext = notificationContext.get()
                if (notifyContext == null || notif.channelId != "message_channel_new_id") {
                    // v3.91: 无 dealNotify 上下文的重发 —— 2026-10-22 09:22 实测的最后一条漏网路径。
                    // 回复后 3 次核对 + 5s watch 都确认通知已消失, 但 10s 后用户仍按到了同一 key 通知上的
                    // 「标为已读」按钮, 且全程没有 intercepted 日志 ⇒ 这条 notify 没带上下文、v3.90 是
                    // 静默放行的。判定: 该 (tag,id) 是我们最近取消过的 & 该会话未读为 0 ⇒ 拦下。
                    // 有未读(新消息)或 key 不认识时照常放行, 不会吞真通知。
                    if (notifyContext == null) {
                        if (shouldSuppressContextLessRepost(key, "notify(tag,id,notif)")) {
                            result = null
                            return@hookBefore
                        }
                    }
                    if (notifyContext != null) {
                        // 带上了会话上下文却走了别的 channel —— 微信还有第二条通知通道,
                        // 我们的拦截拦不到它 (v3.89 曾出现"没日志却来了通知")。留痕便于定性。
                        WeLogger.i(
                            TAG,
                            "notify bypassed (context set but channel=${notif.channelId}) " +
                                "key=${key.tag}/${key.id} proc=${TargetProcesses.currentName}",
                        )
                    } else if (notif.channelId == "message_channel_new_id") {
                        // v3.95: 消灭"静默分支" —— 走消息通道、又没上下文、key 也不在登记表里,
                        // 以前这里一声不响 (排查时看起来就是"幽灵零日志")。消息通道的通知本来就少,
                        // 每条留一行足够定位。
                        WeLogger.i(
                            TAG,
                            "context-less notify (unregistered key) key=${key.tag}/${key.id} " +
                                "group=${notif.group} proc=${TargetProcesses.currentName}",
                        )
                    }
                    synchronized(stateLock) {
                        invalidatePendingNotifications { it.key == key }
                        notificationTalkers.remove(key)
                    }
                    return@hookBefore
                }
                notificationContext.remove()
                val originalNotify = captureOriginalMethod()
                scheduleNotification(key, notif.clone(), notifyContext, originalNotify)
                result = null
            }

        // ⭐ v3.94: 另外两个 notify 重载也要挂上。
        //
        // 通知栏上的 key 是 (tag,id), 而 Android 还有 `notify(int id, Notification)` 与
        // `notifyAsPackage(String, String, int, Notification)` 两条发布入口, 它们发出来的通知
        // 在通知栏上与上面那条**完全同 key**, 但我们的主钩子看不见 (v3.92 日志里"幽灵出现时零日志"
        // 就是这一类)。不挂它们, 幽灵只能等 5 秒一档的定时复查去拔, 或者靠通知使用权监听器。
        // 这里只做一件事: 命中"刚被我们取消过的 key + 未读为 0"就整个吞掉, 其余一律放行。
        NotificationManager::class.reflekt()
            .firstMethod {
                name = "notify"
                parameters(Int::class, Notification::class)
            }
            .hookBefore {
                val key = NotificationKey(null, args[0] as Int)
                if (shouldSuppressContextLessRepost(key, "notify(id,notif)")) {
                    result = null
                    return@hookBefore
                }
                synchronized(stateLock) {
                    invalidatePendingNotifications { it.key == key }
                    notificationTalkers.remove(key)
                }
            }
        NotificationManager::class.reflekt()
            .firstMethod {
                name = "notifyAsPackage"
                parameters(String::class, String::class, Int::class, Notification::class)
            }
            .hookBefore {
                val key = NotificationKey(args[1] as String?, args[2] as Int)
                if (shouldSuppressContextLessRepost(key, "notifyAsPackage")) {
                    result = null
                    return@hookBefore
                }
            }

        NotificationManager::class.reflekt()
            .firstMethod {
                name = "cancel"
                parameters(String::class, Int::class)
            }
            .hookBefore {
                val key = NotificationKey(args[0] as String?, args[1] as Int)
                synchronized(stateLock) {
                    invalidatePendingNotifications { it.key == key }
                    notificationTalkers.remove(key)
                }
            }
        NotificationManager::class.reflekt()
            .firstMethod {
                name = "cancelAll"
                parameters()
            }
            .hookBefore {
                synchronized(stateLock) {
                    invalidatePendingNotifications { true }
                    notificationTalkers.clear()
                }
            }

        val filter = IntentFilter().apply {
            addAction(ACTION_REPLY)
            addAction(ACTION_MARK_READ)
            addAction(ACTION_NOTIFICATION_OPENED)
            addAction(ACTION_NOTIFICATION_DISMISSED)
        }
        ContextCompat.registerReceiver(
            HostInfo.application, notificationReceiver, filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        receiverRegistered = true
    }

    override fun onDisable() {
        if (::mediaScope.isInitialized) mediaScope.cancel()
        clearState()
        if (!receiverRegistered) return
        receiverRegistered = false
        runCatching { HostInfo.application.unregisterReceiver(notificationReceiver) }
            .onFailure { WeLogger.w(TAG, "failed to unregister notification receiver", it) }
    }
}
