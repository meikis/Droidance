package com.droidance.agent

import com.droidance.core.agent.*
import com.droidance.core.device.agent.*

import com.droidance.agent.*

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppForegroundState @Inject constructor() {
    private val mutable = MutableStateFlow(false)
    val foreground: StateFlow<Boolean> = mutable.asStateFlow()
    val isInForeground: Boolean get() = foreground.value
    internal fun update(value: Boolean) { mutable.value = value }
}
