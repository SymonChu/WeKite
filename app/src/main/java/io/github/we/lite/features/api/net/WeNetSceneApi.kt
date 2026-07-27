package io.github.we.lite.features.api.net

import dev.ujhhgtg.reflekt.reflekt
import io.github.we.lite.dexkit.abc.IResolveDex
import io.github.we.lite.dexkit.dsl.dexClass
import io.github.we.lite.dexkit.dsl.dexMethod
import io.github.we.lite.features.core.ApiFeature
import io.github.we.lite.features.core.Feature

@Feature(name = "NetScene 服务", categories = ["API"], description = "提供 NetScene 发送能力")
object WeNetSceneApi : ApiFeature(), IResolveDex {

    fun sendNetScene(netScene: Any) {
        val queue = classMmKernel.clazz.reflekt()
            .firstMethod {
                returnType = methodAddNetSceneToQueue.method.declaringClass
            }.invokeStatic()!!
        methodAddNetSceneToQueue.method.invoke(queue, netScene, 0)
    }

    val classMmKernel by dexClass {
        matcher {
            usingEqStrings("MicroMsg.MMKernel", "Kernel not null, has initialized.")
        }
    }

    val methodAddNetSceneToQueue by dexMethod {
        matcher {
            usingEqStrings("MicroMsg.NetSceneQueue", "forbid in waiting: type=", "forbid in running: type=")
        }
    }
}
