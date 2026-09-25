package com.github.wekite.ui.content

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.github.wekite.ui.content.animation.rememberRotatingRingAngle
import com.github.wekite.ui.content.animation.rotatingBorderRing
import com.github.wekite.ui.content.animation.staticBorderRing
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
    dismissButton: (@Composable () -> Unit)? = null,
    /**
     * true = 卡片高度撑满可用高度（配合调用方把弹窗窗口设成固定大小，
     * 用于「报告弹窗上下留白」这类需求）；默认 false = 原来的 wrapContentHeight。
     */
    fillHeight: Boolean = false,
    /**
     * 卡片边缘的**静态描边色**：null = 不画（默认，其它弹窗不受影响）。
     * 与 [rotatingBorder] = true 同时给出时以「旋转流光」为准。
     * 用于「分析报告」弹窗（用户 2026-09-25：报告只要蓝色描边、不要动效）。
     */
    borderColor: Color? = null,
    /**
     * true = 卡片边缘加一圈**旋转流光**（用户 2026-09-24 要求：围绕弹窗的旋转动效）。
     * 只画在卡片自己的边缘上，**不改卡片尺寸/位置**；默认 false，其它弹窗不受影响。
     * 实现见 [com.github.wekite.ui.content.animation.rotatingBorderRing]。
     */
    rotatingBorder: Boolean = false,
    /**
     * body 是否由公共层提供「限高 + 纵向滚动」。默认 true。
     * 用户 2026-09-25：设置项过多时弹窗底部顶到屏幕边缘 ⇒ 公共层统一限高（[DIALOG_MAX_HEIGHT_FRACTION]），
     * 内容放不下就滚动。
     *
     * ⚠️⚠️ 调用方 `text` 里**自己已经有** `verticalScroll` / `LazyColumn` 时必须显式传 `false`：
     * 两层纵向滚动会给内层传**无限高约束**、测量阶段抛异常 —— 发生在微信进程里就是**闪退**
     * （本仓 v1.8x 真机踩过，见技能 `references/dialog-nested-scroll-regression.md`）。
     * 结构断言脚本：`workspace/audit-dialog-scroll.py <app/src/main/java>`（漏传一处即 exit 1）。
     */
    bodyScrollable: Boolean = true
) {
    val dark = ThemeSettings.themeMode.resolve()
    val maxCardHeight = LocalConfiguration.current.screenHeightDp.dp * DIALOG_MAX_HEIGHT_FRACTION
    // 只在需要时创建无限动画（常开的无限动画即使不画也会占帧回调）
    val ringAngle = if (rotatingBorder) rememberRotatingRingAngle() else null
    val ringModifier = when {
        ringAngle != null ->
            Modifier.rotatingBorderRing(angle = ringAngle, cornerRadius = 28.dp)
        borderColor != null ->
            Modifier.staticBorderRing(borderColor = borderColor, cornerRadius = 28.dp)
        else -> Modifier
    }
    Surface(
        shape = RoundedCornerShape(28.dp),
        tonalElevation = 6.dp,
        color = if (dark) Color(0xFF111111) else Color.White,
        contentColor = if (dark) Color.White else Color.Black,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = maxCardHeight)
            .then(if (fillHeight) Modifier.fillMaxHeight() else Modifier.wrapContentHeight())
            .then(ringModifier)
    ) {
        DefaultColumn(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
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

                // 限高 + 溢出滚动由公共层提供；body 自带滚动容器的调用方必须传 bodyScrollable = false
                val bodyModifier = if (bodyScrollable) {
                    Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
                } else {
                    Modifier.weight(1f, fill = false)
                }
                Box(modifier = bodyModifier) {
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

/**
 * 公共弹窗卡片的最大高度 = 屏高 × 该比例（上下各留出 ≈6% 屏高）。
 * 用户 2026-09-25：设置项过多时弹窗底部已顶到屏幕边缘，要留一点距离；内容放不下就由 body 滚动
 * （见 [AlertDialogContent] 的 `bodyScrollable`）。
 */
private const val DIALOG_MAX_HEIGHT_FRACTION = 0.88f
