package com.jarvis.android.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jarvis.android.core.ConversationRole
import com.jarvis.android.core.MAX_MESSAGE_CHARS
import com.jarvis.android.core.PendingConfirmation
import com.jarvis.android.rest.RestChat
import com.jarvis.android.rest.VoiceStage
import kotlinx.coroutines.launch

/**
 * Text chat over plain `generateContent`: no microphone, no Live session. Tool actions that
 * need a confirmation (volume) show the same banner as on the main screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    chat: RestChat,
    modelName: String,
    confirmPending: PendingConfirmation?,
    onConfirm: () -> Unit,
    onCancelConfirm: () -> Unit,
    onBack: () -> Unit,
) {
    val messages by chat.messages.collectAsState()
    val sending by chat.sending.collectAsState()
    var draft by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val voice = chat.voice
    val container = (androidx.compose.ui.platform.LocalContext.current.applicationContext as com.jarvis.android.JarvisApp).container
    val attached by container.attachedFiles.current.collectAsState()
    val pickFile = rememberFileAttacher { failure -> error = failure }
    val stage by voice.stage.collectAsState()
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) error = voice.startRecording()
        else error = "Micro non autorisé. Accordez la permission d’enregistrement audio puis réessayez."
    }
    val listState = rememberLazyListState()

    DisposableEffect(Unit) {
        onDispose {
            voice.cancelRecording()
            voice.stopSpeaking()
        }
    }

    LaunchedEffect(messages.size, sending) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Chat texte") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Retour") } },
                actions = {
                    IconButton(
                        enabled = !sending && messages.isNotEmpty(),
                        onClick = { chat.reset(); error = null },
                    ) { Icon(Icons.Filled.Refresh, contentDescription = "Nouvelle conversation") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Modèle : $modelName", style = MaterialTheme.typography.bodySmall)
            LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().weight(1f)) {
                if (messages.isEmpty()) {
                    item {
                        Text(
                            "Écrivez un message pour démarrer une conversation texte. Jarvis peut aussi utiliser ses outils (météo, rappels, minuteurs, recherche…).",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                items(messages) { message ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        val (label, color) = when (message.role) {
                            ConversationRole.USER -> "Vous" to MaterialTheme.colorScheme.primary
                            ConversationRole.ASSISTANT -> "Jarvis" to MaterialTheme.colorScheme.primary
                            ConversationRole.SYSTEM -> "Système" to MaterialTheme.colorScheme.secondary
                        }
                        Text(label, style = MaterialTheme.typography.labelMedium, color = color)
                        Text(message.text, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (sending) {
                    item { Text("Jarvis réfléchit…", style = MaterialTheme.typography.bodySmall) }
                }
            }
            when (stage) {
                VoiceStage.RECORDING -> Text("Enregistrement… appuyez sur le micro pour envoyer.", style = MaterialTheme.typography.bodySmall)
                VoiceStage.TRANSCRIBING -> Text("Transcription…", style = MaterialTheme.typography.bodySmall)
                VoiceStage.SPEAKING -> Text("Jarvis parle…", style = MaterialTheme.typography.bodySmall)
                else -> {}
            }
            attached?.let { file ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Fichier joint : ${file.name} (${com.jarvis.android.files.humanSize(file.bytes.size.toLong())}). Demandez à Jarvis de l’analyser ; il est envoyé à Gemini à ce moment-là.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { container.attachedFiles.clear() }) { Icon(Icons.Filled.Close, contentDescription = "Retirer le fichier") }
                }
            }
            error?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            if (confirmPending != null) {
                ConfirmBanner(confirmPending, onConfirm, onCancelConfirm)
            }
            Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(bottom = 8.dp)) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it.take(MAX_MESSAGE_CHARS); error = null },
                    label = { Text("Votre message") },
                    maxLines = 4,
                    enabled = !sending && stage == VoiceStage.IDLE,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = pickFile, enabled = !sending && stage == VoiceStage.IDLE) {
                    Icon(Icons.Filled.AttachFile, contentDescription = "Joindre un fichier")
                }
                Spacer(Modifier.width(4.dp))
                when (stage) {
                    VoiceStage.RECORDING -> {
                        IconButton(onClick = { voice.cancelRecording() }) {
                            Icon(Icons.Filled.Close, contentDescription = "Annuler l’enregistrement")
                        }
                        IconButton(onClick = {
                            scope.launch {
                                error = null
                                error = voice.finishRecording()
                            }
                        }) { Icon(Icons.Filled.Stop, contentDescription = "Envoyer l’enregistrement") }
                    }
                    VoiceStage.SPEAKING -> IconButton(onClick = { voice.stopSpeaking() }) {
                        Icon(Icons.Filled.Stop, contentDescription = "Arrêter la lecture")
                    }
                    else -> IconButton(
                        enabled = !sending && stage == VoiceStage.IDLE,
                        onClick = {
                            error = null
                            micPermission.launch(Manifest.permission.RECORD_AUDIO)
                        },
                    ) { Icon(Icons.Filled.Mic, contentDescription = "Parler") }
                }
                Button(
                    enabled = draft.isNotBlank() && !sending && stage == VoiceStage.IDLE,
                    onClick = {
                        val text = draft
                        draft = ""
                        error = null
                        scope.launch {
                            val failure = chat.send(text)
                            if (failure != null) {
                                error = failure
                                if (draft.isEmpty()) draft = text
                            }
                        }
                    },
                ) { Text("Envoyer") }
            }
        }
    }
}
