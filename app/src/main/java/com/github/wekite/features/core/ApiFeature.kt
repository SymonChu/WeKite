package com.github.wekite.features.core

import com.github.wekite.utils.TargetProcesses

abstract class ApiFeature : BaseFeature() {

    override fun startup() {
        if (!TargetProcesses.isInMain) return
        enable()
    }
}
