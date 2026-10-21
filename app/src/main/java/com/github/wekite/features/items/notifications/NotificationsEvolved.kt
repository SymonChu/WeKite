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
    //  3. 内核未就绪用主线程 postDelayed 重试 (5 次 × 2s ≈ 8s), 不 sleep、不占线程;
    //  4. 看门狗: 无论卡在哪一步, 12s 内一定 finish() 广播, 转圈不会无限持续。
    private const val KERNEL_RETRY_MAX = 5
    private const val KERNEL_RETRY_DELAY_MS = 2_000L
    private const val REPLY_WATCHDOG_MILLIS = 12_000L

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
                    onDone(false)
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
        }
    }

    private fun storageProbe(): Boolean =
        runCatching { WeDatabaseApi.rawQuery("SELECT 1 FROM rconversation LIMIT 1").use { it.moveToFirst() } }
            .onFailure { WeLogger.w(TAG, "storage probe failed: ${it.message}") }
            .getOrDefault(false)


    private val notificationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val targetWxId = intent.getStringExtra("extra_target_wxid") ?: return
            if (TargetProcesses.currentType != TargetProcesses.PROC_MAIN) {
                WeLogger.i(
                    TAG,
                    "ignore action=${intent.action} in non-main process (${TargetProcesses.currentName})"
                )
                return
            }
            val notificationManager =
                context.getSystemService<NotificationManager>()

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
                                WeConversationApi.markAsRead(targetWxId)
                                cancelConversationNotifications(notificationManager, targetWxId)

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
                            if (ready) {
                                WeConversationApi.markAsRead(targetWxId)
                                clearConversationState(targetWxId)
                                removeContentIntent(targetWxId)
                                cancelConversationNotifications(notificationManager, targetWxId)
                            } else {
                                WeLogger.w(TAG, "mark-as-read storage still not ready for $targetWxId; notification kept")
                            }
                        } finally {
                            finishOnce()
                        }
                    }
                }

                ACTION_NOTIFICATION_OPENED -> {
                    // 用户点了通知 — 先清历史, 再交给微信自己的打开意图。
                    clearConversationState(targetWxId)
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

    private fun cancelConversationNotifications(manager: NotificationManager, talker: String) {
        val keys = synchronized(stateLock) {
            invalidatePendingNotifications { it.talker == talker }
            lastPostedNotifications.remove(talker)
            notificationTalkers.filterValues { it == talker }.keys.toList()
        }
        keys.forEach { manager.cancel(it.tag, it.id) }
    }

    private fun scheduleNotification(
        key: NotificationKey,
        notif: Notification,
        context: NotificationContext,
        originalNotify: OriginalMethodInvoker,
    ) {
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
                            fallbackGroupSenderName, capturedMessage?.senderWxId, history,
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
