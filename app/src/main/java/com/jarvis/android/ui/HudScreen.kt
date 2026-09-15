package com.jarvis.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
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
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("JARVIS") },
                actions = {
                    IconButton(onClick = onOpenMemory) { Icon(Icons.Filled.Info, contentDescription = "Memory") }
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Filled.Menu, contentDescription = "Settings") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(24.dp))
            ReactorCore(state = state, onTap = {
                when (state) {
                    JarvisState.ASLEEP -> onStart()
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
                OutlinedButton(onClick = onStop) { Text("Stop session") }
            }

            Spacer(Modifier.height(16.dp))
            Text("Activity", style = MaterialTheme.typography.labelLarge)
            LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                items(activityLog.asReversed()) { line ->
                    Text(line, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 2.dp))
                }
            }
        }
    }
}

@Composable
private fun ReactorCore(state: JarvisState, onTap: () -> Unit) {
    val color = when (state) {
        JarvisState.ASLEEP -> MaterialTheme.colorScheme.surfaceVariant
        JarvisState.CONNECTING -> MaterialTheme.colorScheme.secondary
        JarvisState.LISTENING -> MaterialTheme.colorScheme.primary
        JarvisState.THINKING -> MaterialTheme.colorScheme.tertiary
        JarvisState.SPEAKING -> MaterialTheme.colorScheme.primary
        JarvisState.ERROR -> Color(0xFFE05252)
    }
    Box(
        modifier = Modifier
            .size(160.dp)
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
                .clip(CircleShape)
                .background(color)
        )
    }
    Spacer(Modifier.height(8.dp))
    TextButton(onClick = onTap) { Text(if (state == JarvisState.ASLEEP) "Tap to start" else "Tap to sleep/wake") }
}

@Composable
private fun ConfirmBanner(pending: PendingConfirmation, onConfirm: () -> Unit, onCancel: () -> Unit) {
    Card {
        Column(Modifier.padding(16.dp)) {
            Text(pending.actionLabel, fontWeight = FontWeight.Bold)
            Text(pending.detail, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            Row {
                Button(onClick = onConfirm) { Text("CONFIRM") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = onCancel) { Text("Cancel") }
            }
        }
    }
}

private fun stateLabel(state: JarvisState): String = when (state) {
    JarvisState.ASLEEP -> "Asleep"
    JarvisState.CONNECTING -> "Connecting…"
    JarvisState.LISTENING -> "Listening"
    JarvisState.THINKING -> "Thinking…"
    JarvisState.SPEAKING -> "Speaking"
    JarvisState.ERROR -> "Error"
}
