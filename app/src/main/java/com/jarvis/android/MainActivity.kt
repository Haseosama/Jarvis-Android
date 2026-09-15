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
import com.jarvis.android.ui.HudScreen
import com.jarvis.android.ui.MemoryScreen
import com.jarvis.android.ui.OnboardingScreen
import com.jarvis.android.ui.SettingsScreen
import com.jarvis.android.ui.theme.JarvisTheme

class MainActivity : ComponentActivity() {

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* handled reactively via hasMicPermission() on next recompose */ }

    private fun hasMicPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun requestNeededPermissions() {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms += Manifest.permission.POST_NOTIFICATIONS
        }
        requestPermissions.launch(perms.toTypedArray())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as JarvisApp).container

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
                                container.configStore.setApiKey(key)
                                hasKey = true
                                if (!hasMicPermission()) requestNeededPermissions()
                                navController.navigate("hud") { popUpTo("onboarding") { inclusive = true } }
                            }
                        )
                    }
                    composable("hud") {
                        val state by container.engine.state.collectAsState()
                        val log by container.engine.activityLog.collectAsState()
                        val confirm by container.confirmManager.pending.collectAsState()

                        HudScreen(
                            state = state,
                            activityLog = log,
                            confirmPending = confirm,
                            onStart = {
                                if (!hasMicPermission()) {
                                    requestNeededPermissions()
                                } else {
                                    ContextCompat.startForegroundService(
                                        this@MainActivity, Intent(this@MainActivity, JarvisVoiceService::class.java)
                                    )
                                }
                            },
                            onStop = {
                                stopService(Intent(this@MainActivity, JarvisVoiceService::class.java))
                            },
                            onToggleAwake = { container.engine.toggleAwake() },
                            onConfirm = { container.confirmManager.confirm() },
                            onCancelConfirm = { container.confirmManager.cancel() },
                            onOpenSettings = { navController.navigate("settings") },
                            onOpenMemory = { navController.navigate("memory") },
                        )
                    }
                    composable("settings") {
                        SettingsScreen(configStore = container.configStore, onBack = { navController.popBackStack() })
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
