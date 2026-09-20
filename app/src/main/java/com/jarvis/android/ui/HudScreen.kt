package com.jarvis.android.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.lazy.rememberLazyListState
import com.jarvis.android.core.ConversationMessage
import com.jarvis.android.core.ConversationRole
import com.jarvis.android.core.MAX_MESSAGE_CHARS
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jarvis.android.core.JarvisState
import com.jarvis.android.core.PendingConfirmation

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HudScreen(
    state: JarvisState,
    activityLog: List<String>,
    confirmPending: PendingConfirmation?,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onToggleAwake: () -> Unit,
    onConfirm: () -> Unit,
    onCancelConfirm: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenMemory: () -> Unit,
    onOpenChat: () -> Unit = {},
    outputLevel: Float = 0f,
    videoSource: com.jarvis.android.core.VideoSource = com.jarvis.android.core.VideoSource.OFF,
    onVideoSource: (com.jarvis.android.core.VideoSource) -> Unit = {},
    onCameraFrame: (ByteArray) -> Unit = {},
    conversation: List<ConversationMessage> = emptyList(),
    sessionReady: Boolean = false,
    onSendText: suspend (String) -> Boolean = { false },
) {
    var draft by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var sendError by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val conversationScroll = rememberLazyListState()
    LaunchedEffect(state) {
        if (state == JarvisState.ASLEEP) {
            draft = ""
            sendError = false
        }
    }
    LaunchedEffect(conversation) {
        if (conversation.isNotEmpty()) conversationScroll.animateScrollToItem(conversation.lastIndex)
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("JARVIS") },
                actions = {
                    val pickFile = rememberFileAttacher { }
                    IconButton(onClick = pickFile) { Icon(Icons.Filled.AttachFile, contentDescription = "Joindre un fichier") }
                    IconButton(onClick = onOpenChat) { Icon(Icons.Filled.Chat, contentDescription = "Chat texte") }
                    IconButton(onClick = onOpenMemory) { Icon(Icons.Filled.Info, contentDescription = "Mémoire") }
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Filled.Menu, contentDescription = "Paramètres") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).imePadding().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(24.dp))
            ReactorCore(state = state, outputLevel = outputLevel, onTap = {
                when (state) {
                    JarvisState.ASLEEP, JarvisState.ERROR -> onStart()
                    else -> onToggleAwake()
                }
            })
            Spacer(Modifier.height(12.dp))
            Text(stateLabel(state), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

            if (confirmPending != null) {
                Spacer(Modifier.height(16.dp))
                ConfirmBanner(confirmPending, onConfirm, onCancelConfirm)
            }

            Spacer(Modifier.height(24.dp))
            if (state != JarvisState.ASLEEP) {
                OutlinedButton(onClick = onStop) { Text("Arrêter la session") }
            }
            CameraStreamer(active = videoSource == com.jarvis.android.core.VideoSource.CAMERA, onFrame = onCameraFrame)
            if (sessionReady) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    when (videoSource) {
                        com.jarvis.android.core.VideoSource.OFF -> {
                            OutlinedButton(onClick = { onVideoSource(com.jarvis.android.core.VideoSource.SCREEN) }) { Text("Partager l’écran") }
                            OutlinedButton(onClick = { onVideoSource(com.jarvis.android.core.VideoSource.CAMERA) }) { Text("Partager la caméra") }
                        }
                        else -> {
                            Text(
                                if (videoSource == com.jarvis.android.core.VideoSource.SCREEN) "Écran partagé avec Gemini" else "Caméra partagée avec Gemini",
                                color = androidx.compose.ui.graphics.Color(0xFFE05252),
                                fontWeight = FontWeight.Bold,
                            )
                            OutlinedButton(onClick = { onVideoSource(com.jarvis.android.core.VideoSource.OFF) }) { Text("Arrêter") }
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Text("Conversation éphémère", style = MaterialTheme.typography.labelLarge)
            LazyColumn(
                state = conversationScroll,
                modifier = Modifier.fillMaxWidth().weight(1f),
            ) {
                if (conversation.isEmpty()) {
                    item { Text("Les transcriptions apparaîtront ici.", style = MaterialTheme.typography.bodySmall) }
                }
                items(conversation) { message ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        val (label, color) = when (message.role) {
                            ConversationRole.USER -> "Vous" to MaterialTheme.colorScheme.primary
                            ConversationRole.ASSISTANT -> "Jarvis" to MaterialTheme.colorScheme.primary
                            ConversationRole.SYSTEM -> "Système" to MaterialTheme.colorScheme.secondary
                        }
                        Text(
                            label,
                            style = MaterialTheme.typography.labelMedium,
                            color = color,
                        )
                        Text(message.text, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            Text(
                if (sessionReady) "Texte envoyé dans la session vocale · microphone actif"
                else "Démarrez une session vocale pour envoyer du texte.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it.take(MAX_MESSAGE_CHARS); sendError = false },
                    modifier = Modifier.weight(1f),
                    enabled = sessionReady && !sending,
                    label = { Text("Votre message") },
                    maxLines = 3,
                )
                TextButton(
                    enabled = sessionReady && draft.isNotBlank() && !sending,
                    onClick = {
                        val text = draft
                        sending = true
                        scope.launch {
                            try {
                                if (onSendText(text)) {
                                    draft = ""
                                    sendError = false
                                } else {
                                    sendError = true
                                }
                            } finally {
                                sending = false
                            }
                        }
                    },
                ) { Text("Envoyer") }
            }
            if (sendError) Text("Message non envoyé. Réessayez une fois connecté.", color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(8.dp))
            Text("Activité", style = MaterialTheme.typography.labelLarge)
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 72.dp)) {
                items(activityLog.asReversed()) { line ->
                    Text(line, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 2.dp))
                }
            }
        }
    }
}

