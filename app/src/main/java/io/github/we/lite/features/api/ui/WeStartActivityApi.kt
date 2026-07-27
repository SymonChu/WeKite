package io.github.we.lite.features.api.ui

import android.app.Activity
import android.content.ContextWrapper
import android.content.Intent
import io.github.we.lite.BuildConfig
import io.github.we.lite.features.core.ApiFeature
import io.github.we.lite.features.core.Feature
import io.github.we.lite.utils.HookParam
import io.github.we.lite.utils.WeLogger
import java.util.concurrent.CopyOnWriteArrayList

@Feature(name = "活动启动监听服务", categories = ["API"], description = "提供 startActivity 监听能力")
object WeStartActivityApi : ApiFeature() {

    fun interface IStartActivityListener {
        fun onStartActivity(param: HookParam, intent: Intent)
    }

    private const val TAG = "WeStartActivityApi"

    private val listeners = CopyOnWriteArrayList<IStartActivityListener>()

    fun addListener(listener: IStartActivityListener) {
        listeners.addIfAbsent(listener)
    }

    fun removeListener(listener: IStartActivityListener) {
        listeners.remove(listener)
    }

    override fun onEnable() {
        listOf(
            Activity::class.java,
            ContextWrapper::class.java
        ).forEach { clazz ->
            clazz.declaredMethods.forEach {
                if (it.name != "startActivity" && it.name != "startActivityForResult") {
                    return@forEach
                }
                it.hookBefore {
                    handleStartActivity(this)
                }
            }
        }
    }

    private fun handleStartActivity(param: HookParam) {
        val intent = param.args[0] as? Intent ?: param.args[1] as? Intent
        if (intent == null) {
            WeLogger.w(TAG, "startActivity called but no Intent found in arguments")
            return
        }

        if (intent.getBooleanExtra(BuildConfig.TAG, false)) {
            return
        }

        listeners.forEach { listener ->
            try {
                listener.onStartActivity(param, intent)
            } catch (e: Throwable) {
                WeLogger.e(TAG, "listener threw an exception: ${e.message}")
            }
        }
    }
}
