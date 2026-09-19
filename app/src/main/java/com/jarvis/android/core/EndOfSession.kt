package com.jarvis.android.core

/**
 * Tracks a "stop the session" request made through the `end_session` tool. The session must not
 * be cut while the model is still saying goodbye: the request only takes effect once the tool
 * answer has been sent back and the model's next turn is complete.
 */
internal class EndOfSession {
    var pending = false
        private set
    private var armed = false

    fun request() {
        pending = true
    }

    /** The tool answer went out: from now on the next completed turn is the goodbye. */
    fun toolResponseSent() {
        if (pending) armed = true
    }

    /** True when a completed turn should now end the session. */
    fun turnCompleted(): Boolean = pending && armed

    fun reset() {
        pending = false
        armed = false
    }
}
