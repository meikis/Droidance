// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.droidance.R
import com.droidance.ui.icons.DroidanceIcons
import com.droidance.ui.theme.AppShapes
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.WeekFields

@Composable
internal fun DroidanceDatePicker(
    date: LocalDate,
    onDismiss: () -> Unit,
    onSelect: (LocalDate) -> Unit
) {
    var inputMode by rememberSaveable { mutableStateOf(false) }
    var dateText by rememberSaveable { mutableStateOf(date.toString()) }
    var monthText by rememberSaveable { mutableStateOf(YearMonth.from(date).toString()) }
    val selected = runCatching { LocalDate.parse(dateText) }.getOrNull()
    val month = YearMonth.parse(monthText)
    val locale = LocalConfiguration.current.locales[0]
    val firstDay = WeekFields.of(locale).firstDayOfWeek
    val offset = (month.atDay(1).dayOfWeek.value - firstDay.value + 7) % 7
    DroidanceDialog(onDismissRequest = onDismiss,
        title = stringResource(R.string.ui_choose_date),
        confirmLabel = stringResource(R.string.common_save), confirmEnabled = selected != null,
        onConfirm = { selected?.let(onSelect) }, dismissLabel = stringResource(R.string.common_cancel), maxWidth = 420.dp) {
        if (inputMode) {
            OutlinedTextField(dateText, { dateText = it }, modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.ui_date_format)) }, singleLine = true,
                isError = selected == null, colors = droidanceFieldColors(), shape = AppShapes.Control,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii))
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { monthText = month.minusMonths(1).toString() }) {
                    Icon(DroidanceIcons.ChevronRight, stringResource(R.string.ui_previous_month), modifier = Modifier.rotate(180f))
                }
                Text(month.format(DateTimeFormatter.ofPattern(stringResource(R.string.ui_month_pattern), locale)),
                    modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                IconButton(onClick = { monthText = month.plusMonths(1).toString() }) {
                    Icon(DroidanceIcons.ChevronRight, stringResource(R.string.ui_next_month))
                }
            }
            Row(Modifier.fillMaxWidth()) {
                repeat(7) { index ->
                    Text(firstDay.plus(index.toLong()).getDisplayName(TextStyle.NARROW, locale),
                        modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Column(Modifier.fillMaxWidth()) {
                repeat((offset + month.lengthOfMonth() + 6) / 7) { week ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        repeat(7) { column ->
                            val day = week * 7 + column - offset + 1
                            if (day !in 1..month.lengthOfMonth()) Spacer(Modifier.weight(1f).aspectRatio(1f))
                            else {
                                val value = month.atDay(day)
                                val active = value == selected
                                TextButton(onClick = { dateText = value.toString() },
                                    modifier = Modifier.weight(1f).aspectRatio(1f).semantics {
                                        this.selected = active
                                        contentDescription = value.format(DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.FULL).withLocale(locale))
                                    }, shape = CircleShape,
                                    contentPadding = PaddingValues(0.dp),
                                    colors = ButtonDefaults.textButtonColors(
                                        containerColor = if (active) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent,
                                        contentColor = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)) {
                                    Text(day.toString(), style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                }
            }
        }
        TextButton(onClick = {
            if (inputMode && selected != null) monthText = YearMonth.from(selected).toString()
            inputMode = !inputMode
        }, modifier = Modifier.align(Alignment.End)) {
            Text(stringResource(if (inputMode) R.string.ui_date_calendar else R.string.ui_date_input))
        }
    }
}

@Composable
internal fun DroidanceTimePicker(
    hour: Int,
    minute: Int,
    onDismiss: () -> Unit,
    onSelect: (Int, Int) -> Unit
) {
    var hours by rememberSaveable { mutableStateOf(hour.toString().padStart(2, '0')) }
    var minutes by rememberSaveable { mutableStateOf(minute.toString().padStart(2, '0')) }
    val selectedHour = hours.toIntOrNull()?.takeIf { it in 0..23 }
    val selectedMinute = minutes.toIntOrNull()?.takeIf { it in 0..59 }
    val valid = selectedHour != null && selectedMinute != null
    DroidanceDialog(onDismissRequest = onDismiss,
        title = stringResource(R.string.ui_choose_time), confirmLabel = stringResource(R.string.common_save),
        confirmEnabled = valid,
        onConfirm = { if (valid) onSelect(requireNotNull(selectedHour), requireNotNull(selectedMinute)) },
        dismissLabel = stringResource(R.string.common_cancel)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(hours, { hours = it.filter(Char::isDigit).take(2) }, modifier = Modifier.weight(1f),
                label = { Text(stringResource(R.string.ui_hour)) }, singleLine = true,
                isError = selectedHour == null, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                textStyle = MaterialTheme.typography.titleLarge, colors = droidanceFieldColors(), shape = AppShapes.Control)
            Text(":", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(minutes, { minutes = it.filter(Char::isDigit).take(2) }, modifier = Modifier.weight(1f),
                label = { Text(stringResource(R.string.ui_minute)) }, singleLine = true,
                isError = selectedMinute == null, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                textStyle = MaterialTheme.typography.titleLarge, colors = droidanceFieldColors(), shape = AppShapes.Control)
        }
    }
}
