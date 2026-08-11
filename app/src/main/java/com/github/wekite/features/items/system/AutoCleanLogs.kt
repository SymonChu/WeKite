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
import com.github.wekite.utils.HostInfo
import com.github.wekite.utils.WeLogger
import com.github.wekite.utils.android.showToastSuspend
import com.github.wekite.utils.formatBytesSize
import com.github.wekite.utils.formatEpoch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.time.Duration.Companion.milliseconds

@Feature(name = "自动清理日志", categories = ["系统与隐私"], description = "定期自动清理微信日志文件 (xlog/onelog/tbslog), 清理周期可调节")
object AutoCleanLogs : ClickableFeature() {

    private const val TAG = "AutoCleanLogs"

    /** 日志清理周期选项: 1天 / 2天 / 3天 / 7天 */
    private val INTERVAL_OPTIONS = listOf(
        24 * 60 * 60 * 1000L to "1 天",
        2 * 24 * 60 * 60 * 1000L to "2 天",
        3 * 24 * 60 * 60 * 1000L to "3 天",
        7 * 24 * 60 * 60 * 1000L to "7 天"
    )

    private var intervalMs by prefOption("clean_logs_interval_ms", 24 * 60 * 60 * 1000L)

    private var cleanJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val logPaths = run {
        val paths = mutableListOf<Path>()
        val storageDataDir = HostInfo.application.externalCacheDir!!.toPath().parent!!
        paths.add(storageDataDir / "files" / "xlog")
        paths.add(storageDataDir / "files" / "onelog")
        paths.add(storageDataDir / "files" / "tbslog")
        paths.add(storageDataDir / "files" / "Tencent" / "tbs_common_log")
        paths.add(storageDataDir / "files" / "Tencent" / "tbs_live_log")
        return@run paths
    }

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

    @OptIn(ExperimentalPathApi::class)
    private fun performClean(): Long {
        var totalDeletedBytes = 0L
        logPaths.forEach { path ->
            try {
                WeLogger.d(TAG, "deleting $path")
                if (path.exists()) {
                    totalDeletedBytes += calculateSize(path)
                    path.deleteRecursively()
                }
            } catch (e: Exception) {
                WeLogger.w(TAG, "exception during cleaning: ${path.fileName}, ${e.message}")
            }
        }
        return totalDeletedBytes
    }

    private fun calculateSize(path: Path): Long {
        val file = path.toFile()
        if (!file.exists()) return 0L
        if (file.isFile) return file.length()

        var size = 0L
        file.listFiles()?.forEach {
            size += if (it.isDirectory) calculateSize(it.toPath()) else it.length()
        }
        return size
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
