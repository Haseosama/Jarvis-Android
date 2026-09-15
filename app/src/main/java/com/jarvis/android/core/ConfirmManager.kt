package com.jarvis.android.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class PendingConfirmation(val actionLabel: String, val detail: String)

/**
 * Gate for irreversible actions — the Android equivalent of Mark-LIII's
 * `core/confirm.py`. The model can *request* a confirmation but the token is
 * only ever produced by a human tapping CONFIRM in the UI; a tool handler
 * awaits [request] and gets back true/false, never a value the model supplied.
 */
class ConfirmManager {
    private val _pending = MutableStateFlow<PendingConfirmation?>(null)
    val pending: StateFlow<PendingConfirmation?> = _pending

    private var waiting: CompletableDeferred<Boolean>? = null

    suspend fun request(actionLabel: String, detail: String): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        waiting = deferred
        _pending.value = PendingConfirmation(actionLabel, detail)
        val result = deferred.await()
        _pending.value = null
        return result
    }

    fun confirm() {
        waiting?.complete(true)
        waiting = null
    }

    fun cancel() {
        waiting?.complete(false)
        waiting = null
    }
}
