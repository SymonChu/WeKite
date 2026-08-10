package com.github.wekite.utils

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.github.wekite.BuildConfig
import com.github.wekite.constants.PackageNames
import com.github.wekite.loader.entry.zygisk.ZygiskLoaderService
import com.github.wekite.loader.startup.StartupInfo
import com.github.wekite.utils.android.getSystemService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Serializable
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
)

sealed interface UpdateResult {
    /** Remote versionCode ≤ installed versionCode. */
    data object UpToDate : UpdateResult

    /** A newer version is available. */
    data class UpdateAvailable(val info: UpdateInfo) : UpdateResult

    /** Something went wrong while checking or downloading. */
    data class Error(val cause: Throwable) : UpdateResult
}

// ─── GitHub release lookup ───────────────────────────────────────────────────

private const val REPO = "SymonChu/WeKite"
private const val LATEST_RELEASE_API = "https://api.github.com/repos/$REPO/releases/latest"
private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
private const val ZIP_MIME_TYPE = "application/zip"

// 发布到 GitHub Release 的 APK 资产名 (universal, 含双 ABI)。
private const val RELEASE_APK_NAME = "app-standard-release.apk"

// 发布时附带的版本元数据资产 (手动发布流程会随 Release 一起上传)。
// 内容: {"versionCode": 925, "versionName": "1.0"}
private const val UPDATE_JSON_NAME = "update.json"

// ─── AppUpdater ───────────────────────────────────────────────────────────────

