package io.github.we.lite.loader.startup

import android.content.Context
import com.tencent.mm.boot.BuildConfig
import io.github.we.lite.constants.PackageNames
import io.github.we.lite.constants.Preferences
import io.github.we.lite.dexkit.cache.DexCacheManager
import io.github.we.lite.features.core.FeaturesLoader
import io.github.we.lite.loader.utils.ActivityProxy
import io.github.we.lite.loader.utils.ParcelableFixer
import io.github.we.lite.loader.utils.ResourcesInjector
import io.github.we.lite.utils.HostInfo
import io.github.we.lite.utils.RuntimeConfig
import io.github.we.lite.utils.TargetProcesses
import io.github.we.lite.utils.WeLogger

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
