package com.github.wekite.ui.content

import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.RadioButtonColors
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.github.wekite.ui.utils.theme.ThemeSettings

/**
 * 弹窗内 Switch/Slider/ListItem/RadioButton 的统一配色（v1.45 起）：
 * M3 默认未选中轨道色是 surfaceContainerHighest —— 从模块蓝 seed 生成后带蓝调，
 * 盖在弹窗白/黑底上就是用户看到的「残留淡蓝」；ListItem 说明文字默认
 * onSurfaceVariant 同样是蓝调灰。这里统一为中性色：
 * - Switch/Slider 未选中轨道：中性灰（浅色浅灰 / 深色深灰），选中轨道/滑块保持模块蓝
 * - ListItem：透明容器 + 说明文字中性灰
 * - RadioButton 未选中描边：中性灰（选中仍模块蓝）
 * 深浅跟随模块主题（ThemeSettings），与 ModuleTheme 一致，不跟系统。
 */
@Composable
fun dialogSwitchColors(): SwitchColors {
    val dark = ThemeSettings.themeMode.resolve()
    return SwitchDefaults.colors(
        uncheckedTrackColor = if (dark) Color(0xFF3A3A3A) else Color(0xFFE2E2E2),
    )
}

@Composable
fun dialogSliderColors(): SliderColors {
    val dark = ThemeSettings.themeMode.resolve()
    return SliderDefaults.colors(
        inactiveTrackColor = if (dark) Color(0xFF3A3A3A) else Color(0xFFE2E2E2),
    )
}

@Composable
fun dialogListItemColors(): ListItemColors {
    val dark = ThemeSettings.themeMode.resolve()
    return ListItemDefaults.colors(
        containerColor = Color.Transparent,
        headlineColor = if (dark) Color.White else Color.Black,
        supportingColor = if (dark) Color(0xFFAAAAAA) else Color(0xFF666666),
    )
}

@Composable
fun dialogRadioButtonColors(): RadioButtonColors {
    val dark = ThemeSettings.themeMode.resolve()
    return RadioButtonDefaults.colors(
        unselectedColor = if (dark) Color(0xFFAAAAAA) else Color(0xFF757575),
    )
}

/**
 * 弹窗内可点击的占位符小标签 (chip) 配色: 中性灰底 + 纯黑/白字。
 * M3 的 secondaryContainer / onSecondaryContainer 从模块蓝 seed 生成后带蓝调,
 * 与弹窗"中性灰未选中控件 + 纯黑白文字"的统一标准冲突 (v1.45 起)。
 */
@Composable
fun dialogChipColors(): Pair<Color, Color> {
    val dark = ThemeSettings.themeMode.resolve()
    val container = if (dark) Color(0xFF3A3A3A) else Color(0xFFE2E2E2)
    val content = if (dark) Color.White else Color.Black
    return container to content
}
