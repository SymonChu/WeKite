package com.github.wekite.ui.utils.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable

@Composable
fun ModuleAppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    // 与 miuix 默认主题色一致 (miuix 默认蓝 0xFF3482FF), 模块 App 与设置页颜色统一
    val colorScheme = SeedResolver.materialScheme(0xFF3482FF.toInt(), darkTheme)
    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        motionScheme = MotionScheme.expressive(),
    ) {
        content()
    }
}
