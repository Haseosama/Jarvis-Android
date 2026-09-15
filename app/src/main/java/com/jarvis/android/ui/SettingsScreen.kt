package com.jarvis.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.jarvis.android.memory.ConfigStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(configStore: ConfigStore, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()

    val assistantName by configStore.assistantName.collectAsState(initial = "JARVIS")
    val userName by configStore.userName.collectAsState(initial = "")
    val voice by configStore.voice.collectAsState(initial = "Puck")
    val model by configStore.model.collectAsState(initial = ConfigStore.DEFAULT_MODEL)
    val hue by configStore.themeHue.collectAsState(initial = 190f)
    val wakeWordEnabled by configStore.wakeWordEnabled.collectAsState(initial = false)

    var assistantNameField by remember(assistantName) { mutableStateOf(assistantName) }
    var userNameField by remember(userName) { mutableStateOf(userName) }
    var modelField by remember(model) { mutableStateOf(model) }
    var apiKeyField by remember { mutableStateOf("") }
    var voiceMenuOpen by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, null) } },
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
        ) {
            Text("Identity", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = assistantNameField,
                onValueChange = { assistantNameField = it; scope.launch { configStore.setAssistantName(it) } },
                label = { Text("Assistant name") },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            OutlinedTextField(
                value = userNameField,
                onValueChange = { userNameField = it; scope.launch { configStore.setUserName(it) } },
                label = { Text("Your name") },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )

            Spacer(Modifier.height(24.dp))
            Text("Voice", style = MaterialTheme.typography.titleMedium)
            ExposedDropdownMenuBox(
                expanded = voiceMenuOpen,
                onExpandedChange = { voiceMenuOpen = it },
                modifier = Modifier.padding(top = 8.dp),
            ) {
                OutlinedTextField(
                    value = voice,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Gemini voice") },
                    modifier = Modifier.menuAnchor().fillMaxWidth(),
                )
                ExposedDropdownMenu(expanded = voiceMenuOpen, onDismissRequest = { voiceMenuOpen = false }) {
                    ConfigStore.AVAILABLE_VOICES.forEach { v ->
                        DropdownMenuItem(text = { Text(v) }, onClick = {
                            voiceMenuOpen = false
                            scope.launch { configStore.setVoice(v) }
                        })
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            Text("Theme", style = MaterialTheme.typography.titleMedium)
            Slider(
                value = hue,
                onValueChange = { scope.launch { configStore.setThemeHue(it) } },
                valueRange = 0f..360f,
                modifier = Modifier.padding(top = 8.dp),
            )

            Spacer(Modifier.height(24.dp))
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("Wake word (\"Hey Jarvis\")", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Switch(checked = wakeWordEnabled, onCheckedChange = { scope.launch { configStore.setWakeWordEnabled(it) } })
            }
            Text(
                "Uses Android's on-device speech recognizer as an approximation — see README for the caveat vs. the desktop app's fully offline detector.",
                style = MaterialTheme.typography.bodySmall,
            )

            Spacer(Modifier.height(24.dp))
            Text("Advanced", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = modelField,
                onValueChange = { modelField = it; scope.launch { configStore.setModel(it) } },
                label = { Text("Live model") },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            OutlinedTextField(
                value = apiKeyField,
                onValueChange = { apiKeyField = it },
                label = { Text("Update Gemini API key") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            Button(
                onClick = { if (apiKeyField.isNotBlank()) { configStore.setApiKey(apiKeyField.trim()); apiKeyField = "" } },
                enabled = apiKeyField.isNotBlank(),
                modifier = Modifier.padding(top = 8.dp),
            ) { Text("Save key") }

            Spacer(Modifier.height(16.dp))
            Text(
                "Diagnostic: calls the plain REST API (generateContent) with the currently saved key — separate from the Live/voice WebSocket path, to tell whether the key itself works at all.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(
                onClick = {
                    testing = true
                    testResult = null
                    scope.launch {
                        testResult = testApiKey(configStore)
                        testing = false
                    }
                },
                enabled = !testing,
                modifier = Modifier.padding(top = 8.dp),
            ) { Text(if (testing) "Testing…" else "Test saved key (REST)") }
            testResult?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

/**
 * One-shot REST call to `generateContent` — deliberately NOT the Live WebSocket
 * path — so a failure here means the key/project itself is the problem, and a
 * success here while the Live path still fails means the key works but lacks
 * Live API access specifically. Never logs or displays the key.
 */
private suspend fun testApiKey(configStore: ConfigStore): String = withContext(Dispatchers.IO) {
    val key = configStore.getApiKey()
    if (key.isNullOrBlank()) return@withContext "No key saved yet."
    val client = OkHttpClient()
    val body = """{"contents":[{"parts":[{"text":"Say OK"}]}]}"""
        .toRequestBody("application/json".toMediaType())
    val request = Request.Builder()
        .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.6-flash:generateContent?key=$key")
        .post(body)
        .build()
    try {
        client.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (resp.isSuccessful) {
                "REST OK (HTTP ${resp.code}) — the key works for the standard API."
            } else {
                "REST FAILED (HTTP ${resp.code}): ${text.take(300)}"
            }
        }
    } catch (e: Exception) {
        "REST call error: ${e.message}"
    }
}
