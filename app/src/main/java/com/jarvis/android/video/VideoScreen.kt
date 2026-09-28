package com.jarvis.android.video

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * What a video does to the screen around it: turning the phone on its side makes it full screen (and upright again brings it back),
 * full screen asked for with the phone upright turns the picture on its side as YouTube does, the system bars hide in full screen
 * ([full]), the screen stays on while something plays, and Back leaves full screen first.
 */
@Composable
internal fun VideoScreenEffects(panel: VideoPanel?, video: VideoPanel.Video?, landscape: Boolean, full: Boolean) {
    val view = LocalView.current
    val activity = remember(view) { view.context.findActivity() }

    // only a turn of the phone counts, not the side it was on when the video appeared
    var lastLandscape by remember { mutableStateOf(landscape) }
    LaunchedEffect(landscape) {
        if (landscape != lastLandscape) {
            lastLandscape = landscape
            panel?.setFullscreen(landscape)
        }
    }

    val sideways = video?.fullscreen == true && !video.isSlideshow
    DisposableEffect(sideways, activity) {
        if (sideways && !landscape) activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose { if (sideways) activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }

    DisposableEffect(full, activity) {
        val window = activity?.window
        if (full && window != null) {
            WindowCompat.getInsetsController(window, view).apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose { if (full && window != null) WindowCompat.getInsetsController(window, view).show(WindowInsetsCompat.Type.systemBars()) }
    }

    val playing = video != null && !video.paused
    DisposableEffect(playing) {
        view.keepScreenOn = playing
        onDispose { view.keepScreenOn = false }
    }

    BackHandler(enabled = video?.fullscreen == true) { panel?.setFullscreen(false) }
}

internal fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}
