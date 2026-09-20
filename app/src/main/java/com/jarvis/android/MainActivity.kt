package com.jarvis.android

import android.Manifest
import android.content.Intent
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as JarvisApp).container
        container.syncVoiceService()

        setContent {
            val hue by container.configStore.themeHue.collectAsState(initial = 190f)
            JarvisTheme(hue = hue) {
              Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                val navController = rememberNavController()
                var hasKey by remember { mutableStateOf(container.configStore.hasApiKey()) }

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
                        val state by container.engine.state.collectAsState()
                        val log by container.engine.activityLog.collectAsState()
                        val conversation by container.engine.conversation.collectAsState()
                        val sessionReady by container.engine.sessionReady.collectAsState()
                        val videoSource by container.engine.videoSource.collectAsState()
                        val outputLevel by container.engine.outputLevel.collectAsState()
                        val confirm by container.confirmManager.pending.collectAsState()

                        HudScreen(
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
}
