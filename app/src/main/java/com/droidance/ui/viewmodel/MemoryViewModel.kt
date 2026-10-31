// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.droidance.core.knowledge.memory.DurableMemoryDocument
import com.droidance.core.knowledge.memory.LongTermMemoryStore
import com.droidance.core.knowledge.memory.MemoryDisplaySaveResult
import com.droidance.core.knowledge.memory.ProfileStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class MemoryViewModel @Inject constructor(
    private val profileStore: ProfileStore,
    private val longTermMemoryStore: LongTermMemoryStore
) : ViewModel() {
    private val _userProfile = MutableStateFlow("")
    val userProfile: StateFlow<String> = _userProfile
    private val _longTermMemory = MutableStateFlow("")
    val longTermMemory: StateFlow<String> = _longTermMemory

    private val _isEditingUserProfile = MutableStateFlow(false)
    val isEditingUserProfile: StateFlow<Boolean> = _isEditingUserProfile
    private val _editingUserProfileText = MutableStateFlow("")
    val editingUserProfileText: StateFlow<String> = _editingUserProfileText
    private var editingUserProfileSource = ""

    private val _isEditingMemory = MutableStateFlow(false)
    val isEditingMemory: StateFlow<Boolean> = _isEditingMemory
    private val _editingMemoryText = MutableStateFlow("")
    val editingMemoryText: StateFlow<String> = _editingMemoryText
    private var editingLongTermMemorySource = ""

    private val _memoryDisplaySaveResult = MutableStateFlow<MemoryDisplaySaveResult?>(null)
    val memoryDisplaySaveResult: StateFlow<MemoryDisplaySaveResult?> = _memoryDisplaySaveResult

    init {
        load()
        viewModelScope.launch {
            profileStore.updates.collect { content ->
                _userProfile.value = DurableMemoryDocument.displayProjection(content)
            }
        }
    }

    fun load() {
        _userProfile.value = profileStore.readForDisplay()
        _longTermMemory.value = longTermMemoryStore.readForDisplay()
    }

    fun startEditUserProfile() {
        val snapshot = profileStore.editSnapshot()
        editingUserProfileSource = snapshot.sourceDocument
        _editingUserProfileText.value = snapshot.displayText
        _memoryDisplaySaveResult.value = null
        _isEditingUserProfile.value = true
    }

    fun onUserProfileTextChanged(text: String) {
        _editingUserProfileText.value = text
        _memoryDisplaySaveResult.value = null
    }

    fun saveUserProfile() {
        val result = profileStore.saveDisplayEdit(
            sourceDocument = editingUserProfileSource,
            editedDisplay = _editingUserProfileText.value
        )
        _memoryDisplaySaveResult.value = result.takeUnless { it == MemoryDisplaySaveResult.SAVED }
        if (result != MemoryDisplaySaveResult.SAVED) return
        _userProfile.value = profileStore.readForDisplay()
        _isEditingUserProfile.value = false
    }

    fun cancelEditUserProfile() {
        _memoryDisplaySaveResult.value = null
        _isEditingUserProfile.value = false
    }

    fun startEditMemory() {
        val snapshot = longTermMemoryStore.editSnapshot()
        editingLongTermMemorySource = snapshot.sourceDocument
        _editingMemoryText.value = snapshot.displayText
        _memoryDisplaySaveResult.value = null
        _isEditingMemory.value = true
    }

    fun onMemoryTextChanged(text: String) {
        _editingMemoryText.value = text
        _memoryDisplaySaveResult.value = null
    }

    fun saveMemory() {
        val result = longTermMemoryStore.saveDisplayEdit(
            sourceDocument = editingLongTermMemorySource,
            editedDisplay = _editingMemoryText.value
        )
        _memoryDisplaySaveResult.value = result.takeUnless { it == MemoryDisplaySaveResult.SAVED }
        if (result != MemoryDisplaySaveResult.SAVED) return
        _longTermMemory.value = longTermMemoryStore.readForDisplay()
        _isEditingMemory.value = false
    }

    fun cancelEditMemory() {
        _memoryDisplaySaveResult.value = null
        _isEditingMemory.value = false
    }
}
