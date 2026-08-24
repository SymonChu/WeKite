package com.github.wekite.features.items.system

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.clickable
import androidx.compose.material3.ListItem
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import com.github.wekite.features.core.ClickableFeature
import com.github.wekite.features.core.Feature
import com.github.wekite.preferences.WePrefs.Companion.prefOption
import com.github.wekite.ui.content.AlertDialogContent
import com.github.wekite.ui.content.Button
import com.github.wekite.ui.content.DefaultColumn
import com.github.wekite.ui.content.TextButton
import com.github.wekite.ui.content.dialogListItemColors
import com.github.wekite.ui.content.dialogRadioButtonColors
import com.github.wekite.ui.utils.showComposeDialog
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
import kotlin.time.Duration.Companion.milliseconds

@Feature(name = "自动清理日志", categories = ["系统与隐私"], description = "定期自动清理模块日志, 保留天数可调节")
object AutoCleanLogs : ClickableFeature() {

    private const val DAY_MS = 24 * 60 * 60 * 1000L

    /** 日志保留天数选项: 1天 / 3天 / 7天 */
    private val INTERVAL_OPTIONS = listOf(
        DAY_MS to "1 天",
        3 * DAY_MS to "3 天",
        7 * DAY_MS to "7 天"
    )

    // 与 WeLogger.deleteOldLogs 共用同一个 key: 保留天数全局生效
    private var intervalMs by prefOption("clean_logs_interval_ms", 3 * DAY_MS)

    private var cleanJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())


    override fun onEnable() {
        startCleaningJob()
    }

    private fun startCleaningJob() {
        cleanJob?.cancel()
        cleanJob = scope.launch {
            while (isActive) {
                performClean()
                // intervalMs is the retention period, not the scheduler interval. Check daily so
                // a 3/7-day retention setting cannot leave expired files behind for another week.
                delay(DAY_MS.milliseconds)
            }
        }
    }

    /** 删除超过保留天数的模块日志文件，交给 WeLogger 写入线程执行。 */
    private fun performClean() {
        WeLogger.deleteOldLogs()
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
                                modifier = Modifier.height(48.dp).clickable { selectedInterval = ms },
                                colors = dialogListItemColors(),
                                leadingContent = {
                                    RadioButton(selected = selectedInterval == ms, onClick = { selectedInterval = ms }, colors = dialogRadioButtonColors())
                                },
                                headlineContent = { Text(label) },
                            )
                        }
                        TextButton(onClick = {
                            scope.launch {
                                // 手动「立即清理」= 清空全部日志 (关闭 writer 后删除所有文件),
                                // 与自动清理(只删超过保留天数的旧日志)语义区分
                                val deletedSize = WeLogger.clearAllLogs()
                                showToastSuspend(context, "日志已清空, 共释放 ${formatBytesSize(deletedSize)}")
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