/**
 * Self-contained in-app updater for WeKite.
 *
 * Fetches the latest GitHub Release via the public API (no token needed for a
 * public repo, unauthenticated rate limit applies) and downloads the matching
 * artifact: the universal APK for LSPosed/Xposed modes, or the Zygisk module
 * ZIP when running under the Zygisk loader.
 *
 * Usage:
 * ```
 * when (val result = AppUpdater.checkForUpdate()) {
 *     is UpdateResult.UpdateAvailable -> AppUpdater.downloadAndInstall(context, result.info)
 *     is UpdateResult.UpToDate        -> { /* nothing to do */ }
 *     is UpdateResult.Error           -> { /* show error */ }
 * }
 * ```
 */
object AppUpdater {

    private val json = Json { ignoreUnknownKeys = true }

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    /** versionCode / versionName / download URLs of the latest GitHub release. */
    private class LatestRelease(
        val versionCode: Int,
        val versionName: String,
        val apkUrl: String,
        val zygiskZipUrl: String?,
        val zygiskZipName: String?,
    )

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Queries the latest GitHub release and compares its versionCode with the
     * currently installed version.
     *
     * Must be called from a coroutine; network I/O runs on [Dispatchers.IO].
     */
    suspend fun checkForUpdate(): UpdateResult = withContext(Dispatchers.IO) {
        runCatching {
            val release = fetchLatestRelease()
            if (release.versionCode > BuildConfig.VERSION_CODE) {
                UpdateResult.UpdateAvailable(
                    UpdateInfo(release.versionCode, release.versionName)
                )
            } else {
                UpdateResult.UpToDate
            }
        }.getOrElse {
            UpdateResult.Error(it)
        }
    }

    /**
     * Downloads the update matching the active loader. Zygisk mode downloads
     * the module ZIP and opens it with a compatible root manager; other modes
     * download the APK and open the system package installer.
     *
     * Requires the `REQUEST_INSTALL_PACKAGES` permission and a FileProvider
     * authority of `<packageName>.provider` in your manifest.
     *
     * Must be called from a coroutine; completion is awaited via a
     * [BroadcastReceiver] on [Dispatchers.Main].
     */
    suspend fun downloadAndInstall(context: Context, info: UpdateInfo) {
        val isZygisk = StartupInfo.loaderService is ZygiskLoaderService
        val release = fetchLatestRelease()
        val fileName: String
        val downloadUrl: String
        val mimeType: String
        if (isZygisk) {
            // 直接用 GitHub Release 上的实际资产名 (WeKite-<N>-git+<hash>-release.zip),
            // 避免本地拼接与打包器命名规则不一致。
            val zygiskName = release.zygiskZipName
                ?: error("最新发行版缺少 Zygisk 模块包")
            fileName = zygiskName
            downloadUrl = release.zygiskZipUrl ?: error("最新发行版缺少 Zygisk 下载地址")
            mimeType = ZIP_MIME_TYPE
        } else {
            fileName = "wekit-${info.versionName}.apk"
            downloadUrl = release.apkUrl
            mimeType = APK_MIME_TYPE
        }

        val downloadId = enqueueDownload(context, downloadUrl, fileName, mimeType)
        val downloadedFile = waitForDownload(context, downloadId)
        val contentUri = getDownloadedFileUri(context, downloadedFile)

        if (isZygisk) {
            launchKsuWithModule(context, contentUri)
        } else {
            installApk(context, contentUri)
        }
    }

    fun launchKsuWithModule(context: Context, zipUri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(zipUri, ZIP_MIME_TYPE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }

    // ── GitHub API ───────────────────────────────────────────────────────────

    /**
     * Fetches the latest published (non-prerelease) release and resolves the
     * artifact URLs plus the version identifiers.
     *
     * versionCode/versionName 解析优先级:
     * 1. Release 的 `update.json` 资产 (发布流程附带, 内容最精确);
     * 2. Zygisk ZIP 资产名 `WeKite-<N>-git+<hash>-release.zip` 里的 N;
     * 3. Release tag `v<N>` (仅当 tag 是纯数字前缀时可用)。
     */
    private fun fetchLatestRelease(): LatestRelease {
        val request = Request.Builder().url(LATEST_RELEASE_API).build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("HTTP ${response.code} 获取最新发行版信息失败")
            }
            val root = json.parseToJsonElement(response.body.string()).jsonObject

            var apkUrl: String? = null
            var zygiskZipUrl: String? = null
            var zygiskZipName: String? = null
            var updateJsonUrl: String? = null

            root["assets"]?.jsonArray?.forEach { assetEl ->
                val asset = assetEl.jsonObject
                val name = asset["name"]?.jsonPrimitive?.content ?: return@forEach
                val url = asset["browser_download_url"]?.jsonPrimitive?.content ?: return@forEach
                when {
                    name == RELEASE_APK_NAME -> apkUrl = url
                    name == UPDATE_JSON_NAME -> updateJsonUrl = url
                    name.startsWith("WeKite-") && name.endsWith("-release.zip") -> {
                        zygiskZipName = name
                        zygiskZipUrl = url
                    }
                }
            }

            val apkUrlValue = apkUrl ?: error("最新发行版缺少 APK 资产 ($RELEASE_APK_NAME)")
            val tag = root["tag_name"]?.jsonPrimitive?.content ?: ""

            // 1) update.json 资产优先
            val fromUpdateJson = updateJsonUrl?.let { fetchUpdateJson(it) }
            var versionCode = fromUpdateJson?.versionCode
            var versionName = fromUpdateJson?.versionName

            // 2) 回退: Zygisk zip 资产名解析
            if (versionCode == null) {
                versionCode = zygiskZipName
                    ?.let { Regex("WeKite-(\\d+)-").find(it)?.groupValues?.get(1)?.toIntOrNull() }
            }
            if (versionName == null) {
                versionName = zygiskZipName
                    ?.let { Regex("WeKite-\\d+-(.+)-release\\.zip").find(it)?.groupValues?.get(1) }
            }

            // 3) 最后回退: tag 解析
            if (versionCode == null) {
                versionCode = Regex("v(\\d+)").find(tag)?.groupValues?.get(1)?.toIntOrNull()
            }
            if (versionName == null) {
                versionName = tag.removePrefix("v").ifEmpty { null }
            }

            return LatestRelease(
                versionCode = versionCode ?: error("无法从发行版解析 versionCode"),
                versionName = versionName ?: "unknown",
                apkUrl = apkUrlValue,
                zygiskZipUrl = zygiskZipUrl,
                zygiskZipName = zygiskZipName,
            )
        }
    }

    private fun fetchUpdateJson(url: String): UpdateInfo? = runCatching {
        val request = Request.Builder().url(url).build()
        httpClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return@runCatching null
            json.decodeFromString<UpdateInfo>(resp.body.string())
        }
    }.getOrNull()

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun enqueueDownload(
        context: Context,
        url: String,
        fileName: String,
        mimeType: String,
    ): Long {
        val request = DownloadManager.Request(url.toUri()).apply {
            setTitle("WeKite 更新")
            setDescription("正在下载更新...")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            setMimeType(mimeType)
        }
        val dm = context.getSystemService<DownloadManager>()
        return dm.enqueue(request)
    }

    /** Suspends until [DownloadManager] broadcasts completion for [downloadId]. */
    private suspend fun waitForDownload(context: Context, downloadId: Long): File =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(ctx: Context, intent: Intent) {
                        val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                        if (id != downloadId) return

                        context.unregisterReceiver(this)

                        val dm = context.getSystemService<DownloadManager>()
                        val query = DownloadManager.Query().setFilterById(downloadId)

                        dm.query(query)?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                val statusCol = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                                if (cursor.getInt(statusCol) == DownloadManager.STATUS_SUCCESSFUL) {

                                    // 核心：动态获取 DownloadManager 实际保存的本地真实路径
                                    val localUriCol = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                                    val localUriStr = cursor.getString(localUriCol)

                                    runCatching {
                                        val realFile = File(android.net.Uri.parse(localUriStr).path!!)
                                        cont.resume(realFile)
                                    }.getOrElse {
                                        cont.resumeWithException(RuntimeException("Failed to resolve download path", it))
                                    }
                                } else {
                                    val reasonCol = cursor.getColumnIndex(DownloadManager.COLUMN_REASON)
                                    cont.resumeWithException(RuntimeException("Download failed: reason=${cursor.getInt(reasonCol)}"))
                                }
                            } else {
                                cont.resumeWithException(RuntimeException("Download query returned no results"))
                            }
                        } ?: cont.resumeWithException(RuntimeException("Download query returned null cursor"))
                    }
                }

                val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    @Suppress("UnspecifiedRegisterReceiverFlag")
                    context.registerReceiver(receiver, filter)
                }

                cont.invokeOnCancellation {
                    runCatching { context.unregisterReceiver(receiver) }
                    val dm = context.getSystemService<DownloadManager>()
                    dm.remove(downloadId)
                }
            }
        }

    private fun getDownloadedFileUri(context: Context, file: File): Uri {
        /*
        <provider
            android:name="androidx.core.content.FileProvider"
            android:exported="false"
            android:process=":recovery"
            android:authorities="com.tencent.mm.external.recovery.logprovider"
            android:grantUriPermissions="true">
            <meta-data
                android:name="android.support.FILE_PROVIDER_PATHS"
                android:resource="@xml/di"/>
        </provider>
         */

        return FileProvider.getUriForFile(
            context,
            "${PackageNames.WECHAT}.external.recovery.logprovider",
            file,
        )
    }

    private fun installApk(context: Context, apkUri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, APK_MIME_TYPE)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        context.startActivity(intent)
    }
}
