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

@Feature(name = "清理缓存垃圾", categories = ["系统与隐私"], description = "自动或手动清理微信的缓存, 清理间隔可调节")
object AutoCleanCache : ClickableFeature() {

    private const val TAG = "AutoCleanCache"

    /** 清理间隔选项: 1小时 / 4小时 / 1天 */
    private val INTERVAL_OPTIONS = listOf(
        60 * 60 * 1000L to "1 小时",
        4 * 60 * 60 * 1000L to "4 小时",
        24 * 60 * 60 * 1000L to "1 天"
    )

    private var intervalMs by prefOption("clean_cache_interval_ms", 30 * 60 * 1000L)

    private var cleanJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val cleanPaths = run {
        val paths = mutableListOf<Path>()

        val dataDir = HostInfo.application.filesDir.parentFile!!.toPath()
        val storageDataDir = HostInfo.application.externalCacheDir!!.toPath().parent!!

        // 注意: 不清理小程序运行目录 (dataDir/appbrand、cache/appbrand、
        // MicroMsg/appbrand、cache/liteapp、files/liteapp) —— 删除它们会导致
        // 主页下拉小程序面板黑屏 (微信运行中渲染资源被清, 2026-08 v1.36 修复)
        // 微信日志目录 (xlog/onelog/tbslog) 也在此清理
        paths.add(dataDir / "cache")
        paths.add(dataDir / "MicroMsg" / "crash")
        paths.add(dataDir / "tinker")
        paths.add(dataDir / "tinker_server")
        paths.add(dataDir / "tinker_temp")
        paths.add(storageDataDir / "cache")
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
        cleanPaths.forEach { path ->
            try {
                if (path.exists()) {
                    totalDeletedBytes += deletePathProtected(path)
                }
            } catch (e: Exception) {
                // tinker 目录是微信热更新在用, 删不掉属预期, 不刷警告日志
                val name = path.fileName.toString()
                if (name !in setOf("tinker", "tinker_server", "tinker_temp")) {
                    WeLogger.w(TAG, "exception during cleaning: $name, ${e.message}")
                }
            }
        }
        return totalDeletedBytes
    }

    /**
     * 删除路径，但保护小程序运行目录（appbrand/liteapp）。
     * 主缓存目录 dataDir/cache 递归删除时会跳过这些子目录，
     * 避免清理时微信正在渲染的小程序资源被清导致下拉面板黑屏。
     */
    @OptIn(ExperimentalPathApi::class)
    private fun deletePathProtected(path: Path): Long {
        val protected = setOf("appbrand", "liteapp")
        val cacheRoot = HostInfo.application.filesDir.parentFile!!.toPath() / "cache"
        if (path == cacheRoot && path.exists()) {
            var deleted = 0L
            path.toFile().listFiles()?.forEach { child ->
                if (child.name !in protected) {
                    deleted += calculateSize(child.toPath())
                    child.deleteRecursively()
                }
            }
            return deleted
        }
        val size = calculateSize(path)
        path.deleteRecursively()
        return size
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
                title = { Text("清理缓存垃圾") },
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
                                val deletedSize = performClean()
                                showToastSuspend(context, "缓存清理完成, 共释放 ${formatBytesSize(deletedSize)}")
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
