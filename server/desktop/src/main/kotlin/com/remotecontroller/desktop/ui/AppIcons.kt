package com.remotecontroller.desktop.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

val RemoteServerAppIcon: ImageVector
    get() = ImageVector.Builder(
        name = "RemoteServerAppIcon",
        defaultWidth = 48.dp,
        defaultHeight = 48.dp,
        viewportWidth = 48f,
        viewportHeight = 48f,
    ).apply {
        path(
            fill = SolidColor(Color(0xFF1B5E20)),
            stroke = null,
            pathFillType = PathFillType.NonZero,
        ) {
            moveTo(0f, 0f)
            lineTo(48f, 0f)
            lineTo(48f, 48f)
            lineTo(0f, 48f)
            close()
        }
        path(
            fill = SolidColor(Color.White),
            stroke = null,
            pathFillType = PathFillType.NonZero,
        ) {
            moveTo(10f, 11f)
            lineTo(38f, 11f)
            quadTo(40.5f, 11f, 40.5f, 13.5f)
            lineTo(40.5f, 29.5f)
            quadTo(40.5f, 32f, 38f, 32f)
            lineTo(10f, 32f)
            quadTo(7.5f, 32f, 7.5f, 29.5f)
            lineTo(7.5f, 13.5f)
            quadTo(7.5f, 11f, 10f, 11f)
            close()
        }
        path(
            fill = SolidColor(Color(0xFF1B5E20)),
            stroke = null,
            pathFillType = PathFillType.NonZero,
        ) {
            moveTo(12f, 14f)
            lineTo(36f, 14f)
            lineTo(36f, 28f)
            lineTo(12f, 28f)
            close()
        }
        path(
            fill = SolidColor(Color.White),
            stroke = null,
            pathFillType = PathFillType.NonZero,
        ) {
            moveTo(21f, 32f)
            lineTo(27f, 32f)
            lineTo(27f, 34.5f)
            lineTo(21f, 34.5f)
            close()
            moveTo(18f, 34.5f)
            lineTo(30f, 34.5f)
            lineTo(30f, 36.5f)
            lineTo(18f, 36.5f)
            close()
        }
        path(
            fill = SolidColor(Color(0xFFA5D6A7)),
            stroke = null,
            pathFillType = PathFillType.NonZero,
        ) {
            moveTo(24f, 22.5f)
            moveToRelative(-2f, 0f)
            arcToRelative(2f, 2f, 0f, true, true, 4f, 0f)
            arcToRelative(2f, 2f, 0f, true, true, -4f, 0f)
        }
        path(
            fill = SolidColor(Color.Transparent),
            stroke = SolidColor(Color(0xFFA5D6A7)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            pathFillType = PathFillType.NonZero,
        ) {
            moveTo(18.5f, 24f)
            quadTo(24f, 18.5f, 29.5f, 24f)
        }
        path(
            fill = SolidColor(Color.Transparent),
            stroke = SolidColor(Color(0xFFA5D6A7)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            pathFillType = PathFillType.NonZero,
        ) {
            moveTo(16f, 24f)
            quadTo(24f, 16f, 32f, 24f)
        }
    }.build()
