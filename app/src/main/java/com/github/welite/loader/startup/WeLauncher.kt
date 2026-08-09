package com.github.welite.loader.startup

import android.content.Context
import com.tencent.mm.boot.BuildConfig
import com.github.welite.constants.PackageNames
import com.github.welite.constants.Preferences
import com.github.welite.dexkit.cache.DexCacheManager
import com.github.welite.features.core.FeaturesLoader
import com.github.welite.loader.utils.ActivityProxy
import com.github.welite.loader.utils.ParcelableFixer
import com.github.welite.loader.utils.ResourcesInjector
import com.github.welite.utils.HostInfo
import com.github.welite.utils.RuntimeConfig
import com.github.welite.utils.TargetProcesses
import com.github.welite.utils.WeLogger

object WeLauncher {

    fun init(context: Context) {
        WeLogger.d(TAG, "loading in process name=${TargetProcesses.currentName}, type=${TargetProcesses.currentType}")

        ParcelableFixer.init()

        DexCacheManager.init(
            if (!Preferences.resetDexCacheOnHotUpdate) "${HostInfo.versionName}${HostInfo.versionCode}"
            else "${BuildConfig.VERSION_NAME}${BuildConfig.VERSION_CODE}${BuildConfig.CLIENT_VERSION_ARM64}"
        )

        val appContext = context.applicationContext ?: context
        ResourcesInjector.injectModuleRes(appContext.resources)

        if (TargetProcesses.isInMain) {
            ActivityProxy.init(appContext)

            val prefs =
                context.getSharedPreferences("${PackageNames.WECHAT}_preferences", Context.MODE_PRIVATE)
            RuntimeConfig.mmPrefs = prefs
        }

        runCatching {
            FeaturesLoader.loadFeatures()
        }.onFailure { WeLogger.e(TAG, "failed to load features", it) }
    }

    private const val TAG = "WeLauncher"
}
