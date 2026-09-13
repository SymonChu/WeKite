package com.github.wekite.loader.startup

import android.content.Context
import com.github.wekite.constants.PackageNames
import com.github.wekite.dexkit.cache.DexCacheManager
import com.github.wekite.features.core.FeaturesLoader
import com.github.wekite.loader.utils.ActivityProxy
import com.github.wekite.loader.utils.ParcelableFixer
import com.github.wekite.loader.utils.ResourcesInjector
import com.github.wekite.utils.HostInfo
import com.github.wekite.utils.RuntimeConfig
import com.github.wekite.utils.TargetProcesses
import com.github.wekite.utils.WeLogger

object WeLauncher {

    fun init(context: Context) {
        WeLogger.d(TAG, "loading in process name=${TargetProcesses.currentName}, type=${TargetProcesses.currentType}")

        ParcelableFixer.init()

        // DEX 缓存键固定为「模块版本号」。曾经由「兼容 → 宿主热更新时重新适配」开关控制是否改用
        // 宿主版本号 (微信热更新时强制重新适配); 该开关已从设置界面移除, 行为固化为原来的默认值
        // (不重置), 也就是这里唯一保留的表达式。
        DexCacheManager.init("${HostInfo.versionName}${HostInfo.versionCode}")

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
