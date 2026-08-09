package com.github.wekite.utils.fs

import android.os.Environment
import com.github.wekite.BuildConfig
import com.github.wekite.constants.PackageNames
import com.github.wekite.utils.HostInfo
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.div

object KnownPaths {

    /**
     * 应用沙盒外部目录 (Android 11+ Scoped Storage 兼容)。
     *
     * 注入微信进程时 [HostInfo.application] 是微信的 Application, 返回
     * /sdcard/Android/data/com.tencent.mm/files/ 下的模块专属子目录。
     * 使用 getExternalFilesDir 而非拼接 Environment.getExternalStorageDirectory(),
     * 否则 Android 11+ 上直接访问 /sdcard/Android/data/ 会被 FUSE 拒绝,
     * 导致 DEX 缓存等数据写入静默失败 (每次启动都重新扫描)。
     */
    val moduleData: Path by lazy {
        val base = HostInfo.application.getExternalFilesDir(null)
            ?: HostInfo.application.filesDir
        (base.toPath() / BuildConfig.TAG).createDirsSafe()
    }

    val codeCacheDir: Path by lazy {
        HostInfo.application.codeCacheDir.asPath
    }

    val moduleCache: Path by lazy {
        HostInfo.application.cacheDir.toPath().createDirsSafe() / BuildConfig.TAG
    }

    /**
     * 微信宿主进程的 DEX 缓存目录 (仅模块进程需要)。
     *
     * 设置页 SettingsActivity 运行在模块进程 (com.github.wekite), 而 DEX 缓存实际由
     * 微信进程写入 [moduleData] (getExternalFilesDir 绑定当前进程的 Application)。
     * 模块进程无法直接访问微信的沙盒目录 (Android 11+ Scoped Storage / FUSE 拒绝),
     * 只能用 root 清除。/data/media/0 是 /sdcard 的物理路径, root shell 下不经过 FUSE。
     * 微信进程内请直接使用 [moduleData], 此处返回 null。
     */
    val hostWechatDexCacheDir: Path? by lazy {
        if (HostInfo.isModule) {
            Paths.get("/data/media/0/Android/data", PackageNames.WECHAT, "files", BuildConfig.TAG, "dex_cache")
        } else null
    }

    val moduleAssets by lazy {
        (moduleData / "assets").createDirsSafe()
    }

    val downloads by lazy {
        (Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).toPath() / BuildConfig.TAG)
            .createDirsSafe()
    }
}
