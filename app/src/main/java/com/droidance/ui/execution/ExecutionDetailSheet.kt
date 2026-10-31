// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.execution

import androidx.compose.foundation.layout.ColumnScope
import com.droidance.ui.components.droidanceFloatingSurfaceColor
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.flow.first

/** Detail selection and its window share one lifetime, including interrupted dismissals. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ExecutionDetailSheet(
    onDismissRequest: () -> Unit,
    sheetState: SheetState = rememberExecutionDetailSheetState(),
    content: @Composable ColumnScope.() -> Unit
) {
    val dismiss by rememberUpdatedState(onDismissRequest)
    var dismissed by remember(sheetState) { mutableStateOf(false) }
    val dismissOnce = {
        if (!dismissed) {
            dismissed = true
            dismiss()
        }
    }
    LaunchedEffect(sheetState) {
        snapshotFlow {
            sheetState.currentValue == SheetValue.Hidden && sheetState.targetValue == SheetValue.Hidden
        }.first { it }
        // Hidden content still owns a fullscreen Dialog window until it leaves composition.
        dismissOnce()
    }
    ModalBottomSheet(onDismissRequest = dismissOnce, sheetState = sheetState,
        containerColor = droidanceFloatingSurfaceColor(), content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun rememberExecutionDetailSheetState(): SheetState {
    val density = LocalDensity.current
    return remember(density) {
        // Selection is transient: do not restore a Hidden state or leave an invisible startup window
        // waiting for ModalBottomSheet's separate show animation to complete.
        SheetState(skipPartiallyExpanded = true, density = density, initialValue = SheetValue.Expanded)
    }
}
