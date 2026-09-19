package com.jarvis.android.ui

import androidx.compose.foundation.layout.*
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
fun SettingsScreen(configStore: ConfigStore, onBack: () -> Unit) {
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
    var reminders by remember { mutableStateOf(emptyList<ReminderRecord>()) }
    var reminderText by remember { mutableStateOf("") }
    var reminderWhen by remember { mutableStateOf("") }
    var reminderFeedback by remember { mutableStateOf<String?>(null) }
    var loadingReminders by remember { mutableStateOf(false) }

    val context = LocalContext.current
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
                supportingText = { Text("Utilisez l’identifiant exact retourné par la liste des modèles compatibles ci-dessous.") },
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
                    validatedApiKey(apiKeyField)?.let {
                        configStore.setApiKey(it)
                        apiKeyField = ""
                        testResult = "Clé enregistrée sur cet appareil."
                    }
                },
                enabled = validatedApiKey(apiKeyField) != null && !testing,
                modifier = Modifier.padding(top = 8.dp),
            ) { Text("Enregistrer la clé") }
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
                    scope.launch {
                        try { testResult = listLiveModels(configStore) } finally { testing = false }
                    }
                },
                enabled = !testing,
                modifier = Modifier.padding(top = 8.dp),
            ) { Text(if (testing) "Vérification en cours…" else "Lister les modèles compatibles Live") }
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

private suspend fun listLiveModels(configStore: ConfigStore): String = withContext(Dispatchers.IO) {
    try {
        val key = validatedApiKey(configStore.getApiKey().orEmpty())
            ?: return@withContext "Aucune clé valide enregistrée."
        val live = linkedSetOf<String>()
        val tokens = mutableSetOf<String>()
        var token: String? = null
        var total = 0
        var pages = 0
        do {
            val url = "https://generativelanguage.googleapis.com/v1beta/models".toHttpUrl().newBuilder()
                .addQueryParameter("pageSize", "200")
                .apply { token?.let { addQueryParameter("pageToken", it) } }.build()
            val request = Request.Builder().url(url).header("x-goog-api-key", key).build()
            val page = settingsHttp.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext "Liste des modèles indisponible (HTTP ${response.code}). Vérifiez la clé et réessayez plus tard."
                parseLiveModelPage(response.body?.string().orEmpty())
            }
            live.addAll(page.names)
            total += page.total
            val nextToken = page.nextPageToken
            token = nextToken
            pages++
            if (nextToken != null && (!tokens.add(nextToken) || pages >= 20)) {
                return@withContext "Liste partielle des modèles compatibles Live :\n" +
                    live.joinToString("\n").ifBlank { "Aucun modèle trouvé dans les pages reçues." }
            }
        } while (token != null)
        if (live.isEmpty()) "Aucun modèle compatible Live dans ce projet ($total modèles vérifiés)."
        else "Modèles compatibles Live :\n" + live.joinToString("\n")
    } catch (e: CancellationException) {
        throw e
    } catch (_: IOException) {
        "Liste des modèles indisponible : connexion impossible ou délai dépassé."
    } catch (_: Exception) {
        "Liste des modèles indisponible : réponse inexploitable ou clé inaccessible."
    }
}
