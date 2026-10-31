// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.screens.home

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp

internal val ConversationActionGlyphSize = 18.dp
internal val ConversationActionControlSize = 36.dp

internal val NewConversationIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "NewConversation",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(
            fill = SolidColor(Color.Transparent),
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.5f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(13f, 4f)
            horizontalLineTo(6.5f)
            curveTo(5.12f, 4f, 4f, 5.12f, 4f, 6.5f)
            verticalLineTo(17.5f)
            curveTo(4f, 18.88f, 5.12f, 20f, 6.5f, 20f)
            horizontalLineTo(17.5f)
            curveTo(18.88f, 20f, 20f, 18.88f, 20f, 17.5f)
            verticalLineTo(11f)

            moveTo(9.5f, 15.2f)
            lineTo(10.12f, 12.72f)
            lineTo(18.32f, 4.52f)
            curveTo(18.71f, 4.13f, 19.34f, 4.13f, 19.73f, 4.52f)
            lineTo(20.48f, 5.27f)
            curveTo(20.87f, 5.66f, 20.87f, 6.29f, 20.48f, 6.68f)
            lineTo(12.28f, 14.88f)
            lineTo(9.5f, 15.2f)
        }
    }.build()
}

@Composable
internal fun ConversationActionGlyph(
    @DrawableRes iconRes: Int,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current
) {
    Icon(
        painter = painterResource(iconRes),
        contentDescription = contentDescription,
        modifier = modifier.then(Modifier.size(ConversationActionGlyphSize)),
        tint = tint
    )
}

@Composable
internal fun ConversationActionGlyph(
    imageVector: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current
) {
    Icon(
        imageVector = imageVector,
        contentDescription = contentDescription,
        modifier = modifier.then(Modifier.size(ConversationActionGlyphSize)),
        tint = tint
    )
}
