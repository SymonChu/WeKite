package com.github.wekite.utils

import android.util.Log
import com.github.wekite.BuildConfig
import com.github.wekite.preferences.WePrefs
import com.github.wekite.utils.fs.KnownPaths
import com.github.wekite.utils.fs.createDirsSafe
import java.io.FileWriter
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.div
import kotlin.math.min

object WeLogger {

    private const val TAG = BuildConfig.TAG

    private const val CHUNK_SIZE = 4000
    private const val MAX_CHUNKS = 200
    private const val QUEUE_CAPACITY = 2048
    private const val RESERVED_IMPORTANT_CAPACITY = 128
    private const val BATCH_SIZE = 64
    private const val FLUSH_TIMEOUT_MILLIS = 3000L

    private val timestampFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    private val dateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    private sealed interface WriteTask {
        data class Record(
            val level: String,
            val tag: String?,
            val msg: String,
            val throwable: Throwable?,
            val timestamp: LocalDateTime,
        ) : WriteTask

        class Flush(val completed: CountDownLatch) : WriteTask

        /** Close the active writer and delete every run-log file; reports freed bytes via [result]. */
        class ClearAll(val completed: CountDownLatch, val result: AtomicLong) : WriteTask

        /** Delete run-log files older than the configured retention period on the writer thread. */
        class CleanOld(val completed: CountDownLatch) : WriteTask
    }

    /**
     * A bounded queue keeps logging fire-and-forget even if storage is temporarily slow. A
     * dropped-record counter is emitted by the writer once it catches up, so loss is visible.
     */
    private val writeQueue = ArrayBlockingQueue<WriteTask>(QUEUE_CAPACITY, true)
    private val droppedRecords = AtomicLong()
    private val writerThread = Thread(::runWriter, "WeKite-Logger").apply {
        isDaemon = true
        start()
    }

    private var writer: FileWriter? = null
    private var currentLogDate: LocalDate? = null

    // ========== File Logging Internals ==========

    private fun getOrRotateWriter(logDate: LocalDate): FileWriter? {
        if (writer != null && currentLogDate == logDate) return writer

        writer?.runCatching { close() }
        writer = null
        currentLogDate = null

        val logsDir = runCatching {
            (KnownPaths.moduleData / "logs").createDirsSafe()
        }.getOrNull() ?: return null

        // Clean up logs older than 3 days during rotation/initialization
        deleteOldLogs(logsDir)

        val logPath = logsDir / "wekite-${dateFmt.format(logDate)}.log"

        return runCatching {
            FileWriter(logPath.toFile(), true).also {
                writer = it
                currentLogDate = logDate
            }
        }.getOrNull()
    }

    private fun deleteOldLogs(logsDir: java.nio.file.Path) {
        runCatching {
            // 保留天数可由「自动清理日志」功能调节 (clean_logs_interval_ms, 默认 3 天)
            val retentionMs = WePrefs.getLongOrDef("clean_logs_interval_ms", 3 * 24 * 60 * 60 * 1000L)
            val retentionDays = (retentionMs / (24 * 60 * 60 * 1000L)).coerceAtLeast(1)
            val thresholdDate = LocalDate.now().minusDays(retentionDays)
            // 兼容旧前缀 wekit- (品牌统一前的残留文件), 新文件均为 wekite-
            val logFileRegex = Regex("""(?:wekit|wekite)-(\d{4}-\d{2}-\d{2})\.log""")

            logsDir.toFile().listFiles()?.forEach { file ->
                val match = logFileRegex.matchEntire(file.name)
                if (match != null) {
                    val dateStr = match.groupValues[1]
                    val fileDate = runCatching { LocalDate.parse(dateStr, dateFmt) }.getOrNull()

                    // If the log file date is older than the retention days, delete it
                    if (fileDate != null && fileDate.isBefore(thresholdDate)) {
                        if (file.delete()) {
                            Log.d(TAG, "deleted old log file: ${file.name}")
                        }
                    }
                }
            }
        }
    }

