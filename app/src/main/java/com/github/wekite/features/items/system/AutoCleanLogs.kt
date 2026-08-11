package com.github.wekite.features.items.system

import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.material3.ListItem
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.github.wekite.features.core.ClickableFeature
import com.github.wekite.features.core.Feature
import com.github.wekite.preferences.WePrefs.Companion.prefOption
import com.github.wekite.ui.content.AlertDialogContent
import com.github.wekite.ui.content.Button
import com.github.wekite.ui.content.DefaultColumn
import com.github.wekite.ui.content.TextButton
import com.github.wekite.ui.utils.showComposeDialog
import com.github.wekite.utils.WeLogger
import com.github.wekite.utils.android.showToastSuspend
import com.github.wekite.utils.formatBytesSize
import com.github.wekite.utils.formatEpoch
import com.github.wekite.utils.fs.KnownPaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.time.Duration.Companion.milliseconds

@Feature(name = "自动清理日志", categories = ["系统与隐私"], description = "定期自动清理模块日志, 保留天数可调节")
object AutoCleanLogs : ClickableFeature() {

    private const val TAG = "AutoCleanLogs"
    private const val DAY_MS = 24 * 60 * 60 * 1000L

    /** 日志保留天数选项: 1天 / 2天 / 3天 / 7天 (每 N 天自动清理一次) */
    private val INTERVAL_OPTIONS = listOf(
        DAY_MS to "1 天",
        2 * DAY_MS to "2 天",
        3 * DAY_MS to "3 天",
        7 * DAY_MS to "7 天"
    )

    // 与 WeLogger.deleteOldLogs 共用同一个 key: 保留天数全局生效
    private var intervalMs by prefOption("clean_logs_interval_ms", 3 * DAY_MS)

    private var cleanJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val logsDir = KnownPaths.moduleData / "logs"
    private val logFileRegex = Regex("""wekit-(\d{4}-\d{2}-\d{2})\.log""")
    private val dateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    override fun onEnable() {
        startCleaningJob()
    }

    private fun startCleaningJob() {
        cleanJob?.cancel()
        cleanJob = scope.launch {
            while (isActive) {
                performClean()
                delay(intervalMs.milliseconds)
            }
        }
    }

    /** 删除超过保留天数的模块日志文件 (保留今天与保留天数内的, 不删正在写的文件) */
    @OptIn(ExperimentalPathApi::class)
    private fun performClean(): Long {
        if (!logsDir.exists()) return 0L
        val retentionDays = (intervalMs / DAY_MS).coerceAtLeast(1)
        val thresholdDate = LocalDate.now().minusDays(retentionDays)
        var deletedBytes = 0L

        logsDir.toFile().listFiles()?.forEach { file ->
            val match = logFileRegex.matchEntire(file.name)
            if (match != null) {
                val fileDate = runCatching { LocalDate.parse(match.groupValues[1], dateFmt) }.getOrNull()
                if (fileDate != null && fileDate.isBefore(thresholdDate)) {
                    deletedBytes += file.length()
                    if (file.delete()) {
                        WeLogger.d(TAG, "deleted ${file.name}")
                    }
                }
            }
        }
        return deletedBytes
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var selectedInterval by remember { mutableStateOf(intervalMs) }

            AlertDialogContent(
                title = { Text("自动清理日志") },
                text = {
                    DefaultColumn {
                        Text(
                            if (isEnabled) "下次自动清理: ${formatEpoch(System.currentTimeMillis() + selectedInterval)}"
                            else "自动清理未启用, 可手动清理"
                        )
                        INTERVAL_OPTIONS.forEach { (ms, label) ->
                            ListItem(
                                modifier = Modifier.clickable { selectedInterval = ms },
                                leadingContent = {
                                    RadioButton(selected = selectedInterval == ms, onClick = { selectedInterval = ms })
                                },
                                headlineContent = { Text(label) },
                            )
                        }
                        TextButton(onClick = {
                            scope.launch {
                                val deletedSize = performClean()
                                showToastSuspend(context, "日志清理完成, 共释放 ${formatBytesSize(deletedSize)}")
                            }
                        }) { Text("立即清理") }
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        intervalMs = selectedInterval
                        if (isEnabled) startCleaningJob()
                        onDismiss()
                    }) { Text("确定") }
                },
                dismissButton = { TextButton(onDismiss) { Text("取消") } }
            )
        }
    }
}
