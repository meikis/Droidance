// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.theme

import androidx.compose.ui.unit.dp

/** Icon size scale. Icons of the same role share a size so rows and bars line up. */
internal object AppIconSize {
    /** Top bar navigation and actions. */
    val Bar = 24.dp
    /** Leading icons in list rows, option sheets and footers. */
    val Leading = 20.dp
    /** Menu items and compact row actions such as overflow buttons. */
    val Action = 18.dp
    /** Chevrons, chip icons and other inline affordances. */
    val Inline = 16.dp
}
