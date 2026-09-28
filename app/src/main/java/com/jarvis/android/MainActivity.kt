package com.jarvis.android

import android.Manifest
import android.content.Intent
import kotlinx.coroutines.flow.first
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.jarvis.android.core.JarvisVoiceService
import com.jarvis.android.memory.ConfigStore
import com.jarvis.android.ui.ChatScreen
import com.jarvis.android.ui.HudScreen
import com.jarvis.android.ui.MemoryScreen
import com.jarvis.android.ui.OnboardingScreen
import com.jarvis.android.ui.SettingsScreen
import com.jarvis.android.ui.theme.JarvisTheme

class MainActivity : ComponentActivity() {

    /** Set right before requesting mic permission from the HUD's start button, so the
     * callback below knows whether to actually launch the service once granted — as
     * opposed to onboarding just asking for permission upfront without starting yet. */
    private var startServiceOnGrant = false

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val granted = results[Manifest.permission.RECORD_AUDIO] == true
        if (granted && startServiceOnGrant) {
            startJarvisService()
        }
        if (granted) (application as JarvisApp).container.syncVoiceService()
        startServiceOnGrant = false
    }

    private fun hasMicPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun requestNeededPermissions() {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms += Manifest.permission.POST_NOTIFICATIONS
        }
        requestPermissions.launch(perms.toTypedArray())
    }

    private fun startJarvisService() {
        ContextCompat.startForegroundService(
            this,
            Intent(this, JarvisVoiceService::class.java)
                .putExtra(JarvisVoiceService.EXTRA_TRIGGER, com.jarvis.android.core.SessionTrigger.APP_BUTTON.name),
        )
    }

    /** The app shrunk to its picture-in-picture window (a video playing when the user left). */
    private var pip by mutableStateOf(false)

    /** The main screen, where the video is, is the one shown: only then may leaving shrink the app to a video window. */
    private var onHud = false

    private fun videoWindowWanted(): Boolean =
        onHud && (application as JarvisApp).container.videoPanel.video.value != null &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    /** The video window's shape and its pause / play button; it opens by itself on leaving the app (Android 12 and later). */
    private fun videoWindowParams(): android.app.PictureInPictureParams {
        val video = (application as JarvisApp).container.videoPanel.video.value
        val paused = video?.paused == true
        val action = android.app.RemoteAction(
            android.graphics.drawable.Icon.createWithResource(this, if (paused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause),
            com.jarvis.android.i18n.tr(if (paused) "Lecture" else "Pause"), com.jarvis.android.i18n.tr(if (paused) "Lecture" else "Pause"),
            com.jarvis.android.video.VideoMedia.controlIntent(this, if (paused) com.jarvis.android.video.VideoMedia.DO_PLAY else com.jarvis.android.video.VideoMedia.DO_PAUSE),
        )
        return android.app.PictureInPictureParams.Builder()
            .setAspectRatio(android.util.Rational(16, 9))
            .setActions(listOf(action))
            .apply { if (Build.VERSION.SDK_INT >= 31) setAutoEnterEnabled(videoWindowWanted()).setSeamlessResizeEnabled(false) }
            .build()
    }

    private fun updateVideoWindow() {
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return
        try { setPictureInPictureParams(videoWindowParams()) } catch (e: Exception) { android.util.Log.w("JarvisPip", "params", e) }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // before Android 12 the window is asked for here; later, Android opens it by itself (setAutoEnterEnabled)
        if (Build.VERSION.SDK_INT < 31 && videoWindowWanted() && !isInPictureInPictureMode) {
            try { enterPictureInPictureMode(videoWindowParams()) } catch (_: Exception) {}
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        pip = isInPictureInPictureMode
        // the window swiped away (the app is left stopped behind it): the video goes with it
        if (!isInPictureInPictureMode && !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
            (application as JarvisApp).container.videoPanel.close()
        }
    }

    /** A launcher shortcut or the home-screen widget asks for a session: start it once a key and the mic permission exist. */
    private var openChatRequest by mutableStateOf(0)

    private fun handleLaunchIntent(intent: Intent?) {
        if (intent?.action == ACTION_OPEN_CHAT) {
            intent.action = null
            openChatRequest++
            return
        }
        if (intent?.action != ACTION_START_SESSION && intent?.action != Intent.ACTION_ASSIST) return
        intent.action = null
        val container = (application as JarvisApp).container
        if (!container.configStore.hasApiKey()) return
        // the assistant button (a steering wheel's, a long press) while a session listens over the radio or a video: the user wants to talk
        if (container.engine.sessionReady.value && container.videoPanel.soundOn) {
            container.videoPanel.openFloor()
            return
        }
        if (hasMicPermission()) {
            startJarvisService()
        } else {
            startServiceOnGrant = true
            requestNeededPermissions()
        }
    }

    /** Long-press on the launcher icon: "Parler à Jarvis". Dynamic because the package name differs per build type. */
    private fun publishShortcut() {
        try {
            val shortcut = androidx.core.content.pm.ShortcutInfoCompat.Builder(this, "talk")
                .setShortLabel(getString(R.string.shortcut_talk_short))
                .setLongLabel(getString(R.string.shortcut_talk_long))
                .setIcon(androidx.core.graphics.drawable.IconCompat.createWithResource(this, R.mipmap.ic_launcher))
                .setIntent(Intent(this, MainActivity::class.java).setAction(ACTION_START_SESSION))
                .build()
            androidx.core.content.pm.ShortcutManagerCompat.setDynamicShortcuts(this, listOf(shortcut))
        } catch (_: Exception) {
            // Shortcuts are a convenience; the widget and the app itself still work.
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunchIntent(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as JarvisApp).container
        container.syncVoiceService()
        // Read before the first frame so the interface does not flash in French.
        com.jarvis.android.i18n.Lang.load(this)
        if (savedInstanceState == null) handleLaunchIntent(intent)
        publishShortcut()

        setContent {
            val hue by container.configStore.themeHue.collectAsState(initial = 190f)
            JarvisTheme(hue = hue) {
              Surface(
                color = androidx.compose.ui.graphics.Color.Transparent,
                contentColor = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.fillMaxSize().background(com.jarvis.android.ui.theme.jarvisBackdrop(hue)),
              ) {
                val navController = rememberNavController()
                var hasKey by remember { mutableStateOf(container.configStore.hasApiKey()) }

                androidx.compose.runtime.LaunchedEffect(openChatRequest, hasKey) {
                    if (openChatRequest > 0 && hasKey) navController.navigate("chat") { launchSingleTop = true }
                }

                NavHost(navController = navController, startDestination = if (hasKey) "hud" else "onboarding") {
                    composable("onboarding") {
                        OnboardingScreen(
                            onSave = { key ->
                                if (!container.configStore.setApiKey(key)) return@OnboardingScreen
                                hasKey = true
                                if (!hasMicPermission()) requestNeededPermissions()
                                navController.navigate("hud") { popUpTo("onboarding") { inclusive = true } }
                            }
                        )
                    }
                    composable("hud") {
                        val shownVideo by container.videoPanel.video.collectAsState()
                        androidx.compose.runtime.DisposableEffect(Unit) {
                            onHud = true
                            updateVideoWindow()
                            onDispose { onHud = false; updateVideoWindow() }
                        }
                        androidx.compose.runtime.LaunchedEffect(shownVideo?.paused, shownVideo != null) {
                            updateVideoWindow()
                            // the video over (or closed) while the app is its video window: the window goes, rather than showing the app tiny
                            if (shownVideo == null && pip) moveTaskToBack(false)
                        }
                        val state by container.engine.state.collectAsState()
                        val log by container.engine.activityLog.collectAsState()
                        val conversation by container.engine.conversation.collectAsState()
                        val sessionReady by container.engine.sessionReady.collectAsState()
                        val videoSource by container.engine.videoSource.collectAsState()
                        val outputLevel by container.engine.outputLevel.collectAsState()
                        val confirm by container.confirmManager.pending.collectAsState()
                        val faceOn by container.configStore.avatarFace.collectAsState(initial = true)
                        // the last kept session, read again whenever a session begins or ends
                        val previous by androidx.compose.runtime.produceState<com.jarvis.android.memory.SavedSession?>(null, state, conversation.isEmpty()) {
                            value = if (conversation.isEmpty() && container.configStore.keepSessionTranscripts.first()) {
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { container.sessionTranscripts.latest() }
                            } else null
                        }

                        HudScreen(
                            previousSession = previous?.let { com.jarvis.android.memory.toMessages(it) } ?: emptyList(),
                            previousLabel = previous?.let { java.text.SimpleDateFormat("d MMM yyyy, HH:mm", java.util.Locale.getDefault()).format(java.util.Date(it.id)) } ?: "",
                            avatar = if (faceOn) container.avatar else null,
                            videoPanel = container.videoPanel,
                            state = state,
                            activityLog = log,
                            confirmPending = confirm,
                            onStart = {
                                if (!hasMicPermission()) {
                                    startServiceOnGrant = true
                                    requestNeededPermissions()
                                } else {
                                    startJarvisService()
                                }
                            },
                            onStop = {
                                startServiceOnGrant = false
                                container.engine.stop()
                                container.releaseVoiceService()
                            },
                            onToggleAwake = { container.engine.toggleAwake() },
                            onConfirm = { container.confirmManager.confirm() },
                            onCancelConfirm = { container.confirmManager.cancel() },
                            onOpenSettings = { navController.navigate("settings") },
                            onOpenMemory = { navController.navigate("memory") },
                            onOpenChat = { navController.navigate("chat") },
                            outputLevel = outputLevel,
                            videoSource = videoSource,
                            onVideoSource = { container.engine.setVideoSource(it) },
                            onCameraFrame = { container.engine.sendVideoFrame(it) },
                            conversation = conversation,
                            sessionReady = sessionReady,
                            onSendText = { container.engine.sendText(it) },
                            pip = pip,
                        )
                    }
                    composable("settings") {
                        SettingsScreen(
                            configStore = container.configStore,
                            onBack = { navController.popBackStack() },
                            onDeleteKey = {
                                startServiceOnGrant = false
                                container.engine.stop()
                                stopService(Intent(this@MainActivity, JarvisVoiceService::class.java))
                                val deleted = container.configStore.deleteApiKey()
                                if (deleted) {
                                    hasKey = false
                                    navController.navigate("onboarding") {
                                        popUpTo(navController.graph.id) { inclusive = true }
                                    }
                                }
                                deleted
                            },
                        )
                    }
                    composable("chat") {
                        val confirm by container.confirmManager.pending.collectAsState()
                        val restModel by container.configStore.restModel.collectAsState(initial = ConfigStore.DEFAULT_REST_MODEL)
                        ChatScreen(
                            chat = container.restChat,
                            modelName = restModel,
                            confirmPending = confirm,
                            onConfirm = { container.confirmManager.confirm() },
                            onCancelConfirm = { container.confirmManager.cancel() },
                            onBack = { navController.popBackStack() },
                        )
                    }
                    composable("memory") {
                        MemoryScreen(memoryManager = container.memoryManager, onBack = { navController.popBackStack() })
                    }
                }
              }
            }
        }
    }

    companion object {
        const val ACTION_START_SESSION = "com.jarvis.android.START_SESSION"
        const val ACTION_OPEN_CHAT = "com.jarvis.android.OPEN_CHAT"
    }
}
