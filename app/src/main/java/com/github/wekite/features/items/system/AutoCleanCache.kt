package com.github.wekite.features.items.system

import androidx.activity.ComponentActivity
import com.github.wekite.features.core.ClickableFeature
import com.github.wekite.features.core.Feature
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

@Feature(name = "清理缓存垃圾", categories = ["系统与隐私"], description = "自动或手动清理微信的缓存")
object AutoCleanCache : ClickableFeature() {

    private const val TAG = "AutoCleanCache"
    private const val CLEAN_INTERVAL = 30 * 60 * 1000L // 每 30 分钟清理一次

    private var cleanJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val cleanPaths = run {
        val paths = mutableListOf<Path>()

        val dataDir = HostInfo.application.filesDir.parentFile!!.toPath()
        val storageDataDir = HostInfo.application.externalCacheDir!!.toPath().parent!!

        // 注意: 不清理小程序运行目录 (dataDir/appbrand、cache/appbrand、
        // MicroMsg/appbrand、cache/liteapp、files/liteapp) —— 删除它们会导致
        // 主页下拉小程序面板黑屏 (微信运行中渲染资源被清, 2026-08 v1.36 修复)
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
                delay(CLEAN_INTERVAL.milliseconds)
            }
        }
    }

    @OptIn(ExperimentalPathApi::class)
    private fun performClean(): Long {
        var totalDeletedBytes = 0L
        cleanPaths.forEach { path ->
            try {
                WeLogger.d(TAG, "deleting $path")
                if (path.exists()) {
                    totalDeletedBytes += deletePathProtected(path)
                }
            } catch (e: Exception) {
                WeLogger.w(TAG, "exception during cleaning: ${path.fileName}, ${e.message}")
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
        scope.launch {
            val deletedSize = performClean()
            val sizeText = formatBytesSize(deletedSize)

            val timeText =
                if (isEnabled) "\n下次自动清理将在 ${formatEpoch(System.currentTimeMillis() + CLEAN_INTERVAL)} 进行"
                else ""

            showToastSuspend(context, "缓存清理完成, 共释放 $sizeText$timeText")

            if (isEnabled) startCleaningJob()
        }
    }
}
