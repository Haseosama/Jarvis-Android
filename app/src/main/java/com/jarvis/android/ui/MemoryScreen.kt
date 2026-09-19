package com.jarvis.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jarvis.android.memory.MemoryManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryScreen(memoryManager: MemoryManager, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var rows by remember(memoryManager) { mutableStateOf<List<MemoryManager.UiRow>>(emptyList()) }
    var loaded by remember(memoryManager) { mutableStateOf(false) }
    var error by remember(memoryManager) { mutableStateOf<String?>(null) }
    var busy by remember(memoryManager) { mutableStateOf(false) }
    var reload by remember(memoryManager) { mutableIntStateOf(0) }
    var deleted by remember(memoryManager) { mutableStateOf<MemoryManager.Change?>(null) }

    LaunchedEffect(memoryManager, reload) {
        loaded = false
        error = null
        try {
            rows = withContext(Dispatchers.IO) { memoryManager.allEntriesForUi() }
            loaded = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = "Impossible de lire la mémoire. Le fichier a été conservé."
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Mémoire") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") } },
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            error?.let { message ->
                Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
                TextButton(onClick = { reload++ }, enabled = !busy) { Text("Réessayer") }
            }
            deleted?.let { change ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Souvenir supprimé", modifier = Modifier.weight(1f))
                    TextButton(enabled = !busy, onClick = {
                        busy = true
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) { memoryManager.restore(change) }
                                deleted = null
                                reload++
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                error = "Annulation impossible : mémoire inaccessible ou souvenir modifié."
                            } finally {
                                busy = false
                            }
                        }
                    }) { Text("Annuler") }
                }
            }
            Box(Modifier.fillMaxSize()) {
                if (!loaded && error == null) {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                } else if (loaded && rows.isEmpty()) {
                    Text("Aucun souvenir enregistré.", modifier = Modifier.align(Alignment.Center).padding(24.dp))
                }
                if (loaded) {
                    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                        items(rows, key = { it.category + "/" + it.key }) { row ->
                            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("${memoryCategoryLabel(row.category)} / ${row.key}", style = MaterialTheme.typography.labelMedium)
                                        Text(row.value, style = MaterialTheme.typography.bodyMedium)
                                        Text("Mis à jour le ${row.updated}", style = MaterialTheme.typography.labelSmall)
                                    }
                                    IconButton(enabled = !busy && error == null, onClick = {
                                        busy = true
                                        scope.launch {
                                            try {
                                                val change = withContext(Dispatchers.IO) {
                                                    memoryManager.forgetChange(row.key, row.category)
                                                }
                                                if (change.changed) deleted = change
                                                reload++
                                            } catch (e: CancellationException) {
                                                throw e
                                            } catch (e: Exception) {
                                                error = "Suppression impossible. Le souvenir a été conservé."
                                            } finally {
                                                busy = false
                                            }
                                        }
                                    }) { Icon(Icons.Filled.Delete, contentDescription = "Supprimer ${row.key}") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

internal fun memoryCategoryLabel(category: String): String = when (category) {
    "identity" -> "Identité"
    "preferences" -> "Préférences"
    "projects" -> "Projets"
    "relationships" -> "Relations"
    "wishes" -> "Souhaits"
    "notes" -> "Notes"
    else -> category
}