    /**
     * Deletes old run-log files using the logger's own writer thread. This prevents the automatic
     * cleaner from deleting a file while the asynchronous logger still has it open.
     */
    fun deleteOldLogs() {
        if (Thread.currentThread() === writerThread) {
            deleteOldLogs((KnownPaths.moduleData / "logs").createDirsSafe())
            return
        }

        val completed = CountDownLatch(1)
        val enqueued = try {
            writeQueue.offer(WriteTask.CleanOld(completed), FLUSH_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!enqueued) {
            Log.w(TAG, "timed out while enqueueing old-log cleanup")
            return
        }
        try {
            if (!completed.await(FLUSH_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "timed out while deleting old log files")
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /** Deletes every run-log file (both `wekit-` and `wekite-` prefixes) under [logsDir]. Returns freed bytes. */
    private fun deleteAllLogFiles(): Long {
        val dir = runCatching { (KnownPaths.moduleData / "logs").createDirsSafe() }.getOrNull() ?: return 0L
        // 兼容旧前缀 wekit-
        val logFileRegex = Regex("""(?:wekit|wekite)-\d{4}-\d{2}-\d{2}\.log""")
        var deletedBytes = 0L
        dir.toFile().listFiles()?.forEach { file ->
            if (file.isFile && logFileRegex.matches(file.name)) {
                deletedBytes += file.length()
                if (file.delete()) {
                    Log.d(TAG, "cleared log file: ${file.name}")
                }
            }
        }
        return deletedBytes
    }

    /**
     * Permanently clears all run-log files: flushes and closes the active writer (so no fd keeps
     * writing into an unlinked inode), then deletes every log file. The next log record will
     * re-create today's file. Returns the number of bytes freed. Blocking, like [flush].
     */
    fun clearAllLogs(): Long {
        if (Thread.currentThread() === writerThread) {
            writeDroppedNotice(LocalDateTime.now())
            flushWriter()
            writer?.runCatching { close() }
            writer = null
            currentLogDate = null
            return deleteAllLogFiles()
        }

        val completed = CountDownLatch(1)
        val result = AtomicLong(0L)
        val barrier = WriteTask.ClearAll(completed, result)
        val enqueued = try {
            writeQueue.offer(barrier, FLUSH_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!enqueued) {
            Log.w(TAG, "timed out while enqueueing log clear barrier")
            return 0L
        }

        val finished = try {
            completed.await(FLUSH_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!finished) {
            Log.w(TAG, "timed out while clearing log queue")
        }
        return result.get()
    }

    private fun runWriter() {
        val batch = ArrayList<WriteTask>(BATCH_SIZE)

        while (!Thread.currentThread().isInterrupted) {
            val first = try {
                writeQueue.take()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
            batch += first
            writeQueue.drainTo(batch, BATCH_SIZE - 1)

            var hasWrites = false
            batch.forEach { task ->
                when (task) {
                    is WriteTask.Record -> {
                        writeDroppedNotice(task.timestamp)
                        val written = writeRecord(task)
                        hasWrites = written || hasWrites
                        if (written && (task.level == "E" || task.level == "W" || task.level == "A")) {
                            flushWriter()
                            hasWrites = false
                        }
                    }

                    is WriteTask.Flush -> {
                        try {
                            writeDroppedNotice(LocalDateTime.now())
                            flushWriter()
                        } finally {
                            task.completed.countDown()
                        }
                        hasWrites = false
                    }

                    is WriteTask.ClearAll -> {
                        try {
                            writeDroppedNotice(LocalDateTime.now())
                            flushWriter()
                            // Close the writer first: deleting an open file would leave the fd
                            // pointing at an unlinked inode, so subsequent writes keep going into
                            // an invisible file and the freed space is never released until the
                            // process exits. Closing resets the rotation state (writer + date).
                            writer?.runCatching { close() }
                            writer = null
                            currentLogDate = null
                            task.result.set(deleteAllLogFiles())
                        } finally {
                            task.completed.countDown()
                        }
                        hasWrites = false
                    }

                    is WriteTask.CleanOld -> {
                        try {
                            flushWriter()
                            val logsDir = runCatching {
                                (KnownPaths.moduleData / "logs").createDirsSafe()
                            }.getOrNull()
                            if (logsDir != null) deleteOldLogs(logsDir)
                        } finally {
                            task.completed.countDown()
                        }
                    }
                }
            }

            if (hasWrites) flushWriter()
            batch.clear()
        }
    }

    private fun writeRecord(record: WriteTask.Record): Boolean {
        val w = getOrRotateWriter(record.timestamp.toLocalDate()) ?: return false
        return runCatching {
            w.write(buildString {
                append(timestampFmt.format(record.timestamp))
                append(' ')
                append(record.level)
                append('/')
                append(TAG)
                append(' ')
                append(record.tag)
                append(": ")
                append(record.msg)
                if (record.throwable != null) {
                    append('\n')
                    append(Log.getStackTraceString(record.throwable))
                }
            })
            w.write('\n'.code)
            true
        }.getOrElse {
            Log.e(TAG, "failed to write log file", it)
            false
        }
    }

    private fun writeDroppedNotice(timestamp: LocalDateTime) {
        val count = droppedRecords.getAndSet(0)
        if (count == 0L) return

        writeRecord(
            WriteTask.Record(
                level = "W",
                tag = "WeLogger",
                msg = "dropped $count log record(s) because the async queue was full or reserved for important logs",
                throwable = null,
                timestamp = timestamp,
            )
        )
    }

    private fun flushWriter() {
        writer?.runCatching { flush() }
    }

    private fun enqueue(record: WriteTask.Record) {
        val isImportant = record.level == "E" || record.level == "W" || record.level == "A"
        val hasRoom = isImportant || writeQueue.remainingCapacity() > RESERVED_IMPORTANT_CAPACITY
        if (!hasRoom || !writeQueue.offer(record)) {
            droppedRecords.incrementAndGet()
        }
    }

    /**
     * Wait for all records currently queued, then flush the active writer. This is intentionally
     * blocking because it is used at explicit synchronization points such as crash handling and
     * before the log viewer reads the current file; normal log calls never wait for the writer.
     */
    fun flush() {
        if (Thread.currentThread() === writerThread) {
            writeDroppedNotice(LocalDateTime.now())
            flushWriter()
            return
        }

        val completed = CountDownLatch(1)
        val barrier = WriteTask.Flush(completed)
        val enqueued = try {
            writeQueue.offer(barrier, FLUSH_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!enqueued) {
            Log.w(TAG, "timed out while enqueueing log flush barrier")
            return
        }

        val finished = try {
            completed.await(FLUSH_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!finished) {
            Log.w(TAG, "timed out while flushing log queue")
        }
    }

    // ========== File Logging: public accessors (for the log viewer UI) ==========

    /** The directory run logs are written to (`moduleData/logs`), created on first access. */
    val logsDir: java.nio.file.Path?
        get() = runCatching { (KnownPaths.moduleData / "logs").createDirsSafe() }.getOrNull()

    /**
     * All run-log files (`wekite-yyyy-MM-dd.log`), newest first. Flushes the active writer first so
     * the current day's file reflects the latest entries before the UI reads it.
     */
    val allLogFiles: List<java.nio.file.Path>
        get() {
            flush()
            val dir = logsDir ?: return emptyList()
            // 兼容旧前缀 wekit-
            val regex = Regex("""(?:wekit|wekite)-\d{4}-\d{2}-\d{2}\.log""")
            return runCatching {
                dir.toFile().listFiles()
                    ?.filter { it.isFile && regex.matches(it.name) }
                    ?.sortedByDescending { it.name }
                    ?.map { it.toPath() }
                    ?: emptyList()
            }.getOrDefault(emptyList())
        }

    // ========== Tag + String ==========

    fun e(tag: String?, msg: String) {
        Log.e(TAG, "$tag: $msg")
        enqueue(WriteTask.Record("E", tag, msg, null, LocalDateTime.now()))
    }

    fun w(tag: String?, msg: String) {
        Log.w(TAG, "$tag: $msg")
        enqueue(WriteTask.Record("W", tag, msg, null, LocalDateTime.now()))
    }

    fun i(tag: String?, msg: String) {
        Log.i(TAG, "$tag: $msg")
        enqueue(WriteTask.Record("I", tag, msg, null, LocalDateTime.now()))
    }

    fun d(tag: String?, msg: String) {
        Log.d(TAG, "$tag: $msg")
        enqueue(WriteTask.Record("D", tag, msg, null, LocalDateTime.now()))
    }

    fun v(tag: String?, msg: String) {
        Log.v(TAG, "$tag: $msg")
        enqueue(WriteTask.Record("V", tag, msg, null, LocalDateTime.now()))
    }

    // ========== Tag + String + Throwable ==========

    fun e(tag: String?, msg: String, e: Throwable) {
        Log.e(TAG, "$tag: $msg", e)
        enqueue(WriteTask.Record("E", tag, msg, e, LocalDateTime.now()))
    }

    fun w(tag: String?, msg: String, e: Throwable) {
        Log.w(TAG, "$tag: $msg", e)
        enqueue(WriteTask.Record("W", tag, msg, e, LocalDateTime.now()))
    }

    fun i(tag: String?, msg: String, e: Throwable) {
        Log.i(TAG, "$tag: $msg", e)
        enqueue(WriteTask.Record("I", tag, msg, e, LocalDateTime.now()))
    }

    fun d(tag: String?, msg: String, e: Throwable) {
        Log.d(TAG, "$tag: $msg", e)
        enqueue(WriteTask.Record("D", tag, msg, e, LocalDateTime.now()))
    }

    fun v(tag: String?, msg: String, e: Throwable) {
        Log.v(TAG, "$tag: $msg", e)
        enqueue(WriteTask.Record("V", tag, msg, e, LocalDateTime.now()))
    }

    // ========== Stack Trace ==========

    val currentStackTrace: String
        get() {
            return Thread.currentThread().stackTrace
                .drop(2) // drop getStackTrace + this function
                .joinToString(separator = "\n") { element ->
                    "at ${element.className}.${element.methodName}(${element.fileName}:${element.lineNumber})"
                }
        }

    // ========== Chunked ==========

    fun logChunked(priority: Int, tag: String, msg: String) {
        if (msg.length <= CHUNK_SIZE) {
            Log.println(priority, TAG, "$tag: $msg")
            enqueue(WriteTask.Record(priority.toPriorityChar(), tag, msg, null, LocalDateTime.now()))
            return
        }

        val len = msg.length
        val chunkCount = (len + CHUNK_SIZE - 1) / CHUNK_SIZE
        if (chunkCount > MAX_CHUNKS) {
            val head = msg.substring(0, CHUNK_SIZE)
            val headMsg = "[chunked] too long ($len chars, $chunkCount chunks). head:\n$head"
            val truncMsg = "[chunked] truncated. consider writing to file for full dump."
            Log.println(priority, TAG, "$tag: $headMsg")
            Log.println(priority, TAG, "$tag: $truncMsg")
            val timestamp = LocalDateTime.now()
            enqueue(WriteTask.Record(priority.toPriorityChar(), tag, headMsg, null, timestamp))
            enqueue(WriteTask.Record(priority.toPriorityChar(), tag, truncMsg, null, timestamp))
            return
        }

        var i = 0
        var part = 1
        val timestamp = LocalDateTime.now()
        while (i < len) {
            val end = min(i + CHUNK_SIZE, len)
            val chunk = msg.substring(i, end)
            val partMsg = "[part $part/$chunkCount] $chunk"
            Log.println(priority, TAG, "$tag: $partMsg")
            enqueue(WriteTask.Record(priority.toPriorityChar(), tag, partMsg, null, timestamp))
            i += CHUNK_SIZE
            part++
        }
    }

    fun logChunkedI(tag: String, msg: String) = logChunked(Log.INFO, tag, msg)
    fun logChunkedD(tag: String, msg: String) = logChunked(Log.DEBUG, tag, msg)

    // ========== Helpers ==========

    private fun Int.toPriorityChar(): String = when (this) {
        Log.VERBOSE -> "V"
        Log.DEBUG -> "D"
        Log.INFO -> "I"
        Log.WARN -> "W"
        Log.ERROR -> "E"
        Log.ASSERT -> "A"
        else -> "?"
    }
}