@Composable
private fun ReactorCore(state: JarvisState, outputLevel: Float, onTap: () -> Unit) {
    val color = when (state) {
        JarvisState.ASLEEP -> MaterialTheme.colorScheme.surfaceVariant
        JarvisState.CONNECTING -> MaterialTheme.colorScheme.secondary
        JarvisState.LISTENING -> MaterialTheme.colorScheme.primary
        JarvisState.THINKING -> MaterialTheme.colorScheme.tertiary
        JarvisState.SPEAKING -> MaterialTheme.colorScheme.primary
        JarvisState.ERROR -> Color(0xFFE05252)
    }
    val motion = reactorMotion(state)
    val transition = rememberInfiniteTransition(label = "reactor")
    // No animation registered while still: an endless animation keeps the screen "busy" and
    // costs battery for nothing.
    val breath = if (motion.pulse > 0f) {
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(motion.periodMs, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "breath",
        ).value
    } else {
        0f
    }
    val voice by animateFloatAsState(
        targetValue = if (motion.followsVoice) outputLevel else 0f,
        animationSpec = tween(90),
        label = "voice",
    )
    Box(
        modifier = Modifier
            .size(160.dp)
            .graphicsLayer {
                val scale = 1f + motion.pulse * breath
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .clickable(onClick = onTap)
            .background(color.copy(alpha = 0.25f))
            .padding(12.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = 0.5f)),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .graphicsLayer {
                    val scale = 1f + motion.pulse * 2f * breath + VOICE_GAIN * voice
                    scaleX = scale
                    scaleY = scale
                }
                .clip(CircleShape)
                .background(color)
        )
    }
    Spacer(Modifier.height(8.dp))
    TextButton(onClick = onTap) {
        Text(when (state) {
            JarvisState.ASLEEP -> "Démarrer"
            JarvisState.ERROR -> "Réessayer"
            else -> "Mettre en veille"
        })
    }
}

@Composable
internal fun ConfirmBanner(pending: PendingConfirmation, onConfirm: () -> Unit, onCancel: () -> Unit) {
    Card {
        Column(Modifier.padding(16.dp)) {
            Text(pending.actionLabel, fontWeight = FontWeight.Bold)
            Text(pending.detail, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            Row {
                Button(onClick = onConfirm) { Text("Confirmer") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = onCancel) { Text("Annuler") }
            }
        }
    }
}

private fun stateLabel(state: JarvisState): String = when (state) {
    JarvisState.ASLEEP -> "En veille"
    JarvisState.CONNECTING -> "Connexion…"
    JarvisState.LISTENING -> "À l’écoute"
    JarvisState.THINKING -> "Réflexion…"
    JarvisState.SPEAKING -> "Réponse en cours"
    JarvisState.ERROR -> "Session interrompue"
}
