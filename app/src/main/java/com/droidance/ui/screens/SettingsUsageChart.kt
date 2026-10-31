// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.screens

import androidx.compose.foundation.background
import com.droidance.ui.theme.AppShapes
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.droidance.R
import com.droidance.ui.viewmodel.UsageRange

@Composable
internal fun UsageRangeSelector(
    selected: UsageRange,
    onSelect: (UsageRange) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        UsageRange.entries.forEach { range ->
            val isSelected = range == selected
            val label = stringResource(
                when (range) {
                    UsageRange.SEVEN_DAYS -> R.string.settings_usage_7_days
                    UsageRange.THIRTY_DAYS -> R.string.settings_usage_30_days
                    UsageRange.ALL -> R.string.settings_usage_all
                }
            )
            Surface(
                onClick = { onSelect(range) },
                modifier = Modifier.weight(1f),
                shape = AppShapes.Control,
                color = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surface,
                contentColor = if (isSelected) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface
            ) {
                Text(
                    text = label,
                    modifier = Modifier.padding(vertical = 9.dp),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}

/** 每日总用量柱形图：上方为总用量实心柱，下方为缓存命中率斜线网格。 */
@Composable
internal fun UsageBarChart(
    daily: List<com.droidance.ui.viewmodel.DailyTokenUsage>,
    modifier: Modifier = Modifier
) {
    val maxTotal = daily.maxOfOrNull { it.total } ?: 0L
    val totalColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)
    val cacheFillColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)
    val cacheHatchColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.56f)
    val cacheEdgeColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.34f)
    val gridColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
    val labelColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)

    Column(modifier = modifier) {
        // 图例 + 峰值
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ChartLegendSolid(totalColor, stringResource(R.string.settings_total_tokens))
            Spacer(Modifier.width(12.dp))
            ChartLegendHatch(
                fillColor = cacheFillColor,
                hatchColor = cacheHatchColor,
                label = stringResource(R.string.settings_cache_hit_rate)
            )
            Spacer(Modifier.weight(1f))
            if (maxTotal > 0L) {
                Text(
                    stringResource(R.string.settings_usage_peak, formatTokens(maxTotal)),
                    style = MaterialTheme.typography.labelSmall,
                    color = labelColor
                )
            }
        }
        Spacer(Modifier.height(10.dp))

        if (maxTotal <= 0L) {
            Box(
                modifier = Modifier.fillMaxWidth().height(120.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    stringResource(R.string.settings_usage_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = labelColor
                )
            }
        } else {
            androidx.compose.foundation.Canvas(
                modifier = Modifier.fillMaxWidth().height(120.dp)
            ) {
                val n = daily.size
                if (n == 0) return@Canvas
                val gap = when {
                    n <= 7 -> size.width * 0.04f
                    n <= 30 -> size.width * 0.01f
                    else -> 0f
                }
                val barW = ((size.width - gap * (n - 1)) / n).coerceAtLeast(0.5f)
                val radius = 3.dp.toPx()

                listOf(0.25f, 0.5f, 0.75f).forEach { f ->
                    val y = size.height * (1 - f)
                    drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
                }
                drawLine(
                    totalColor.copy(alpha = 0.78f),
                    Offset(0f, size.height),
                    Offset(size.width, size.height),
                    strokeWidth = 1.dp.toPx()
                )

                daily.forEachIndexed { i, usage ->
                    if (usage.total <= 0L) return@forEachIndexed
                    val x = i * (barW + gap)
                    val hTotal = size.height * usage.total.toFloat() / maxTotal.toFloat()
                    val top = size.height - hTotal
                    drawRoundRect(
                        color = totalColor,
                        topLeft = Offset(x, top),
                        size = androidx.compose.ui.geometry.Size(barW, hTotal),
                        cornerRadius = CornerRadius(radius)
                    )

                    val cacheRate = usage.cacheHitRate?.coerceIn(0f, 1f)
                    val hCache = hTotal * (cacheRate ?: 0f)
                    if (cacheRate != null && hCache > 0f) {
                        val cacheTop = top + hTotal - hCache
                        val cacheBottom = size.height
                        val cacheRadius = minOf(radius, barW / 2f, hCache)
                        val cachePath = Path().apply {
                            moveTo(x, cacheTop)
                            lineTo(x + barW, cacheTop)
                            lineTo(x + barW, cacheBottom - cacheRadius)
                            quadraticTo(x + barW, cacheBottom, x + barW - cacheRadius, cacheBottom)
                            lineTo(x + cacheRadius, cacheBottom)
                            quadraticTo(x, cacheBottom, x, cacheBottom - cacheRadius)
                            close()
                        }
                        drawPath(cachePath, cacheFillColor)
                        clipPath(cachePath) {
                            val hatchSpacing = 7.dp.toPx()
                            var start = x - hCache
                            while (start <= x + barW + hCache) {
                                drawLine(
                                    cacheHatchColor,
                                    Offset(start, cacheBottom),
                                    Offset(start + hCache, cacheTop),
                                    strokeWidth = 1.15.dp.toPx()
                                )
                                start += hatchSpacing
                            }
                        }
                        drawLine(
                            cacheEdgeColor,
                            Offset(x + 0.5.dp.toPx(), cacheTop),
                            Offset(x + barW - 0.5.dp.toPx(), cacheTop),
                            strokeWidth = 1.dp.toPx()
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(daily.firstOrNull()?.date?.takeLast(5).orEmpty(), style = MaterialTheme.typography.labelSmall, color = labelColor)
                Text(daily.lastOrNull()?.date?.takeLast(5).orEmpty(), style = MaterialTheme.typography.labelSmall, color = labelColor)
            }
        }
    }
}

@Composable
private fun ChartLegendSolid(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(color, RoundedCornerShape(2.dp))
        )
        Spacer(Modifier.width(5.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
        )
    }
}

@Composable
private fun ChartLegendHatch(
    fillColor: Color,
    hatchColor: Color,
    label: String
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(fillColor)
        ) {
            androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                val spacing = 4.dp.toPx()
                var start = -size.height
                while (start <= size.width) {
                    drawLine(
                        hatchColor,
                        Offset(start, size.height),
                        Offset(start + size.height, 0f),
                        strokeWidth = 1.dp.toPx()
                    )
                    start += spacing
                }
            }
        }
        Spacer(Modifier.width(5.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
        )
    }
}
