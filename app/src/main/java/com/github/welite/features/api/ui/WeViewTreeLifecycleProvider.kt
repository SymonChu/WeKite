package com.github.welite.features.api.ui

import android.app.Activity
import com.tencent.mm.ui.LauncherUI
import com.github.welite.features.core.ApiFeature
import com.github.welite.features.core.Feature
import com.github.welite.ui.utils.LifecycleOwnerProvider
import com.github.welite.ui.utils.rootView
import com.github.welite.ui.utils.setLifecycleOwner

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
