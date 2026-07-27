package me.hd.wauxv.hook

import androidx.annotation.Keep
import io.github.we.lite.loader.abc.IHookBridge

@Keep
class HookHandle(val unhook: IHookBridge.MemberUnhookHandle) {
    override fun toString(): String {
        return "HookHandle(delegate=${unhook.member})"
    }
}
