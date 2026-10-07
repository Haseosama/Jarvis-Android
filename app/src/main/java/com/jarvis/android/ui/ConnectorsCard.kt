package com.jarvis.android.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jarvis.android.JarvisApp
import com.jarvis.android.connectors.ConnectorOutcome
import com.jarvis.android.i18n.tr
import com.jarvis.android.i18n.trf
import kotlinx.coroutines.launch

/** Connectors: remote MCP servers whose tools Jarvis uses (added with their https address, a token or a login in the browser). */
@Composable
internal fun ConnectorsCard() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val manager = remember { (context.applicationContext as JarvisApp).container.connectors }
    val tick by manager.changes.collectAsState()
    val connectors = remember(tick) { manager.list() }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var nameField by rememberSaveable { mutableStateOf("") }
    var urlField by rememberSaveable { mutableStateOf("") }
    var tokenField by remember { mutableStateOf("") }

    fun show(outcome: ConnectorOutcome) {
        message = outcome.message
        outcome.loginUrl?.let { url ->
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {
                message = tr("Aucun navigateur pour ouvrir la page de connexion.")
            }
        }
    }

    fun act(block: suspend () -> ConnectorOutcome?) {
        busy = true
        scope.launch {
            block()?.let(::show)
            busy = false
        }
    }

    SettingsCard(tr("Connecteurs"), Icons.Filled.Link, initiallyExpanded = false) {
        Text(
            tr("Branchez Jarvis sur d’autres services avec des connecteurs MCP (Model Context Protocol) : Notion, GitHub, Home Assistant, Zapier, n8n… Collez l’adresse https du serveur MCP du service. S’il demande une connexion, la page s’ouvre dans le navigateur ; sinon, collez le jeton d’accès qu’il fournit. Ses outils s’ajoutent à ceux de Jarvis dès la prochaine session."),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (connectors.isEmpty()) {
            Text(tr("Aucun connecteur."), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
        }
        connectors.forEach { c ->
            Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text(c.name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                        Text(c.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Switch(checked = c.enabled, onCheckedChange = { on -> act { manager.setEnabled(c.id, on); null } })
                }
                val state = when {
                    c.needsLogin -> tr("Connexion nécessaire")
                    c.status.isNotEmpty() -> c.status
                    c.tools.isEmpty() -> tr("Aucun outil")
                    else -> trf("{0} outil(s) : {1}", c.tools.size, c.tools.joinToString(", ") { it.name })
                }
                Text(
                    state,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (c.needsLogin || c.status.isNotEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                    if (c.token.isEmpty()) {
                        OutlinedButton(enabled = !busy, onClick = { act { manager.startLogin(c.id) } }) {
                            Text(if (c.oauth == null) tr("Se connecter") else tr("Se reconnecter"))
                        }
                    }
                    OutlinedButton(enabled = !busy, onClick = { act { manager.refresh(c.id) } }) { Text(tr("Actualiser")) }
                    TextButton(enabled = !busy, onClick = {
                        act {
                            manager.remove(c.id)
                            ConnectorOutcome(trf("« {0} » retiré.", c.name))
                        }
                    }) { Text(tr("Retirer"), color = MaterialTheme.colorScheme.error) }
                }
            }
        }
        Text(tr("Ajouter un connecteur"), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 16.dp))
        OutlinedTextField(
            value = nameField,
            onValueChange = { nameField = it.take(40) },
            label = { Text(tr("Nom (ex. Notion)")) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
        OutlinedTextField(
            value = urlField,
            onValueChange = { urlField = it.trim() },
            label = { Text(tr("Adresse du serveur MCP (https://…)")) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
        OutlinedTextField(
            value = tokenField,
            onValueChange = { tokenField = it.trim() },
            label = { Text(tr("Jeton d’accès (facultatif)")) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
        FilledTonalButton(
            enabled = urlField.isNotBlank() && !busy,
            onClick = {
                act {
                    val outcome = manager.add(nameField, urlField, tokenField)
                    if (!outcome.message.startsWith("Adresse refusée")) {
                        nameField = ""
                        urlField = ""
                        tokenField = ""
                    }
                    outcome
                }
            },
            modifier = Modifier.padding(top = 8.dp),
        ) { Text(if (busy) tr("Connexion…") else tr("Ajouter")) }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp)) }
    }
}
