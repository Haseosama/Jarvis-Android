package com.jarvis.android.files

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The one file currently attached, in memory only (never written anywhere by the app). */
internal class AttachedFileStore {
    private val _current = MutableStateFlow<AttachedFile?>(null)
    val current: StateFlow<AttachedFile?> = _current.asStateFlow()

    fun attach(file: AttachedFile) {
        _current.value = file
    }

    fun clear() {
        _current.value = null
    }
}
