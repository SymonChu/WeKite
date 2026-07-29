package io.github.we.lite.ui.utils

import android.content.Context
import android.view.ContextThemeWrapper
import io.github.we.lite.loader.utils.ResourcesInjector
import io.github.we.lite.utils.reflection.ClassLoaders

class CommonContextWrapper(val base: Context) : ContextThemeWrapper(base, base.theme) {

    init {
        ResourcesInjector.injectModuleRes(resources)
    }

    override fun getClassLoader(): ClassLoader = ClassLoaders.MODULE
}
