package io.github.we.lite.features.items.moments

import com.tencent.mm.plugin.sns.storage.ADInfo
import dev.ujhhgtg.reflekt.reflekt
import io.github.we.lite.features.core.Feature
import io.github.we.lite.features.core.SwitchFeature
import io.github.we.lite.utils.WeLogger

@Feature(name = "拦截朋友圈广告", categories = ["朋友圈"], description = "拦截朋友圈广告")
object RemoveMomentsAds : SwitchFeature() {

    private const val TAG = "RemoveMomentsAds"

    override fun onEnable() {
        ADInfo::class.reflekt()
            .firstConstructor {
                parameters(String::class)
            }
            .hookBefore {
                WeLogger.i(TAG, "blocked ad")
                result = null
            }
    }
}
