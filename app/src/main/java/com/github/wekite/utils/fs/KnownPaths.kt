package com.github.wekite.utils.fs

import android.os.Environment
import com.github.wekite.BuildConfig
import com.github.wekite.constants.PackageNames
import com.github.wekite.utils.HostInfo
import java.nio.file.Path
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

    val moduleAssets by lazy {
        (moduleData / "assets").createDirsSafe()
    }

    val downloads by lazy {
        (Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).toPath() / BuildConfig.TAG)
            .createDirsSafe()
    }
}
