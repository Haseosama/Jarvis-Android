package com.jarvis.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jarvis.android.memory.MemoryManager
import kotlinx.coroutines.launch

/** What Jarvis remembers about you, when it learned it, and a delete button — port of the desktop's Memory Panel. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryScreen(memoryManager: MemoryManager, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf<List<MemoryManager.UiRow>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        rows = memoryManager.allEntriesForUi()
        loaded = true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Memory") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, null) } },
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (loaded && rows.isEmpty()) {
                Text(
                    "Nothing stored yet.",
                    modifier = Modifier.align(androidx.compose.ui.Alignment.Center).padding(24.dp),
                )
            }
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                items(rows, key = { it.category + "/" + it.key }) { row ->
                    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Row(
                            Modifier.padding(12.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("${row.category} / ${row.key}", style = MaterialTheme.typography.labelMedium)
                                Text(row.value, style = MaterialTheme.typography.bodyMedium)
                                Text(row.updated, style = MaterialTheme.typography.labelSmall)
                            }
                            IconButton(onClick = {
                                scope.launch {
                                    memoryManager.forget(row.key, row.category)
                                    rows = memoryManager.allEntriesForUi()
                                }
                            }) { Icon(Icons.Filled.Delete, contentDescription = "Forget") }
                        }
                    }
                }
            }
        }
    }
}
