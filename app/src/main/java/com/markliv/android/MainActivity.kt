package com.markliv.android

import android.Manifest
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private lateinit var tts: TtsPlayer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TtsPlayer(applicationContext)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                MessageScreen(tts)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        tts.shutdown()
    }
}

@Composable
private fun MessageScreen(tts: TtsPlayer) {
    val context = LocalContext.current.applicationContext
    val store = remember(context) { SecureStore(context) }
    var storageBusy by remember { mutableStateOf(true) }
    var storageStatus by remember { mutableStateOf(R.string.key_restoring) }
    var apiKey by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var models by remember { mutableStateOf(emptyList<String>()) }
    var menuOpen by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var chatError by remember { mutableStateOf("") }
    var chatVersion by remember { mutableStateOf(0) }
    val scrollState = rememberScrollState()
    val recorder = remember { Recorder() }
    var recording by remember { mutableStateOf(false) }
    val client = remember { GeminiClient() }
    val session = remember { ChatSession(client) }
    val scope = rememberCoroutineScope()
    val unexpectedError = stringResource(R.string.unexpected_error)
    val voiceError = stringResource(R.string.voice_error)
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            try {
                recorder.start(context)
                recording = true
            } catch (_: RecorderException) {
                failed = true
                chatError = voiceError
            }
        } else {
            failed = true
            chatError = voiceError
        }
    }

    fun handleGeminiError(error: Exception, fallback: String, retryText: String?) {
        failed = true
        chatError = (error as? GeminiException)?.message ?: unexpectedError
        if (!retryText.isNullOrEmpty()) draft = retryText
    }

    LaunchedEffect(store) {
        try {
            val restored = withContext(Dispatchers.IO) { store.read() }
            apiKey = restored.orEmpty()
            storageStatus = if (restored == null) R.string.key_not_saved else R.string.key_restored
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            storageStatus = R.string.key_restore_error
        } finally {
            storageBusy = false
        }
    }

    LaunchedEffect(chatVersion) {
        if (chatVersion > 0) {
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }

    DisposableEffect(Unit) {
        onDispose { recorder.stop() }
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(R.string.prototype_notice))
            OutlinedTextField(
                value = apiKey,
                onValueChange = {
                    apiKey = it
                    storageStatus = R.string.key_changed
                },
                label = { Text(stringResource(R.string.api_key)) },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                singleLine = true,
                enabled = !storageBusy && !loading && !recording,
                modifier = Modifier.fillMaxWidth()
            )
            Text(stringResource(storageStatus))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = !storageBusy && !loading && !recording && apiKey.isNotBlank(),
                    onClick = {
                        storageBusy = true
                        val value = apiKey.trim()
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) { store.save(value) }
                                apiKey = value
                                storageStatus = R.string.key_saved
                            } catch (error: CancellationException) {
                                throw error
                            } catch (_: Exception) {
                                storageStatus = R.string.key_save_error
                            } finally {
                                storageBusy = false
                            }
                        }
                    }
                ) {
                    Text(stringResource(R.string.save_key))
                }
                OutlinedButton(
                    enabled = !storageBusy && !loading && !recording,
                    onClick = {
                        storageBusy = true
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) { store.clear() }
                                apiKey = ""
                                models = emptyList()
                                model = ""
                                session.reset()
                                chatError = ""
                                chatVersion++
                                storageStatus = R.string.key_deleted
                            } catch (error: CancellationException) {
                                throw error
                            } catch (_: Exception) {
                                storageStatus = R.string.key_delete_error
                            } finally {
                                storageBusy = false
                            }
                        }
                    }
                ) {
                    Text(stringResource(R.string.delete_key))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    enabled = !storageBusy && !loading && !recording && apiKey.isNotBlank(),
                    onClick = {
                        loading = true
                        failed = false
                        chatError = ""
                        menuOpen = false
                        scope.launch {
                            try {
                                models = withContext(Dispatchers.IO) {
                                    client.listModels(apiKey.trim())
                                }
                                if (models.isEmpty()) {
                                    failed = true
                                    chatError = "Aucun modèle compatible generateContent retourné pour cette clé."
                                }
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: GeminiException) {
                                failed = true
                                chatError = error.message ?: unexpectedError
                            } catch (_: Exception) {
                                failed = true
                                chatError = unexpectedError
                            } finally {
                                loading = false
                            }
                        }
                    }
                ) {
                    Text(stringResource(R.string.load_models))
                }
                Box {
                    OutlinedButton(
                        enabled = !storageBusy && !loading && !recording && models.isNotEmpty(),
                        onClick = { menuOpen = true }
                    ) {
                        Text(stringResource(R.string.choose_model))
                    }
                    androidx.compose.material3.DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false }
                    ) {
                        models.forEach { availableModel ->
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text(availableModel) },
                                onClick = {
                                    model = availableModel
                                    menuOpen = false
                                }
                            )
                        }
                    }
                }
                Text(model, style = MaterialTheme.typography.bodySmall)
            }
            if (model.isBlank()) {
                OutlinedButton(enabled = false, onClick = {}) {
                    Text(stringResource(R.string.send))
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(model, style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = { session.reset(); chatVersion++ }) {
                            Text(stringResource(R.string.new_conversation))
                        }
                    }
                    if (loading) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator()
                            Text(stringResource(R.string.loading))
                        }
                    }
                    if (failed) {
                        Text(
                            stringResource(R.string.error_label) + " — " + chatError,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Column(
                        modifier = Modifier
                            .verticalScroll(scrollState)
                            .weight(1f, fill = false),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        session.messages.forEach { message ->
                            val isUser = message.role == "user"
                            Box(
                                modifier = Modifier.fillMaxWidth(),
                                contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
                            ) {
                                Text(
                                    text = message.text,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (isUser) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                    modifier = Modifier.widthIn(max = 300.dp)
                                )
                            }
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    label = { Text(stringResource(R.string.message_hint)) },
                    singleLine = true,
                    enabled = !storageBusy && !loading && !recording,
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = {
                        val text = draft
                        draft = ""
                        loading = true
                        failed = false
                        chatError = ""
                        scope.launch {
                            try {
                                val response = session.send(apiKey.trim(), model.trim(), text)
                                chatVersion++
                                val speechBytes = withContext(Dispatchers.IO) {
                                    client.generateSpeech(apiKey.trim(), model.trim(), response)
                                }
                                tts.play(speechBytes)
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: Exception) {
                                handleGeminiError(error, text, text)
                            } finally {
                                loading = false
                            }
                        }
                    },
                    enabled = !storageBusy && !loading && !recording && apiKey.isNotBlank() && model.isNotBlank() && draft.isNotBlank()
                ) {
                    Text(stringResource(R.string.send))
                }
                Button(
                    enabled = !storageBusy && !loading && apiKey.isNotBlank() && model.isNotBlank(),
                    onClick = {
                        if (recording) {
                            recording = false
                            val samples = try {
                                recorder.stop()
                            } catch (_: RecorderException) {
                                ShortArray(0)
                            }
                            if (samples.isEmpty()) {
                                failed = true
                                chatError = voiceError
                                return@Button
                            }
                            loading = true
                            failed = false
                            chatError = ""
                            scope.launch {
                                try {
                                    val response = withContext(Dispatchers.IO) {
                                        AudioTranscriber.transcribe(client, apiKey.trim(), model.trim(), samples, recorder.sampleRate())
                                    }
                                    session.appendTurn("Message vocal", response)
                                    chatVersion++
                                    val speechBytes = withContext(Dispatchers.IO) {
                                        client.generateSpeech(apiKey.trim(), model.trim(), response)
                                    }
                                    tts.play(speechBytes)
                                } catch (error: CancellationException) {
                                    throw error
                                } catch (error: Exception) {
                                    handleGeminiError(error, "", "")
                                } finally {
                                    loading = false
                                }
                            }
                        } else {
                            failed = false
                            chatError = ""
                            try {
                                recorder.start(context)
                                recording = true
                            } catch (_: RecorderException) {
                                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        }
                    }
                ) {
                    Text(stringResource(if (recording) R.string.stop else R.string.record))
                }
            }
        }
    }
}
