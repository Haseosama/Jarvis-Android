package com.jarvis.android.share

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.jarvis.android.JarvisApp
import com.jarvis.android.i18n.Lang
import com.jarvis.android.i18n.tr
import com.jarvis.android.rest.RestChatException
import com.jarvis.android.rest.RestReply
import com.jarvis.android.rest.parseGenerateResponse
import com.jarvis.android.ui.theme.JarvisTheme
import kotlinx.coroutines.CancellationException

/**
 * Target of "Jarvis" in the selected-text menu of any app (see TextActions.kt): a small panel over that app with Translate,
 * Summarise, Explain and Fix. Nothing is sent before an action is tapped. The answer can be copied, and when the selection was in a
 * field the user can edit, a translation or a correction can replace it.
 */
class ProcessTextActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()?.trim().orEmpty()
        val readOnly = intent.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
        if (text.isEmpty()) {
            finish()
            return
        }
        val container = (application as JarvisApp).container
        setContent {
            val hue by container.configStore.themeHue.collectAsState(initial = 190f)
            JarvisTheme(hue = hue) {
                Box(
                    Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)).clickable { finish() },
                    contentAlignment = Alignment.Center,
                ) {
                    // a tap on the panel itself must not reach the dimmed background, which closes it
                    val panelTaps = remember { MutableInteractionSource() }
                    Card(Modifier.fillMaxWidth().padding(16.dp).clickable(interactionSource = panelTaps, indication = null) {}) {
                        TextActionsPanel(
                            text = text,
                            canReplace = !readOnly,
                            run = { action ->
                                val request = buildTextActionRequest(action, text, Lang.isEnglish)
                                val reply = container.restChat.transport.generate(container.configStore.snapshotRestModel(), request)
                                (parseGenerateResponse(reply) as? RestReply.Text)?.text?.let { cleanTextActionAnswer(it) }
                                    ?: throw RestChatException(tr("Pas de réponse."))
                            },
                            onCopy = { answer -> copy(answer) },
                            onReplace = { answer ->
                                setResult(RESULT_OK, Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, answer))
                                finish()
                            },
                            onClose = { finish() },
                        )
                    }
                }
            }
        }
    }

    private fun copy(answer: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Jarvis", answer))
    }
}

@Composable
private fun TextActionsPanel(
    text: String,
    canReplace: Boolean,
    run: suspend (TextAction) -> String,
    onCopy: (String) -> Unit,
    onReplace: (String) -> Unit,
    onClose: () -> Unit,
) {
    var action by remember { mutableStateOf<TextAction?>(null) }
    var answer by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(action) {
        val chosen = action ?: return@LaunchedEffect
        answer = null
        error = null
        copied = false
        try {
            answer = run(chosen)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: tr("Erreur inattendue. Réessayez.")
        }
    }
    Column(Modifier.padding(16.dp)) {
        Text("Jarvis", style = MaterialTheme.typography.titleMedium)
        Text(
            text.replace(Regex("\\s+"), " ").let { if (it.length > 140) it.take(139) + "…" else it },
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 10.dp).horizontalScroll(rememberScrollState())) {
            TextAction.entries.forEach { a ->
                FilterChip(selected = action == a, onClick = { action = a }, label = { Text(tr(a.french)) })
            }
        }
        when {
            action == null -> Text(tr("Choisissez ce que Jarvis doit faire de ce texte."), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 10.dp))
            error != null -> Text(error!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 10.dp))
            answer == null -> CircularProgressIndicator(Modifier.padding(top = 12.dp).size(24.dp), strokeWidth = 2.dp)
            else -> SelectionContainer(Modifier.padding(top = 10.dp).heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                Text(answer!!, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) {
            val ready = answer
            if (ready != null) {
                TextButton(onClick = { onCopy(ready); copied = true }) { Text(if (copied) tr("Copié ✓") else tr("Copier")) }
                if (canReplace && action?.replaces == true) Button(onClick = { onReplace(ready) }) { Text(tr("Remplacer")) }
            }
            TextButton(onClick = onClose) { Text(tr("Fermer")) }
        }
    }
}
