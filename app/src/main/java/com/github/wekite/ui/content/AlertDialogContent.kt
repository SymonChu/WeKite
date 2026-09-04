package com.github.wekite.ui.content

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.github.wekite.ui.utils.theme.ThemeSettings

// drop-in replacement for AlertDialog that should be used in showComposeDialog()
// to avoid creating multiple Windows
@Composable
fun AlertDialogContent(
    modifier: Modifier = Modifier,
    icon: @Composable (() -> Unit)? = null,
    title: @Composable (() -> Unit)?,
    text: @Composable (() -> Unit)?,
    confirmButton: (@Composable () -> Unit)? = null,
    dismissButton: (@Composable () -> Unit)? = null
) {
    val dark = ThemeSettings.themeMode.resolve()
    Surface(
        shape = RoundedCornerShape(28.dp),
        tonalElevation = 6.dp,
        color = if (dark) Color(0xFF111111) else Color.White,
        contentColor = if (dark) Color.White else Color.Black,
        modifier = modifier
//            .padding(12.dp)
            .fillMaxWidth()
            // 弹窗整体封顶: 内容超屏时由 text 区滚动, 而不是被窗口直接裁掉
            // (底部选项够不着, v2.11 修复②)
            .heightIn(max = 560.dp)
            .wrapContentHeight()
    ) {
        DefaultColumn(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            scrollable = true
        ) {
            if (icon != null) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.primary) {
                        icon()
                    }
                }
            }
            title?.let {
                val customStyle = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                CompositionLocalProvider(LocalTextStyle provides customStyle) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        it()
                    }
                }
            }

            text?.let {
                val bodyStyle = MaterialTheme.typography.bodyMedium
                val bodyColor = if (dark) Color.White else Color.Black

                // 选项变多时 text 区内部滚动, 标题与按钮始终可见: 超屏内容不再被窗口裁掉
                // (底部选项够不着, v2.11 修复②)。Material3 原版同款 heightIn(max)+verticalScroll。
                Box(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .heightIn(max = 480.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    CompositionLocalProvider(
                        LocalTextStyle provides bodyStyle,
                        LocalContentColor provides bodyColor
                    ) {
                        it()
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
            ) {
                val buttonTextStyle = MaterialTheme.typography.labelLarge
                CompositionLocalProvider(LocalTextStyle provides buttonTextStyle) {
                    dismissButton?.invoke()
                    confirmButton?.invoke()
                }
            }
        }
    }
}
