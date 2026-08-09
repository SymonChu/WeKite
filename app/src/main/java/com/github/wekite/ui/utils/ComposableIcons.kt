package com.github.wekite.ui.utils

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

val TelegramIcon by lazy {
    ImageVector.Builder(
        name = "TelegramIcon",
        defaultWidth = 24.0.dp,
        defaultHeight = 24.0.dp,
        viewportWidth = 1024.0f,
        viewportHeight = 1024.0f
    ).apply {
        path(
            fill = SolidColor(Color.White),
            pathFillType = PathFillType.NonZero
        ) {
            moveTo(679.4f, 746.9f)
            lineToRelative(84.0f, -396.0f)
            curveToRelative(7.4f, -34.9f, -12.6f, -48.6f, -35.4f, -40.0f)
            lineToRelative(-493.7f, 190.3f)
            curveToRelative(-33.7f, 13.1f, -33.1f, 32.0f, -5.7f, 40.6f)
            lineToRelative(126.3f, 39.4f)
            lineToRelative(293.2f, -184.6f)
            curveToRelative(13.7f, -9.1f, 26.3f, -4.0f, 16.0f, 5.2f)
            lineToRelative(-237.1f, 214.3f)
            lineToRelative(-9.1f, 130.3f)
            curveToRelative(13.1f, 0.0f, 18.9f, -5.7f, 25.7f, -12.6f)
            lineToRelative(61.7f, -59.4f)
            lineToRelative(128.0f, 94.3f)
            curveToRelative(23.4f, 13.1f, 40.0f, 6.3f, 46.3f, -21.7f)
            close()
            moveTo(1024.0f, 512.0f)
            curveToRelative(0.0f, 282.8f, -229.2f, 512.0f, -512.0f, 512.0f)
            reflectiveCurveTo(0.0f, 794.8f, 0.0f, 512.0f)
            reflectiveCurveTo(229.2f, 0.0f, 512.0f, 0.0f)
            reflectiveCurveToRelative(512.0f, 229.2f, 512.0f, 512.0f)
            close()
        }
    }.build()
}
