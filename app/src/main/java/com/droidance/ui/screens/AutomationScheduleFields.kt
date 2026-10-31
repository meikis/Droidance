// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.screens

import com.droidance.ui.components.droidanceFieldColors
import com.droidance.ui.components.DroidanceDatePicker
import com.droidance.ui.components.DroidanceTimePicker
import com.droidance.ui.theme.AppShapes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import com.droidance.ui.icons.DroidanceIcons
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.droidance.R
import com.droidance.automation.*
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

@Composable
internal fun AutomationScheduleFields(
    initial: AutomationSchedule?,
    onChange: (AutomationSchedule?, Long?) -> Unit
) {
    val initialTime = rememberSaveable(initial) { initial?.runAtMs?.takeIf { it > 0L } ?: (System.currentTimeMillis() + 300_000L) }
    var cadence by rememberSaveable(initial) { mutableStateOf(initial?.cadence ?: AutomationCadence.ONCE) }
    var runAt by rememberSaveable(initial) { mutableStateOf(initialTime) }
    var hour by rememberSaveable(initial) { mutableStateOf(initial?.hour ?: 9) }
    var minute by rememberSaveable(initial) { mutableStateOf(initial?.minute ?: 0) }
    var days by rememberSaveable(initial, stateSaver = Saver(save = { it.toList() }, restore = { it.toSet() })) { mutableStateOf(initial?.daysOfWeek.orEmpty()) }
    var zoneText by rememberSaveable(initial) { mutableStateOf(initial?.timeZoneId ?: ZoneId.systemDefault().id) }
    var relative by rememberSaveable(initial) { mutableStateOf(false) }
    var delayText by rememberSaveable(initial) { mutableStateOf("5") }
    var intervalText by rememberSaveable(initial) { mutableStateOf((initial?.intervalMinutes?.takeIf { it > 0L } ?: 5L).toString()) }
    var intervalUnit by rememberSaveable(initial) { mutableStateOf(1L) }
    var endMode by rememberSaveable(initial) { mutableStateOf(if ((initial?.endAtMs ?: 0L) > 0L) 1 else if ((initial?.maxOccurrences ?: 0L) > 0L) 2 else 0) }
    var endAt by rememberSaveable(initial) { mutableStateOf(initial?.endAtMs?.takeIf { it > 0L } ?: initialTime + 86_400_000L) }
    var countText by rememberSaveable(initial) { mutableStateOf((initial?.maxOccurrences?.takeIf { it > 0L } ?: 10L).toString()) }
    var policy by rememberSaveable(initial) { mutableStateOf(initial?.missedPolicy ?: AutomationMissedPolicy.RUN_ONCE) }
    var touched by rememberSaveable(initial) { mutableStateOf(initial != null) }
    var advanced by rememberSaveable(initial) { mutableStateOf((initial?.endAtMs ?: 0L) > 0L || (initial?.maxOccurrences ?: 0L) > 0L || initial?.missedPolicy == AutomationMissedPolicy.SKIP) }
    val zone = runCatching { ZoneId.of(zoneText) }.getOrNull()
    val interval = runCatching { Math.multiplyExact(intervalText.toLong(), intervalUnit) }.getOrNull()
    val delay = if (relative) delayText.toLongOrNull()?.takeIf { it > 0L && it <= 5256000L } else null
    val candidate = runCatching {
        require(touched && zone != null)
        require(!relative || delay != null)
        require(cadence != AutomationCadence.INTERVAL || interval != null)
        val limit = if (endMode == 2 && cadence != AutomationCadence.ONCE) requireNotNull(countText.toLongOrNull()).also { require(it > 0L) } else 0L
        AutomationSchedule(cadence, hour, minute, if (cadence == AutomationCadence.WEEKLY) days else emptySet(), zone.id,
            runAtMs = if (cadence in setOf(AutomationCadence.ONCE, AutomationCadence.INTERVAL)) runAt else 0L,
            intervalMinutes = if (cadence == AutomationCadence.INTERVAL) requireNotNull(interval) else 0L,
            endAtMs = if (endMode == 1 && cadence != AutomationCadence.ONCE) endAt else 0L,
            maxOccurrences = limit, missedPolicy = policy)
    }.getOrNull()
    val currentOnChange by rememberUpdatedState(onChange)
    LaunchedEffect(candidate, delay, relative) { currentOnChange(candidate, if (relative) delay else null) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.automation_schedule_label), style = MaterialTheme.typography.labelLarge)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(AutomationCadence.ONCE to R.string.automation_once, AutomationCadence.DAILY to R.string.automation_daily,
                AutomationCadence.WEEKLY to R.string.automation_weekly, AutomationCadence.INTERVAL to R.string.automation_interval).forEach { (value, label) ->
                FilterChip(selected = cadence == value, onClick = { cadence = value; touched = true; relative = false }, label = { Text(stringResource(label)) })
            }
        }
        if (cadence in setOf(AutomationCadence.ONCE, AutomationCadence.INTERVAL)) {
            if (cadence == AutomationCadence.INTERVAL) Text(stringResource(R.string.automation_first_run),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(!relative, { relative = false; touched = true }, label = { Text(stringResource(R.string.automation_at_time)) })
                FilterChip(relative, { relative = true; touched = true }, label = { Text(stringResource(R.string.automation_after_enable)) })
            }
            if (relative) {
                OutlinedTextField(delayText, { delayText = it; touched = true }, label = { Text(stringResource(R.string.automation_delay_minutes)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth(), textStyle = MaterialTheme.typography.bodyMedium, colors = droidanceFieldColors(), shape = AppShapes.Control)
            } else if (zone != null) {
                ScheduleDateTimeField(runAt, zone) { runAt = it; touched = true }
            }
        }
        if (cadence == AutomationCadence.INTERVAL) {
            OutlinedTextField(intervalText, { intervalText = it; touched = true }, label = { Text(stringResource(R.string.automation_interval_value)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth(), textStyle = MaterialTheme.typography.bodyMedium, colors = droidanceFieldColors(), shape = AppShapes.Control)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(1L to R.string.automation_minutes, 60L to R.string.automation_hours, 1440L to R.string.automation_days).forEach { (value, label) ->
                    FilterChip(intervalUnit == value, { intervalUnit = value; touched = true }, label = { Text(stringResource(label)) })
                }
            }

        }
        if (cadence in setOf(AutomationCadence.DAILY, AutomationCadence.WEEKLY)) {
            var choosingTime by rememberSaveable { mutableStateOf(false) }
            TextButton(onClick = { choosingTime = true }) { Text(String.format(Locale.getDefault(), "%02d:%02d", hour, minute)) }
            if (choosingTime) DroidanceTimePicker(hour, minute, onDismiss = { choosingTime = false }) { h, m ->
                hour = h; minute = m; touched = true; choosingTime = false
            }
        }
        if (cadence == AutomationCadence.WEEKLY) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DayOfWeek.entries.forEach { day ->
                    FilterChip(day.value in days, { days = if (day.value in days) days - day.value else days + day.value; touched = true },
                        label = { Text(day.getDisplayName(TextStyle.SHORT, Locale.getDefault())) })
                }
            }
        }
        TextButton(onClick = { advanced = !advanced }) {
            Text(stringResource(R.string.automation_advanced))
            Spacer(Modifier.width(4.dp))
            Icon(if (advanced) DroidanceIcons.ChevronUp else DroidanceIcons.ChevronDown, null, Modifier.size(16.dp))
        }
        if (advanced) {
            if (cadence != AutomationCadence.ONCE) {
                Text(stringResource(R.string.automation_end_condition), style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(R.string.automation_no_end, R.string.automation_end_date, R.string.automation_end_count).forEachIndexed { index, label ->
                        FilterChip(endMode == index, { endMode = index; touched = true }, label = { Text(stringResource(label)) })
                    }
                }
                if (endMode == 1 && zone != null) ScheduleDateTimeField(endAt, zone) { endAt = it; touched = true }
                if (endMode == 2) OutlinedTextField(countText, { countText = it; touched = true }, label = { Text(stringResource(R.string.automation_trigger_limit)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth(), textStyle = MaterialTheme.typography.bodyMedium, colors = droidanceFieldColors(), shape = AppShapes.Control)
            }
            Text(stringResource(R.string.automation_missed_policy), style = MaterialTheme.typography.labelLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(AutomationMissedPolicy.RUN_ONCE to R.string.automation_catch_up, AutomationMissedPolicy.SKIP to R.string.automation_skip).forEach { (value, label) ->
                    FilterChip(policy == value, { policy = value }, label = { Text(stringResource(label)) })
                }
            }
            OutlinedTextField(zoneText, { zoneText = it; touched = true }, label = { Text(stringResource(R.string.automation_zone_label)) },
                isError = zone == null, textStyle = MaterialTheme.typography.bodyMedium, colors = droidanceFieldColors(), shape = AppShapes.Control, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        if (candidate == null && touched) Text(stringResource(R.string.automation_schedule_invalid), color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun ScheduleDateTimeField(value: Long, zone: ZoneId, onChange: (Long) -> Unit) {
    val local = Instant.ofEpochMilli(value).atZone(zone)
    var choosingDate by rememberSaveable { mutableStateOf(false) }
    var choosingTime by rememberSaveable { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { choosingDate = true }, modifier = Modifier.weight(1f)) {
            Text(local.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)), style = MaterialTheme.typography.bodyMedium)
        }
        TextButton(onClick = { choosingTime = true }) {
            Text(local.format(DateTimeFormatter.ofPattern("HH:mm")), style = MaterialTheme.typography.bodyMedium)
        }
    }
    if (choosingDate) DroidanceDatePicker(local.toLocalDate(), onDismiss = { choosingDate = false }) { date ->
        onChange(date.atTime(local.toLocalTime()).atZone(zone).toInstant().toEpochMilli()); choosingDate = false
    }
    if (choosingTime) DroidanceTimePicker(local.hour, local.minute, onDismiss = { choosingTime = false }) { hour, minute ->
        onChange(local.toLocalDate().atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()); choosingTime = false
    }
}
