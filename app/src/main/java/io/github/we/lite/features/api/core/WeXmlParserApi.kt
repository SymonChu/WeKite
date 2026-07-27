package io.github.we.lite.features.api.core

import io.github.we.lite.dexkit.abc.IResolveDex
import io.github.we.lite.dexkit.dsl.dexMethod
import io.github.we.lite.features.core.ApiFeature
import io.github.we.lite.features.core.Feature
import io.github.we.lite.utils.HookParam
import io.github.we.lite.utils.WeLogger
import java.util.concurrent.CopyOnWriteArrayList

@Feature(name = "XML 解析钩子服务", categories = ["API"], description = "提供篡改 XML 解析流程的能力")
object WeXmlParserApi : ApiFeature(), IResolveDex {

    private const val TAG = "WeXmlParserApi"

    fun interface IAfterParseListener {
        fun onParse(param: HookParam, result: MutableMap<String, Any?>)
    }

    private val listeners = CopyOnWriteArrayList<IAfterParseListener>()

    fun addListener(listener: IAfterParseListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: IAfterParseListener) {
        listeners.remove(listener)
    }

    private val methodXmlParser by dexMethod {
        searchPackages("com.tencent.mm.sdk.platformtools")
        matcher {
            usingEqStrings("MicroMsg.SDK.XmlParser", "[ %s ]")
        }
    }

    override fun onEnable() {
        methodXmlParser.hookAfter {
            val param = this

            @Suppress("UNCHECKED_CAST")
            val result = result as? MutableMap<String, Any?>? ?: return@hookAfter
            listeners.forEach { listener ->
                runCatching {
                    @Suppress("UNCHECKED_CAST")
                    listener.onParse(param, result)
                }.onFailure { WeLogger.e(TAG, "failed to execute listener ${listener.javaClass.name}", it) }
            }
        }
    }

    override fun onDisable() {
        listeners.clear()
    }
}
