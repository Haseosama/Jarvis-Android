package com.jarvis.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.jarvis.android.memory.ConfigStore
import com.jarvis.android.reminders.ReminderRecord
import com.jarvis.android.reminders.ReminderService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    configStore: ConfigStore,
    onBack: () -> Unit,
    onDeleteKey: suspend () -> Boolean = { false },
) {
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
    var hasKey by remember { mutableStateOf<Boolean?>(null) }
    var keyStatus by remember { mutableStateOf<String?>(null) }
    var keyBusy by remember { mutableStateOf(false) }
    var confirmDeleteKey by remember { mutableStateOf(false) }
    var liveModels by remember { mutableStateOf(emptyList<String>()) }
    var modelsNote by remember { mutableStateOf<String?>(null) }
    var reminders by remember { mutableStateOf(emptyList<ReminderRecord>()) }
    var reminderText by remember { mutableStateOf("") }
    var reminderWhen by remember { mutableStateOf("") }
    var reminderFeedback by remember { mutableStateOf<String?>(null) }
    var loadingReminders by remember { mutableStateOf(false) }

    val context = LocalContext.current
    LaunchedEffect(Unit) {
        hasKey = withContext(Dispatchers.IO) {
            try { configStore.hasApiKey() } catch (_: Exception) { false }
        }
    }
    LaunchedEffect(Unit) {
        loadingReminders = true
        withContext(Dispatchers.IO) {
            try {
                reminders = ReminderService.list(context)
            } catch (_: Exception) {
                reminderFeedback = "Impossible de charger les rappels."
            } finally {
                loadingReminders = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Paramètres") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Retour") } },
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding().padding(16.dp).verticalScroll(rememberScrollState())) {
            Text("Identité", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = assistantNameField,
                onValueChange = { assistantNameField = it; scope.launch { configStore.setAssistantName(it) } },
                label = { Text("Nom de l’assistant") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            OutlinedTextField(
                value = userNameField,
                onValueChange = { userNameField = it; scope.launch { configStore.setUserName(it) } },
                label = { Text("Votre nom") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            Spacer(Modifier.height(24.dp))
            Text("Voix", style = MaterialTheme.typography.titleMedium)
            ExposedDropdownMenuBox(
                expanded = voiceMenuOpen,
                onExpandedChange = { voiceMenuOpen = it },
                modifier = Modifier.padding(top = 8.dp),
            ) {
                OutlinedTextField(
                    value = voice,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Voix Gemini") },
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
            Text("Thème", style = MaterialTheme.typography.titleMedium)
            Text("Teinte de l’interface", style = MaterialTheme.typography.bodySmall)
            Slider(
                value = hue,
                onValueChange = { scope.launch { configStore.setThemeHue(it) } },
                valueRange = 0f..360f,
                modifier = Modifier.padding(top = 8.dp),
            )
            Spacer(Modifier.height(24.dp))
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("Mot d’activation (« Hey Jarvis »)", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Switch(checked = wakeWordEnabled, onCheckedChange = { scope.launch { configStore.setWakeWordEnabled(it) } })
            }
            Text(
                "Utilise la reconnaissance vocale d’Android. La disponibilité et le fonctionnement hors connexion dépendent de l’appareil ; ce n’est pas le détecteur hors ligne de l’application de bureau. L’écoute reprend automatiquement en veille.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(24.dp))
            Text("Options avancées", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = modelField,
                onValueChange = { modelField = it; scope.launch { configStore.setModel(it) } },
                label = { Text("Modèle Live") },
                singleLine = true,
                supportingText = { Text("Saisissez un identifiant ou choisissez-en un dans la liste ci-dessous. Le changement s’applique au prochain démarrage de session.") },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            OutlinedTextField(
                value = apiKeyField,
                onValueChange = { apiKeyField = it },
                label = { Text("Nouvelle clé API Gemini") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            Button(
                onClick = {
                    val candidate = validatedApiKey(apiKeyField) ?: return@Button
                    keyBusy = true
                    keyStatus = null
                    scope.launch {
                        try {
                            if (configStore.saveApiKey(candidate)) {
                                apiKeyField = ""
                                hasKey = true
                                keyStatus = "Clé enregistrée et chiffrée sur cet appareil."
                            } else {
                                keyStatus = "Enregistrement impossible. Réessayez."
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            keyStatus = "Enregistrement impossible. Réessayez."
                        } finally {
                            keyBusy = false
                        }
                    }
                },
                enabled = validatedApiKey(apiKeyField) != null && !testing && !keyBusy,
                modifier = Modifier.padding(top = 8.dp),
            ) { Text("Enregistrer la clé") }
            Text(
                keyStatus ?: when (hasKey) {
                    true -> "Une clé est enregistrée sur cet appareil."
                    false -> "Aucune clé enregistrée."
                    null -> "Lecture du stockage sécurisé…"
                },
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
            )
            OutlinedButton(
                onClick = { confirmDeleteKey = true },
                enabled = hasKey == true && !keyBusy && !testing,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.padding(top = 8.dp),
            ) { Text("Supprimer la clé enregistrée") }
            Spacer(Modifier.height(16.dp))
            Text(
                "Le diagnostic teste l’API REST standard avec la clé enregistrée, indépendamment de la connexion vocale Live. Un succès ne garantit pas l’accès au modèle Live choisi.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(
                onClick = {
                    testing = true
                    testResult = null
                    scope.launch {
                        try { testResult = testApiKey(configStore) } finally { testing = false }
                    }
                },
                enabled = !testing,
                modifier = Modifier.padding(top = 8.dp),
            ) { Text(if (testing) "Vérification en cours…" else "Tester la clé enregistrée (REST)") }
            testResult?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    testing = true
                    testResult = null
                    modelsNote = null
                    scope.launch {
                        try {
                            val listing = listLiveModels(configStore)
                            liveModels = listing.models
                            modelsNote = listing.message
                        } finally {
                            testing = false
                        }
                    }
                },
                enabled = !testing,
                modifier = Modifier.padding(top = 8.dp),
            ) { Text(if (testing) "Vérification en cours…" else "Lister les modèles compatibles Live") }
            modelsNote?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            }
            if (liveModels.isNotEmpty()) {
                Text(
                    "Touchez un modèle pour l’utiliser :",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
                liveModels.forEach { name ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = name == model,
                                role = Role.RadioButton,
                                onClick = { scope.launch { configStore.setModel(name) } },
                            )
                            .padding(vertical = 4.dp),
                    ) {
                        RadioButton(selected = name == model, onClick = null)
                        Text(name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 12.dp))
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            Text("Rappels", style = MaterialTheme.typography.titleMedium)
            if (reminderFeedback != null) {
                Text(reminderFeedback!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(8.dp))
            }
            OutlinedTextField(
                value = reminderText,
                onValueChange = { reminderText = it.take(1000) },
                label = { Text("Rappel") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            OutlinedTextField(
                value = reminderWhen,
                onValueChange = { reminderWhen = it },
                label = { Text("Quand (yyyy-MM-dd HH:mm)") },
                singleLine = true,
                supportingText = { Text("Date stricte dans le futur, heure non ambiguë dans votre fuseau.") },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            Button(
                onClick = {
                    reminderFeedback = null
                    loadingReminders = true
                    scope.launch {
                        try {
                            val record = withContext(Dispatchers.IO) {
                                ReminderService.create(context, reminderText.trim(), reminderWhen.trim())
                            }
                            reminders = ReminderService.list(context)
                            reminderText = ""
                            reminderWhen = ""
                            reminderFeedback = "Rappel #${record.id} enregistré pour ${record.whenIso} (${record.zoneId})."
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            reminderFeedback = "Le rappel n’a pas pu être enregistré. Vérifiez la date et réessayez."
                        } finally {
                            loadingReminders = false
                        }
                    }
                },
                enabled = reminderText.isNotBlank() && reminderWhen.isNotBlank() && !loadingReminders,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) { Text("Ajouter un rappel") }
            if (loadingReminders) Text("Chargement…", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp)) {
                if (reminders.isEmpty()) {
                    item { Text("Aucun rappel.", style = MaterialTheme.typography.bodySmall) }
                }
                items(reminders) { record ->
                    ReminderItem(record = record, onCancel = {
                        reminderFeedback = null
                        loadingReminders = true
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) { ReminderService.cancel(context, record.id) }
                                reminders = ReminderService.list(context)
                                reminderFeedback = "Rappel #${record.id} annulé."
                            } catch (_: Exception) {
                                reminderFeedback = "L’annulation a échoué."
                            } finally {
                                loadingReminders = false
                            }
                        }
                    })
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }

    if (confirmDeleteKey) {
        AlertDialog(
            onDismissRequest = { if (!keyBusy) confirmDeleteKey = false },
            title = { Text("Supprimer la clé API ?") },
            text = {
                Text(
                    "La session en cours sera arrêtée et Jarvis ne pourra plus se connecter tant que vous n’aurez pas saisi une nouvelle clé."
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !keyBusy,
                    onClick = {
                        keyBusy = true
                        scope.launch {
                            try {
                                if (onDeleteKey()) {
                                    hasKey = false
                                    keyStatus = null
                                } else {
                                    keyStatus = "Suppression impossible. Réessayez."
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                keyStatus = "Suppression impossible. Réessayez."
                            } finally {
                                keyBusy = false
                                confirmDeleteKey = false
                            }
                        }
                    },
                ) { Text("Supprimer", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(enabled = !keyBusy, onClick = { confirmDeleteKey = false }) { Text("Annuler") }
            },
        )
    }
}

@Composable
private fun ReminderItem(record: ReminderRecord, onCancel: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(record.text, style = MaterialTheme.typography.bodyMedium)
            Text(
                "Pour le ${record.whenIso} (${record.zoneId})" +
                    if (record.approximate) " · approximatif" else " · exact",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        TextButton(onClick = onCancel) { Text("Annuler") }
    }
}

private val settingsHttp = OkHttpClient()

private suspend fun testApiKey(configStore: ConfigStore): String = withContext(Dispatchers.IO) {
    try {
        val key = validatedApiKey(configStore.getApiKey().orEmpty())
            ?: return@withContext "Aucune clé valide enregistrée."
        val body = """{"contents":[{"parts":[{"text":"Say OK"}]}]}"""
            .toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.6-flash:generateContent")
            .header("x-goog-api-key", key).post(body).build()
        settingsHttp.newCall(request).execute().use { response ->
            if (response.isSuccessful) "REST : succès (HTTP ${response.code}). La clé fonctionne pour l’API standard."
            else "REST : échec (HTTP ${response.code}). Vérifiez la clé, les quotas et l’accès au modèle de diagnostic ; cet échec ne prouve pas à lui seul que la clé est invalide."
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: IOException) {
        "Diagnostic indisponible : connexion impossible ou délai dépassé."
    } catch (_: Exception) {
        "Impossible de tester la clé enregistrée."
    }
}

internal data class LiveModelPage(val names: List<String>, val total: Int, val nextPageToken: String?)

internal fun parseLiveModelPage(body: String): LiveModelPage {
    val root = Json.parseToJsonElement(body).jsonObject
    val models = root["models"]?.jsonArray.orEmpty()
    val names = models.mapNotNull { model ->
        val obj = model.jsonObject
        val methods = obj["supportedGenerationMethods"]?.jsonArray.orEmpty()
        val name = obj["name"]?.jsonPrimitive?.contentOrNull
        name?.takeIf { it.isNotBlank() && methods.any { method -> method.jsonPrimitive.contentOrNull == "bidiGenerateContent" } }
    }.distinct()
    return LiveModelPage(names, models.size, root["nextPageToken"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() })
}

internal sealed interface LiveModelsResult {
    /** [partial] is true when paging stopped early (page limit or a repeated token). */
    data class Found(val names: List<String>, val partial: Boolean) : LiveModelsResult

    data class NoneFound(val total: Int) : LiveModelsResult
}

internal const val MAX_MODEL_PAGES = 20

/** Follows the paging tokens of ListModels, guarding against loops and endless lists. */
internal fun collectLiveModels(fetchPage: (String?) -> LiveModelPage): LiveModelsResult {
    val live = linkedSetOf<String>()
    val tokens = mutableSetOf<String>()
    var token: String? = null
    var total = 0
    var pages = 0
    do {
        val page = fetchPage(token)
        live.addAll(page.names)
        total += page.total
        pages++
        val next = page.nextPageToken
        if (next != null && (!tokens.add(next) || pages >= MAX_MODEL_PAGES)) {
            return LiveModelsResult.Found(live.toList(), partial = true)
        }
        token = next
    } while (token != null)
    return if (live.isEmpty()) LiveModelsResult.NoneFound(total) else LiveModelsResult.Found(live.toList(), partial = false)
}

private class ModelListHttpException(val code: Int) : Exception("HTTP $code")

private data class ModelListing(val models: List<String>, val message: String?)

private suspend fun listLiveModels(configStore: ConfigStore): ModelListing = withContext(Dispatchers.IO) {
    try {
        val key = validatedApiKey(configStore.getApiKey().orEmpty())
            ?: return@withContext ModelListing(emptyList(), "Aucune clé valide enregistrée.")
        val result = collectLiveModels { token ->
            val url = "https://generativelanguage.googleapis.com/v1beta/models".toHttpUrl().newBuilder()
                .addQueryParameter("pageSize", "200")
                .apply { token?.let { addQueryParameter("pageToken", it) } }.build()
            val request = Request.Builder().url(url).header("x-goog-api-key", key).build()
            settingsHttp.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw ModelListHttpException(response.code)
                parseLiveModelPage(response.body?.string().orEmpty())
            }
        }
        when (result) {
            is LiveModelsResult.NoneFound ->
                ModelListing(emptyList(), "Aucun modèle compatible Live dans ce projet (${result.total} modèles vérifiés).")
            is LiveModelsResult.Found -> when {
                result.names.isEmpty() ->
                    ModelListing(emptyList(), "Aucun modèle compatible Live trouvé dans les pages reçues.")
                result.partial ->
                    ModelListing(result.names, "Liste partielle : d’autres modèles compatibles peuvent exister.")
                else -> ModelListing(result.names, null)
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: ModelListHttpException) {
        ModelListing(emptyList(), "Liste des modèles indisponible (HTTP ${e.code}). Vérifiez la clé et réessayez plus tard.")
    } catch (_: IOException) {
        ModelListing(emptyList(), "Liste des modèles indisponible : connexion impossible ou délai dépassé.")
    } catch (_: Exception) {
        ModelListing(emptyList(), "Liste des modèles indisponible : réponse inexploitable ou clé inaccessible.")
    }
}
