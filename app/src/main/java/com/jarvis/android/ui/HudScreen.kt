package com.jarvis.android.ui

import com.jarvis.android.i18n.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jarvis.android.core.ConversationMessage
import com.jarvis.android.core.JarvisState
import com.jarvis.android.core.MAX_MESSAGE_CHARS
import com.jarvis.android.core.PendingConfirmation
import com.jarvis.android.core.VideoSource
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HudScreen(
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
    videoSource: VideoSource = VideoSource.OFF,
    onVideoSource: (VideoSource) -> Unit = {},
    onCameraFrame: (ByteArray) -> Unit = {},
    conversation: List<ConversationMessage> = emptyList(),
    sessionReady: Boolean = false,
    onSendText: suspend (String) -> Boolean = { false },
    avatar: com.jarvis.android.avatar.AvatarController? = null,
    /** The last kept session, shown while no session is running, with its date. */
    previousSession: List<ConversationMessage> = emptyList(),
    previousLabel: String = "",
) {
    var draft by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var sendError by remember { mutableStateOf(false) }
    var showActivity by remember { mutableStateOf(false) }
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
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onBackground,
        topBar = {
            TopAppBar(
                title = { Text("JARVIS", style = MaterialTheme.typography.titleLarge) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                actions = {
                    val pickFile = rememberFileAttacher { }
                    val tint = MaterialTheme.colorScheme.onSurfaceVariant
                    IconButton(onClick = pickFile) { Icon(Icons.Filled.AttachFile, contentDescription = tr("Joindre un fichier"), tint = tint) }
                    IconButton(onClick = onOpenChat) { Icon(Icons.Filled.Chat, contentDescription = tr("Chat texte"), tint = tint) }
                    IconButton(onClick = onOpenMemory) { Icon(Icons.Filled.Info, contentDescription = tr("Mémoire"), tint = tint) }
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Filled.Settings, contentDescription = tr("Paramètres"), tint = tint) }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).imePadding().padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val onCoreTap = {
                when (state) {
                    JarvisState.ASLEEP, JarvisState.ERROR -> onStart()
                    else -> onToggleAwake()
                }
            }
            if (avatar != null) {
                Box(
                    Modifier.padding(top = 2.dp).size(272.dp)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onCoreTap),
                ) {
                    com.jarvis.android.avatar.AvatarView(avatar, state, outputLevel, Modifier.fillMaxSize())
                }
            } else {
                ReactorCore(state = state, outputLevel = outputLevel, onTap = onCoreTap)
            }
            StatePill(stateLabel(state), stateColor(state))
            Text(
                when (state) {
                    JarvisState.ASLEEP -> tr("Touchez le cœur pour démarrer")
                    JarvisState.ERROR -> tr("Touchez le cœur pour réessayer")
                    else -> tr("Touchez le cœur pour mettre en veille")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )

            if (confirmPending != null) {
                Spacer(Modifier.height(12.dp))
                ConfirmBanner(confirmPending, onConfirm, onCancelConfirm)
            }

            CameraStreamer(active = videoSource == VideoSource.CAMERA, onFrame = onCameraFrame)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 12.dp),
            ) {
                if (state != JarvisState.ASLEEP) {
                    FilledTonalButton(onClick = onStop) { Text(tr("Arrêter la session")) }
                }
                if (sessionReady) {
                    if (videoSource == VideoSource.OFF) {
                        OutlinedButton(onClick = { onVideoSource(VideoSource.SCREEN) }) { Text(tr("Écran")) }
                        OutlinedButton(onClick = { onVideoSource(VideoSource.CAMERA) }) { Text(tr("Caméra")) }
                    } else {
                        Button(
                            onClick = { onVideoSource(VideoSource.OFF) },
                            colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Color(0xFFE05252), contentColor = Color.White),
                        ) { Text(if (videoSource == VideoSource.SCREEN) tr("Écran partagé · arrêter") else tr("Caméra partagée · arrêter")) }
                    }
                }
            }

            Surface(
                modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 12.dp),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.75f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
            ) {
                LazyColumn(
                    state = conversationScroll,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    if (conversation.isEmpty()) {
                        item {
                            Text(
                                tr(if (previousSession.isEmpty()) "Les échanges de la session apparaîtront ici. Ils sont conservés sur le téléphone (Paramètres > Historique des sessions)." else "Dernière session"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 12.dp),
                            )
                        }
                        if (previousSession.isNotEmpty()) {
                            item {
                                Text(previousLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
                            }
                            items(previousSession) { message -> MessageBubble(message, modifier = Modifier.graphicsLayer { alpha = 0.62f }) }
                        }
                    }
                    items(conversation) { message -> MessageBubble(message) }
                }
            }

            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it.take(MAX_MESSAGE_CHARS); sendError = false },
                    modifier = Modifier.weight(1f),
                    enabled = sessionReady && !sending,
                    placeholder = { Text(if (sessionReady) tr("Écrire à Jarvis…") else tr("Démarrez une session pour écrire")) },
                    shape = MaterialTheme.shapes.extraLarge,
                    maxLines = 3,
                )
                Spacer(Modifier.width(8.dp))
                FilledIconButton(
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
                    modifier = Modifier.size(52.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(),
                ) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = tr("Envoyer")) }
            }
            if (sendError) Text(tr("Message non envoyé. Réessayez une fois connecté."), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            Text(
                text = activityLog.lastOrNull()?.let { trf("Activité : {0}", tr(it)) } ?: tr("Activité : rien pour l’instant"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (showActivity) 6 else 1,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { showActivity = !showActivity }
                    .padding(vertical = 8.dp),
            )
            if (showActivity) {
                Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                    activityLog.takeLast(6).asReversed().forEach {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun stateColor(state: JarvisState): Color = when (state) {
    JarvisState.ASLEEP -> MaterialTheme.colorScheme.outline
    JarvisState.CONNECTING -> MaterialTheme.colorScheme.secondary
    JarvisState.LISTENING -> MaterialTheme.colorScheme.primary
    JarvisState.THINKING -> MaterialTheme.colorScheme.tertiary
    JarvisState.SPEAKING -> MaterialTheme.colorScheme.primary
    JarvisState.ERROR -> Color(0xFFE05252)
}

@Composable
private fun ReactorCore(state: JarvisState, outputLevel: Float, onTap: () -> Unit) {
    val color = stateColor(state)
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
    val rotation = if (motion.pulse > 0f) {
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(animation = tween(14_000, easing = LinearEasing)),
            label = "rotation",
        ).value
    } else {
        0f
    }
    val voice by animateFloatAsState(
        targetValue = if (motion.followsVoice) outputLevel else 0f,
        animationSpec = tween(90),
        label = "voice",
    )
    val active = state != JarvisState.ASLEEP && state != JarvisState.ERROR
    Box(
        modifier = Modifier
            .padding(top = 4.dp, bottom = 4.dp)
            .size(236.dp)
            .clip(CircleShape)
            .clickable(onClick = onTap)
            .graphicsLayer {
                val scale = 1f + motion.pulse * breath
                scaleX = scale
                scaleY = scale
            },
        contentAlignment = Alignment.Center,
    ) {
        GlowReactor(
            color = color,
            rotation = rotation,
            coreScale = 1f + motion.pulse * 2f * breath + VOICE_GAIN * voice,
            ringAlpha = if (active) 1f else 0.15f,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
internal fun ConfirmBanner(pending: PendingConfirmation, onConfirm: () -> Unit, onCancel: () -> Unit) {
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(pending.actionLabel, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(pending.detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onConfirm) { Text(tr("Confirmer")) }
                OutlinedButton(onClick = onCancel) { Text(tr("Annuler")) }
            }
        }
    }
}

private fun stateLabel(state: JarvisState): String = when (state) {
    JarvisState.ASLEEP -> tr("En veille")
    JarvisState.CONNECTING -> tr("Connexion…")
    JarvisState.LISTENING -> tr("À l’écoute")
    JarvisState.THINKING -> tr("Réflexion…")
    JarvisState.SPEAKING -> tr("Réponse en cours")
    JarvisState.ERROR -> tr("Session interrompue")
}
