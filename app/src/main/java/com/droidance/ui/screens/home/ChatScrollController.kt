// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.screens.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity

/** All chat gestures, including gestures originating inside an expanded log, suspend following. */
internal class ChatScrollController(
    initiallyFollowing: Boolean,
    private val atBottom: () -> Boolean,
    private val onUserScroll: () -> Unit = {}
) : NestedScrollConnection {
    var followingLatest by mutableStateOf(initiallyFollowing)
        private set
    private var userScrollActive = false

    fun readHistory() { followingLatest = false }
    fun followLatest() { followingLatest = true }

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (source == NestedScrollSource.UserInput && available.y != 0f) {
            // This runs before either list moves, so the layout follower cannot undo the drag.
            readHistory()
            if (!userScrollActive) {
                userScrollActive = true
                onUserScroll()
            }
        }
        return Offset.Zero
    }

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        // Only an explicit gesture past the bottom resumes following. Merely measuring an
        // expanded card at the bottom is not a request to stop reading its history.
        if (source == NestedScrollSource.UserInput && available.y < 0f && atBottom()) followLatest()
        return Offset.Zero
    }

    override suspend fun onPreFling(available: Velocity): Velocity {
        userScrollActive = false
        return Velocity.Zero
    }

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
        userScrollActive = false
        if (available.y < 0f && atBottom()) followLatest()
        return Velocity.Zero
    }
}
