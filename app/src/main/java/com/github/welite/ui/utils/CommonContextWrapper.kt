package com.github.welite.ui.utils

import android.content.Context
import android.view.ContextThemeWrapper
import com.github.welite.loader.utils.ResourcesInjector
import com.github.welite.utils.reflection.ClassLoaders

class CommonContextWrapper(val base: Context) : ContextThemeWrapper(base, base.theme) {

    init {
        ResourcesInjector.injectModuleRes(resources)
    }

    override fun getClassLoader(): ClassLoader = ClassLoaders.MODULE
}
