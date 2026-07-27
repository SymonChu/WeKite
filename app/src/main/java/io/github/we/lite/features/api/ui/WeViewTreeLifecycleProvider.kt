package io.github.we.lite.features.api.ui

import android.app.Activity
import com.tencent.mm.ui.LauncherUI
import io.github.we.lite.features.core.ApiFeature
import io.github.we.lite.features.core.Feature
import io.github.we.lite.ui.utils.LifecycleOwnerProvider
import io.github.we.lite.ui.utils.rootView
import io.github.we.lite.ui.utils.setLifecycleOwner

@Feature(name = "Compose 生命周期提供方", categories = ["API"])
object WeViewTreeLifecycleProvider : ApiFeature() {

    override fun onEnable() {
        LauncherUI::class.hookAfterOnCreate {
            val activity = thisObject as Activity

            val lifecycleOwner = LifecycleOwnerProvider.lifecycleOwner

            val decorView = activity.window.decorView
            decorView.setLifecycleOwner(lifecycleOwner)
            activity.rootView.setLifecycleOwner(lifecycleOwner)
        }
    }
}
