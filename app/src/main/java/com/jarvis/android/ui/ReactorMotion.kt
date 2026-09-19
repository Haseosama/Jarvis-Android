package com.jarvis.android.ui

import com.jarvis.android.core.JarvisState

/** How the reactor core moves in a given state: a full breath every [periodMs], growing by [pulse]. */
internal data class ReactorMotion(val periodMs: Int, val pulse: Float, val followsVoice: Boolean = false)

internal fun reactorMotion(state: JarvisState): ReactorMotion = when (state) {
    JarvisState.ASLEEP, JarvisState.ERROR -> ReactorMotion(periodMs = 2_000, pulse = 0f)
    JarvisState.CONNECTING -> ReactorMotion(periodMs = 800, pulse = 0.05f)
    JarvisState.LISTENING -> ReactorMotion(periodMs = 2_600, pulse = 0.04f)
    JarvisState.THINKING -> ReactorMotion(periodMs = 1_000, pulse = 0.07f)
    JarvisState.SPEAKING -> ReactorMotion(periodMs = 700, pulse = 0.03f, followsVoice = true)
}

/** Extra growth of the inner core at full voice level while speaking. */
internal const val VOICE_GAIN = 0.35f
