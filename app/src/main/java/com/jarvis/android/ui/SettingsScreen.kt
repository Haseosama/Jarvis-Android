package com.jarvis.android.ui

import com.jarvis.android.i18n.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
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
import kotlinx.coroutines.flow.first
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
    val context0 = LocalContext.current
    val assistantName by configStore.assistantName.collectAsState(initial = "JARVIS")
    val userName by configStore.userName.collectAsState(initial = "")
    val voice by configStore.voice.collectAsState(initial = "Puck")
    val model by configStore.model.collectAsState(initial = ConfigStore.DEFAULT_MODEL)
    val hue by configStore.themeHue.collectAsState(initial = 190f)
    val wakeWordEnabled by configStore.wakeWordEnabled.collectAsState(initial = false)
    val deviceControl by configStore.deviceControlEnabled.collectAsState(initial = true)
    val skipConfirmations by configStore.skipConfirmations.collectAsState(initial = false)
    val briefingOn by configStore.briefingEnabled.collectAsState(initial = true)
    val muteWhileSpeaking by configStore.muteMicWhileSpeaking.collectAsState(initial = true)
    val chatHistoryOn by configStore.chatHistoryEnabled.collectAsState(initial = true)
    val faceOn by configStore.avatarFace.collectAsState(initial = true)
    val faceModel by configStore.avatarModel.collectAsState(initial = 0)
    val skinTone by configStore.avatarSkin.collectAsState(initial = 7)
    val lipTone by configStore.avatarLips.collectAsState(initial = 0)
    val capTone by configStore.avatarCap.collectAsState(initial = 0)
    val speechLanguage by configStore.speechLanguage.collectAsState(initial = "")
    var langMenuOpen by remember { mutableStateOf(false) }
    val workFolder by configStore.workFolder.collectAsState(initial = "")
    val homeAssistantUrl by configStore.homeAssistantUrl.collectAsState(initial = "")
    var haUrlField by remember(homeAssistantUrl) { mutableStateOf(homeAssistantUrl) }
    val pluginStore = remember { (context0.applicationContext as com.jarvis.android.JarvisApp).container.pluginStore }
    var pluginTick by remember { mutableStateOf(0) }
    var pluginToRemove by remember { mutableStateOf<String?>(null) }
    var pluginMessage by remember { mutableStateOf<String?>(null) }
    val pickPlugin = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                pluginMessage = withContext(Dispatchers.IO) {
                    try {
                        val text = context0.contentResolver.openInputStream(uri)?.use { String(it.readNBytes(com.jarvis.android.plugins.MAX_PLUGIN_BYTES + 1)) }
                        if (text == null) tr("Impossible de lire ce fichier.") else pluginStore.install(text) ?: tr("Plugin installé. Il est actif dès la prochaine session vocale.")
                    } catch (_: Exception) {
                        tr("Impossible de lire ce fichier.")
                    }
                }
                pluginTick++
            }
        }
    }
    val pickFolder = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                context0.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                scope.launch { configStore.setWorkFolder(uri.toString()) }
            } catch (_: Exception) {
            }
        }
    }
    val sessionLog = remember { (context0.applicationContext as com.jarvis.android.JarvisApp).container.sessionLog }
    var sessions by remember { mutableStateOf(emptyList<com.jarvis.android.core.SessionRecord>()) }
    val proactiveOn by configStore.proactiveEnabled.collectAsState(initial = false)
    val wakeSensitivity by configStore.wakeSensitivity.collectAsState(initial = 1)
    var serviceOn by remember { mutableStateOf(false) }
    val wakeManager = remember { (context0.applicationContext as com.jarvis.android.JarvisApp).container.wakeModel }
    var wakeInstalled by remember { mutableStateOf(wakeManager.installed()) }
    var wakeSelected by remember { mutableStateOf(wakeManager.selected()) }
    var wakeMessage by remember { mutableStateOf<String?>(null) }
    val pickWakeModel = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val bytes = withContext(kotlinx.coroutines.Dispatchers.IO) {
                try { context0.contentResolver.openInputStream(uri)?.use { it.readBytes() } } catch (_: Exception) { null }
            }
            wakeMessage = if (bytes == null) tr("Fichier illisible.") else wakeManager.importCustom(bytes)
            wakeSelected = wakeManager.selected()
            (context0.applicationContext as com.jarvis.android.JarvisApp).container.engine.refreshWakeDetection()
        }
    }
    var wakeProgress by remember { mutableStateOf<Int?>(null) }
    val localModelStore = remember { (context0.applicationContext as com.jarvis.android.JarvisApp).container.localModelStore }
    var localInstalled by remember { mutableStateOf(localModelStore.installed()) }
    var localSizeMb by remember { mutableStateOf(localModelStore.sizeMb()) }
    var localMessage by remember { mutableStateOf<String?>(null) }
    var localImporting by remember { mutableStateOf(false) }
    val pickLocalModel = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            localImporting = true
            localMessage = withContext(kotlinx.coroutines.Dispatchers.IO) {
                val tmp = java.io.File(context0.cacheDir, "import_local_model.tmp")
                try {
                    val ok = context0.contentResolver.openInputStream(uri)?.use { input ->
                        tmp.outputStream().use { output -> input.copyTo(output, bufferSize = 1 shl 20) }
                        true
                    } ?: false
                    if (!ok) "Fichier illisible." else localModelStore.import(tmp)
                } catch (_: Exception) {
                    "Import impossible (place manquante sur le téléphone ?)."
                } finally {
                    tmp.delete()
                }
            }
            localInstalled = localModelStore.installed()
            localSizeMb = localModelStore.sizeMb()
            localImporting = false
        }
    }
    var assistantNameField by remember(assistantName) { mutableStateOf(assistantName) }
    var userNameField by remember(userName) { mutableStateOf(userName) }
    var modelField by remember(model) { mutableStateOf(model) }
    val restModel by configStore.restModel.collectAsState(initial = ConfigStore.DEFAULT_REST_MODEL)
    var restModelField by remember(restModel) { mutableStateOf(restModel) }
    val ttsModel by configStore.ttsModel.collectAsState(initial = "")
    var ttsModelField by remember(ttsModel) { mutableStateOf(ttsModel) }
    var apiKeyField by remember { mutableStateOf("") }
    var voiceMenuOpen by remember { mutableStateOf(false) }
    val inputKey by configStore.audioInputKey.collectAsState(initial = "")
    val outputKey by configStore.audioOutputKey.collectAsState(initial = "")
    val carMode by configStore.carAudioMode.collectAsState(initial = 0)
    val offlineMode by configStore.offlineMode.collectAsState(initial = 0)
    var inMenuOpen by remember { mutableStateOf(false) }
    var outMenuOpen by remember { mutableStateOf(false) }
    var inputs by remember { mutableStateOf(emptyList<com.jarvis.android.core.AudioDeviceChoice>()) }
    var outputs by remember { mutableStateOf(emptyList<com.jarvis.android.core.AudioDeviceChoice>()) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    var hasKey by remember { mutableStateOf<Boolean?>(null) }
    var keySlot by remember { mutableStateOf(1) }
    var filledSlots by remember { mutableStateOf(List(ConfigStore.MAX_API_KEYS) { false }) }
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
    var hasHaToken by remember { mutableStateOf<Boolean?>(null) }
    var haTokenField by remember { mutableStateOf("") }
    var haStatus by remember { mutableStateOf<String?>(null) }
    var haBusy by remember { mutableStateOf(false) }
    var haTesting by remember { mutableStateOf(false) }
    var haTestResult by remember { mutableStateOf<String?>(null) }

    val context = LocalContext.current
    LaunchedEffect(Unit) {
        while (true) {
            sessions = withContext(Dispatchers.IO) { sessionLog.entries() }
            kotlinx.coroutines.delay(2_000)
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            serviceOn = com.jarvis.android.device.JarvisAccessibilityService.instance != null
            kotlinx.coroutines.delay(1_000)
        }
    }
    LaunchedEffect(Unit) {
        val slots = withContext(Dispatchers.IO) {
            try { configStore.keySlotsFilled() } catch (_: Exception) { null }
        }
        filledSlots = slots ?: filledSlots
        hasKey = slots?.any { it } ?: false
    }
    LaunchedEffect(Unit) {
        hasHaToken = withContext(Dispatchers.IO) { try { configStore.hasHomeAssistantToken() } catch (_: Exception) { null } }
    }
    LaunchedEffect(Unit) {
        loadingReminders = true
        withContext(Dispatchers.IO) {
            try {
                reminders = ReminderService.list(context)
            } catch (_: Exception) {
                reminderFeedback = tr("Impossible de charger les rappels.")
            } finally {
                loadingReminders = false
            }
        }
    }

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onBackground,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                title = { Text(tr("Paramètres"), style = MaterialTheme.typography.titleLarge) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, tr("Retour")) } },
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding().padding(16.dp).verticalScroll(rememberScrollState())) {
            SettingsCard(tr("Identité"), Icons.Filled.Person, initiallyExpanded = true) {
            OutlinedTextField(
                value = assistantNameField,
                onValueChange = { assistantNameField = it; scope.launch { configStore.setAssistantName(it) } },
                label = { Text(tr("Nom de l’assistant")) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            OutlinedTextField(
                value = userNameField,
                onValueChange = { userNameField = it; scope.launch { configStore.setUserName(it) } },
                label = { Text(tr("Votre nom")) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            }
            SettingsCard(tr("Voix"), Icons.Filled.RecordVoiceOver, initiallyExpanded = true) {
            ExposedDropdownMenuBox(
                expanded = voiceMenuOpen,
                onExpandedChange = { voiceMenuOpen = it },
                modifier = Modifier.padding(top = 8.dp),
            ) {
                OutlinedTextField(
                    value = voice,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(tr("Voix Gemini")) },
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
            val languages = listOf("" to tr("Automatique (peut changer sur demande)"), "fr-FR" to "Français", "en-US" to "English", "fil-PH" to "Filipino")
            ExposedDropdownMenuBox(
                expanded = langMenuOpen,
                onExpandedChange = { langMenuOpen = it },
                modifier = Modifier.padding(top = 8.dp),
            ) {
                OutlinedTextField(
                    value = languages.firstOrNull { it.first == speechLanguage }?.second ?: speechLanguage,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(tr("Langue de la voix")) },
                    modifier = Modifier.menuAnchor().fillMaxWidth(),
                )
                ExposedDropdownMenu(expanded = langMenuOpen, onDismissRequest = { langMenuOpen = false }) {
                    languages.forEach { (code, label) ->
                        DropdownMenuItem(text = { Text(label) }, onClick = {
                            langMenuOpen = false
                            scope.launch { configStore.setSpeechLanguage(code) }
                        })
                    }
                }
            }
            Text(
                tr("Fixer la langue garde un accent stable, mais Jarvis ne pourra plus passer à une autre langue sur demande. S’applique à la prochaine session."),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) {
                Text(
                    tr("Couper le micro pendant que Jarvis parle (haut-parleur). Évite qu’il s’interrompe à cause de son propre écho ; avec un casque, vous pouvez toujours l’interrompre."),
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
                )
                Switch(checked = muteWhileSpeaking, onCheckedChange = { scope.launch { configStore.setMuteMicWhileSpeaking(it) } })
            }
            }
            SettingsCard(tr("Périphériques audio"), Icons.Filled.Headset, initiallyExpanded = false) {
            Text(
                tr("Automatique laisse Android choisir. Un périphérique choisi mais débranché est ignoré : Jarvis revient alors au téléphone. Le changement s’applique à la prochaine session ou au prochain enregistrement."),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(tr("Mode voiture (Android Auto)"), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                listOf(0 to "Automatique", 1 to "Activé", 2 to "Désactivé").forEach { (v, label) ->
                    FilterChip(selected = carMode == v, onClick = { scope.launch { configStore.setCarAudioMode(v) } }, label = { Text(tr(label)) })
                }
            }
            Text(
                tr("En voiture, le micro est coupé pendant que Jarvis parle (sinon il s’entend dans l’habitacle et se répond), le micro du téléphone est utilisé sans toucher au Bluetooth, et la musique est seulement baissée pendant qu’il parle au lieu d’être arrêtée. « Automatique » détecte Android Auto. Le changement s’applique à la prochaine session."),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(tr("Mode hors ligne"), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                listOf(0 to "Automatique", 1 to "Toujours", 2 to "Jamais").forEach { (v, label) ->
                    FilterChip(selected = offlineMode == v, onClick = { scope.launch { configStore.setOfflineMode(v) } }, label = { Text(tr(label)) })
                }
            }
            Text(
                tr("Sans réseau (ou si Gemini est injoignable), Jarvis écoute et parle avec la reconnaissance et la voix du téléphone, et comprend des commandes simples : ouvrir une application, appeler, volume, luminosité, lampe, musique, minuteur, heure, date, batterie, réglages. Il faut le pack de langue français hors ligne (Services vocaux Google). Dites « aide » pour la liste."),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
            ExposedDropdownMenuBox(
                expanded = inMenuOpen,
                onExpandedChange = { inMenuOpen = it; if (it) inputs = com.jarvis.android.core.AudioRoute.choices(context, inputs = true) },
                modifier = Modifier.padding(top = 8.dp),
            ) {
                OutlinedTextField(
                    value = inputs.firstOrNull { it.key == inputKey }?.label ?: if (inputKey.isBlank()) tr("Automatique") else tr("Périphérique absent"),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Microphone") },
                    modifier = Modifier.menuAnchor().fillMaxWidth(),
                )
                ExposedDropdownMenu(expanded = inMenuOpen, onDismissRequest = { inMenuOpen = false }) {
                    DropdownMenuItem(text = { Text(tr("Automatique")) }, onClick = { inMenuOpen = false; scope.launch { configStore.setAudioInputKey("") } })
                    inputs.forEach { d ->
                        DropdownMenuItem(text = { Text(d.label) }, onClick = { inMenuOpen = false; scope.launch { configStore.setAudioInputKey(d.key) } })
                    }
                }
            }
            ExposedDropdownMenuBox(
                expanded = outMenuOpen,
                onExpandedChange = { outMenuOpen = it; if (it) outputs = com.jarvis.android.core.AudioRoute.choices(context, inputs = false) },
                modifier = Modifier.padding(top = 8.dp),
            ) {
                OutlinedTextField(
                    value = outputs.firstOrNull { it.key == outputKey }?.label ?: if (outputKey.isBlank()) tr("Automatique") else tr("Périphérique absent"),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(tr("Sortie audio")) },
                    modifier = Modifier.menuAnchor().fillMaxWidth(),
                )
                ExposedDropdownMenu(expanded = outMenuOpen, onDismissRequest = { outMenuOpen = false }) {
                    DropdownMenuItem(text = { Text(tr("Automatique")) }, onClick = { outMenuOpen = false; scope.launch { configStore.setAudioOutputKey("") } })
                    outputs.forEach { d ->
                        DropdownMenuItem(text = { Text(d.label) }, onClick = { outMenuOpen = false; scope.launch { configStore.setAudioOutputKey(d.key) } })
                    }
                }
            }
            }
            SettingsCard(tr("IA locale (hors ligne)"), Icons.Filled.Memory, initiallyExpanded = false) {
            val localAiEnabled by configStore.localAiEnabled.collectAsState(initial = true)
            Text(
                tr("Sans réseau, Jarvis ne connaît que les commandes fixes ci-dessus. Pour qu’il puisse aussi répondre à une vraie question hors ligne, il peut utiliser un petit modèle (Gemma) installé sur le téléphone : tout tourne sur l’appareil, rien n’est envoyé où que ce soit."),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                tr("Les poids de Gemma sont soumis à une licence Google sur Hugging Face : Jarvis ne peut pas les télécharger lui-même. Sur huggingface.co/litert-community/Gemma3-1B-IT, acceptez la licence puis téléchargez « gemma3-1b-it-int4.task » (≈ 530 Mo) dans le navigateur du téléphone, puis importez-le ici. Nettement moins capable que Gemini ; je n’ai pas pu tester de vraies réponses, faute d’accès à ce fichier protégé pendant le développement."),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
            )
            if (localInstalled) {
                Text(
                    trf("Modèle installé (≈ {0} Mo).", localSizeMb ?: 0L),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text(tr("L’utiliser pour ce qui n’est pas une commande connue"), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Switch(checked = localAiEnabled, onCheckedChange = { scope.launch { configStore.setLocalAiEnabled(it) } })
                }
                OutlinedButton(
                    onClick = { localModelStore.remove(); localInstalled = false; localSizeMb = null; localMessage = null },
                    modifier = Modifier.padding(top = 8.dp),
                ) { Text(tr("Supprimer le modèle")) }
            } else {
                OutlinedButton(
                    enabled = !localImporting,
                    onClick = { pickLocalModel.launch(arrayOf("*/*")) },
                    modifier = Modifier.padding(top = 8.dp),
                ) { Text(if (localImporting) tr("Import en cours…") else tr("Importer le modèle (.task)")) }
            }
            localMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp)) }
            }
            SettingsCard(tr("Apparence"), Icons.Filled.Palette, initiallyExpanded = false) {
            Text(tr("Langue de l’interface"), style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)) {
                listOf(Lang.FRENCH to "Français", Lang.ENGLISH_CODE to "English").forEach { (code, label) ->
                    FilterChip(
                        selected = Lang.code == code,
                        onClick = { Lang.set(context0, code) },
                        label = { Text(label) },
                    )
                }
            }
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                Text(
                    tr("Visage holographique au centre de l’écran (sinon le cœur lumineux). Ses lèvres suivent la voix de Jarvis."),
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
                )
                Switch(checked = faceOn, onCheckedChange = { scope.launch { configStore.setAvatarFace(it) } })
            }
            Text(tr("Visage"), style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp, bottom = 8.dp).horizontalScroll(rememberScrollState())) {
                com.jarvis.android.avatar.AVATAR_FACES.forEachIndexed { v, face ->
                    FilterChip(selected = faceModel == v, onClick = { scope.launch { configStore.setAvatarModel(v) } }, label = { Text(tr(face.label)) })
                }
            }
            Text(tr("Peau"), style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp, bottom = 8.dp).horizontalScroll(rememberScrollState())) {
                listOf(0 to "Réseau lumineux", 5 to "Hologramme", 6 to "Hologramme + cheveux", 7 to "Hologramme bleu", 1 to "Claire", 2 to "Mate", 3 to "Bronzée", 4 to "Foncée").forEach { (v, label) ->
                    FilterChip(selected = skinTone == v, onClick = { scope.launch { configStore.setAvatarSkin(v) } }, label = { Text(tr(label)) })
                }
            }
            Text(tr("Casquette"), style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp, bottom = 12.dp).horizontalScroll(rememberScrollState())) {
                listOf(0 to "Aucune", 1 to "Noire", 2 to "Bleue", 3 to "Rouge", 4 to "Blanche", 5 to "Kaki", 6 to "Gris-vert emblème").forEach { (v, label) ->
                    FilterChip(selected = capTone == v, onClick = { scope.launch { configStore.setAvatarCap(v) } }, label = { Text(tr(label)) })
                }
            }
            Text(tr("Lèvres"), style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp, bottom = 12.dp).horizontalScroll(rememberScrollState())) {
                listOf(0 to "Naturelles", 1 to "Rose", 2 to "Rouge", 3 to "Prune", 4 to "Corail").forEach { (v, label) ->
                    FilterChip(selected = lipTone == v, onClick = { scope.launch { configStore.setAvatarLips(v) } }, label = { Text(tr(label)) })
                }
            }
            Text(
                tr("Visage adapté de Mark-LIV (FatihMakes, licence CC BY-NC 4.0 : usage non commercial) ; tête : scan de Lee Perry-Smith (CC BY 3.0), remodelé pour les visages Léa et Marc ; repères du visage : MediaPipe (Apache-2.0)."),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            Text(tr("Teinte de l’interface"), style = MaterialTheme.typography.bodySmall)
            Slider(
                value = hue,
                onValueChange = { scope.launch { configStore.setThemeHue(it) } },
                valueRange = 0f..360f,
                modifier = Modifier.padding(top = 8.dp),
            )
            }
            SettingsCard(tr("Mot d’activation (« Hey Jarvis »)"), Icons.Filled.Hearing, initiallyExpanded = false) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(tr("Écouter « Hey Jarvis » même quand l’appli est fermée"), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Switch(checked = wakeWordEnabled, onCheckedChange = { scope.launch { configStore.setWakeWordEnabled(it) } })
            }
            Text(
                if (wakeInstalled) tr("Détection hors ligne : modèles openWakeWord installés ✓ (les mêmes que la version bureau). Aucune connexion n’est utilisée pendant l’écoute ; environ deux secondes d’écoute sont nécessaires après le démarrage.")
                else tr("Détection actuelle : reconnaissance vocale d’Android, approximative (elle peut passer par le réseau). Pour une vraie détection hors ligne, téléchargez les modèles openWakeWord (environ 4 Mo, depuis github.com/dscripka/openWakeWord). L’écoute reprend automatiquement en veille."),
                style = MaterialTheme.typography.bodySmall,
            )
            if (!wakeInstalled) {
                OutlinedButton(
                    enabled = wakeProgress == null,
                    onClick = {
                        wakeProgress = 0
                        wakeMessage = null
                        scope.launch {
                            val error = wakeManager.download { wakeProgress = it }
                            wakeProgress = null
                            wakeInstalled = wakeManager.installed()
                            wakeMessage = error
                            (context0.applicationContext as com.jarvis.android.JarvisApp).container.engine.refreshWakeDetection()
                        }
                    },
                    modifier = Modifier.padding(top = 8.dp),
                ) { Text(wakeProgress?.let { trf("Téléchargement… {0} %", it) } ?: tr("Télécharger les modèles (≈ 4 Mo)")) }
            } else {
                OutlinedButton(
                    onClick = {
                        wakeManager.remove()
                        wakeInstalled = false
                        wakeSelected = wakeManager.selected()
                        (context0.applicationContext as com.jarvis.android.JarvisApp).container.engine.refreshWakeDetection()
                    },
                    modifier = Modifier.padding(top = 8.dp),
                ) { Text(tr("Supprimer les modèles")) }
            }
            if (wakeInstalled) {
                Text(tr("Phrase d’activation"), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                    com.jarvis.android.wake.WAKE_PRESETS.take(2).forEach { preset ->
                        FilterChip(selected = wakeSelected == preset.file, enabled = wakeProgress == null, label = { Text(preset.label) }, onClick = {
                            scope.launch {
                                wakeMessage = wakeManager.downloadPreset(preset)
                                wakeSelected = wakeManager.selected()
                                (context0.applicationContext as com.jarvis.android.JarvisApp).container.engine.refreshWakeDetection()
                            }
                        })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                    com.jarvis.android.wake.WAKE_PRESETS.drop(2).forEach { preset ->
                        FilterChip(selected = wakeSelected == preset.file, enabled = wakeProgress == null, label = { Text(preset.label) }, onClick = {
                            scope.launch {
                                wakeMessage = wakeManager.downloadPreset(preset)
                                wakeSelected = wakeManager.selected()
                                (context0.applicationContext as com.jarvis.android.JarvisApp).container.engine.refreshWakeDetection()
                            }
                        })
                    }
                    FilterChip(
                        selected = wakeSelected == com.jarvis.android.wake.WAKE_FILE_CUSTOM,
                        label = { Text(tr("Mon modèle")) },
                        onClick = { pickWakeModel.launch(arrayOf("*/*")) },
                    )
                }
                Text(
                    trf("Les phrases proposées sont celles fournies par openWakeWord (téléchargées à la demande, environ 200 Ko chacune). Une autre phrase, comme « Debout Jarvis », demande un modèle entraîné exprès : entraînez-le avec le carnet « automatic_model_training » d’openWakeWord (github.com/dscripka/openWakeWord), puis importez le fichier .tflite avec « Mon modèle ». Je n’ai pas pu entraîner ni tester un tel modèle ici. Actuellement : {0}.", wakeManager.label(wakeSelected)),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Text(tr("Sensibilité du mot d’activation"), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                listOf(tr("Prudente"), tr("Normale"), tr("Sensible")).forEachIndexed { level, label ->
                    FilterChip(
                        selected = wakeSensitivity == level,
                        onClick = { scope.launch { configStore.setWakeSensitivity(level) } },
                        label = { Text(label) },
                    )
                }
            }
            Text(
                tr("Mesuré avec des voix de synthèse : une voix anglaise déclenche presque à coup sûr, une voix française lisant « Hey Jarvis » avec l’accent est moins bien reconnue, et « Hey Travis » peut parfois déclencher. Si Jarvis ne réagit pas à votre voix, passez en « Sensible » ; s’il se réveille tout seul, en « Prudente ». Non testé avec votre voix."),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
            wakeMessage?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            }
            WakeLearnCard(onChanged = {
                wakeSelected = wakeManager.selected()
                (context0.applicationContext as com.jarvis.android.JarvisApp).container.engine.refreshWakeDetection()
            })
            SettingsCard(tr("Position (météo)"), Icons.Filled.LocationOn, initiallyExpanded = false) {
            var locationGranted by remember { mutableStateOf(com.jarvis.android.weather.hasLocationPermission(context0)) }
            val askLocation = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
                locationGranted = granted || com.jarvis.android.weather.hasLocationPermission(context0)
            }
            Text(
                if (locationGranted) tr("Position autorisée ✓ : « quel temps fait-il ? » sans ville donne la météo de l’endroit où vous êtes.")
                else tr("Position non autorisée : Jarvis ne connaît pas votre position. Sans elle, il faut lui dire le nom de la ville."),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
            if (!locationGranted) {
                OutlinedButton(onClick = { askLocation.launch(android.Manifest.permission.ACCESS_COARSE_LOCATION) }, modifier = Modifier.padding(top = 8.dp)) {
                    Text(tr("Autoriser la position"))
                }
            }
            Text(
                tr("Position approximative, lue seulement au moment d’une demande de météo, envoyée à Open-Meteo pour obtenir les conditions et jamais enregistrée. Fiable quand Jarvis est ouvert ; en arrière-plan Android peut la refuser."),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
            }
            SettingsCard(tr("Contacts (appels et SMS)"), Icons.Filled.Person, initiallyExpanded = false) {
            var contactsGranted by remember { mutableStateOf(androidx.core.content.ContextCompat.checkSelfPermission(context0, android.Manifest.permission.READ_CONTACTS) == android.content.pm.PackageManager.PERMISSION_GRANTED) }
            val askContacts = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
                contactsGranted = granted
            }
            Text(
                if (contactsGranted) tr("Contacts autorisés ✓ : « appelle Maman » ouvre le numéroteur avec son numéro, « écris à Paul » ouvre un brouillon de SMS. Vous appuyez vous-même sur appeler ou sur envoyer.")
                else tr("Contacts non autorisés : Jarvis ne peut pas trouver un numéro par le nom."),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
            if (!contactsGranted) {
                OutlinedButton(onClick = { askContacts.launch(android.Manifest.permission.READ_CONTACTS) }, modifier = Modifier.padding(top = 8.dp)) {
                    Text(tr("Autoriser les contacts"))
                }
            }
            Text(
                tr("Les contacts restent sur le téléphone : Jarvis cherche lui-même le numéro et ne le transmet pas à Gemini. Seuls le nom demandé et, s’il y a plusieurs correspondances, les noms proposés lui sont envoyés."),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
            }
            SettingsCard(tr("Notifications"), Icons.Filled.NotificationsActive, initiallyExpanded = false) {
            var notifEnabled by remember { mutableStateOf(com.jarvis.android.notifications.JarvisNotificationListener.isEnabled(context0)) }
            val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
            androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
                val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                    if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                        notifEnabled = com.jarvis.android.notifications.JarvisNotificationListener.isEnabled(context0)
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }
            Text(
                if (notifEnabled) tr("Lecture des notifications activée ✓ : « qu’est-ce que j’ai manqué ? » résume les dernières notifications. Lecture seule : Jarvis ne peut ni les ouvrir, ni y répondre, ni les supprimer.")
                else tr("Lecture des notifications désactivée : Jarvis ne voit pas vos notifications."),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
            OutlinedButton(
                onClick = {
                    try {
                        context0.startActivity(android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (_: Exception) {
                    }
                },
                modifier = Modifier.padding(top = 8.dp),
            ) { Text(if (notifEnabled) tr("Gérer l’accès aux notifications") else tr("Activer l’accès aux notifications")) }
            Text(
                tr("Android exige que vous l’activiez vous-même dans « Accès aux notifications ». Si l’option est grisée : Paramètres > Applications > Jarvis > ⋮ > Autoriser les paramètres restreints. Les notifications sont gardées en mémoire seulement (les dernières dizaines, jamais écrites sur le téléphone). Quand vous posez la question, leur contenu est envoyé à Gemini ; les messages contenant un code ou un mot de passe sont masqués avant."),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
            }
            SettingsCard(tr("Agenda"), Icons.Filled.Alarm, initiallyExpanded = false) {
            var calendarGranted by remember { mutableStateOf(com.jarvis.android.calendar.hasCalendarPermission(context0)) }
            val askCalendar = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
                calendarGranted = granted
            }
            Text(
                if (calendarGranted) tr("Agenda autorisé ✓ : « qu’est-ce que j’ai demain ? » lit vos événements, et le briefing du matin les mentionne. « Ajoute un rendez-vous » ouvre le formulaire prérempli : vous enregistrez vous-même.")
                else tr("Agenda non autorisé : Jarvis ne peut pas lire vos événements."),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
            if (!calendarGranted) {
                OutlinedButton(onClick = { askCalendar.launch(android.Manifest.permission.READ_CALENDAR) }, modifier = Modifier.padding(top = 8.dp)) {
                    Text(tr("Autoriser l’agenda"))
                }
            }
            Text(
                tr("Seuls l’heure et le titre des événements sont lus (ni description, ni invités). Quand vous posez la question, ils sont envoyés à Gemini pour vous répondre ; avec le briefing du matin activé, ceux du jour le sont au début de la première session."),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
            var calendarWriteGranted by remember { mutableStateOf(com.jarvis.android.calendar.hasCalendarWritePermission(context0)) }
            val askCalendarWrite = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
                calendarWriteGranted = granted
            }
            val calendarAutoCreate by configStore.calendarAutoCreate.collectAsState(initial = false)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            ) {
                Text(tr("Créer les événements sans confirmation quand je dis « ajoute-le »"), modifier = Modifier.weight(1f))
                Switch(checked = calendarAutoCreate, onCheckedChange = { value ->
                    scope.launch { configStore.setCalendarAutoCreate(value) }
                    if (value && !calendarWriteGranted) askCalendarWrite.launch(android.Manifest.permission.WRITE_CALENDAR)
                })
            }
            Text(
                tr("Toujours désactivé par défaut. Une fois activé et une fois l’agenda modifiable autorisé, l’événement est ajouté directement à votre agenda principal (celui dont vous êtes le propriétaire) au lieu d’ouvrir le formulaire à valider vous-même."),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
            }
            SettingsCard(tr("Accès rapide"), Icons.Filled.Bolt, initiallyExpanded = false) {
            var quickMessage by remember { mutableStateOf<String?>(null) }
            Text(
                tr("Lancez Jarvis d’un geste : comme assistant du téléphone (appui long sur le bouton d’accueil ou d’alimentation, selon le modèle), avec une tuile des réglages rapides, avec le widget ou le raccourci de l’écran d’accueil, ou depuis « Partager » dans n’importe quelle appli."),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
            OutlinedButton(
                onClick = {
                    quickMessage = null
                    try {
                        context0.startActivity(android.content.Intent(android.provider.Settings.ACTION_VOICE_INPUT_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (_: Exception) {
                        try {
                            context0.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                        } catch (_: Exception) {
                            quickMessage = tr("Impossible d’ouvrir les réglages de l’assistant : cherchez « Assistant numérique » dans Paramètres > Applications par défaut.")
                        }
                    }
                },
                modifier = Modifier.padding(top = 8.dp),
            ) { Text(tr("Choisir Jarvis comme assistant")) }
            OutlinedButton(
                onClick = {
                    quickMessage = null
                    if (android.os.Build.VERSION.SDK_INT >= 33) {
                        val manager = context0.getSystemService(android.app.StatusBarManager::class.java)
                        manager.requestAddTileService(
                            android.content.ComponentName(context0, com.jarvis.android.tile.JarvisTileService::class.java),
                            "Jarvis",
                            android.graphics.drawable.Icon.createWithResource(context0, com.jarvis.android.R.drawable.ic_tile),
                            context0.mainExecutor,
                        ) { result ->
                            quickMessage = when (result) {
                                android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> tr("Tuile ajoutée.")
                                android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> tr("Tuile déjà présente.")
                                else -> tr("Ajout de la tuile refusé ou impossible.")
                            }
                        }
                    } else {
                        quickMessage = tr("Ouvrez les réglages rapides, touchez le crayon et ajoutez la tuile « Jarvis ».")
                    }
                },
                modifier = Modifier.padding(top = 4.dp),
            ) { Text(tr("Ajouter la tuile aux réglages rapides")) }
            Text(
                tr("Android décide de ce que fait le geste d’assistant : selon la marque (Xiaomi, Samsung…), il peut falloir l’activer dans les réglages du téléphone. Sur l’écran verrouillé, Android demande d’abord le déverrouillage. Jarvis n’est jamais choisi tout seul : c’est à vous de le faire."),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
            )
            quickMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)) }
            }
            SettingsCard("Plugins", Icons.Filled.Extension, initiallyExpanded = false) {
            Text(
                tr("Ajoutez des compétences sans code : un fichier JSON décrit un appel web (HTTPS), un lien à ouvrir ou une routine d’outils existants. Jarvis n’exécute jamais de code téléchargé. Voir le README pour le format."),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
            val installed = remember(pluginTick) { com.jarvis.android.actions.ToolRegistry.pluginTools().filterIsInstance<com.jarvis.android.plugins.PluginTool>() }
            if (installed.isEmpty()) {
                Text(tr("Aucun plugin installé."), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
            }
            installed.forEach { plugin ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(plugin.name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                        Text(plugin.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    OutlinedButton(onClick = { pluginToRemove = plugin.name }) { Text(tr("Désinstaller")) }
                }
            }
            val catalog = remember { com.jarvis.android.plugins.readCatalog(context0, com.jarvis.android.actions.ToolRegistry.builtInNames()) }
            val installedNames = installed.map { it.name }.toSet()
            var catalogExpanded by rememberSaveable { mutableStateOf(false) }
            var catalogQuery by rememberSaveable { mutableStateOf("") }
            val catalogArrow by animateFloatAsState(if (catalogExpanded) 180f else 0f, label = "catalogArrow")
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp).clickable { catalogExpanded = !catalogExpanded },
            ) {
                Text(trf("Catalogue intégré ({0})", catalog.size), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                Icon(
                    Icons.Filled.ExpandMore,
                    contentDescription = if (catalogExpanded) tr("Replier") else tr("Déplier"),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.rotate(catalogArrow),
                )
            }
            AnimatedVisibility(visible = catalogExpanded) {
                Column(Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = catalogQuery,
                        onValueChange = { catalogQuery = it },
                        label = { Text(tr("Chercher un plugin")) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                    val filtered = remember(catalog, catalogQuery) {
                        val q = catalogQuery.trim()
                        if (q.isEmpty()) catalog else catalog.filter { it.name.contains(q, ignoreCase = true) || it.description.contains(q, ignoreCase = true) }
                    }
                    if (filtered.isEmpty()) {
                        Text(tr("Aucun plugin ne correspond."), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
                    }
                    filtered.forEach { entry ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(entry.name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                                Text(entry.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (entry.name in installedNames) {
                                OutlinedButton(onClick = { pluginToRemove = entry.name }, modifier = Modifier.padding(start = 8.dp)) { Text(tr("Désinstaller")) }
                            } else {
                                FilledTonalButton(onClick = {
                                    scope.launch {
                                        pluginMessage = withContext(Dispatchers.IO) { pluginStore.install(entry.json) } ?: trf("« {0} » installé. Il est actif dès la prochaine session vocale.", entry.name)
                                        pluginTick++
                                    }
                                }, modifier = Modifier.padding(start = 8.dp)) { Text(tr("Installer")) }
                            }
                        }
                    }
                }
            }
            pluginToRemove?.let { toRemove ->
                AlertDialog(
                    onDismissRequest = { pluginToRemove = null },
                    title = { Text(trf("Désinstaller « {0} » ?", toRemove)) },
                    text = { Text(tr("Le plugin est retiré de Jarvis. Vous pourrez le réinstaller depuis le catalogue.")) },
                    confirmButton = {
                        TextButton(onClick = {
                            pluginStore.remove(toRemove)
                            pluginTick++
                            pluginMessage = trf("« {0} » désinstallé.", toRemove)
                            pluginToRemove = null
                        }) { Text(tr("Désinstaller"), color = MaterialTheme.colorScheme.error) }
                    },
                    dismissButton = { TextButton(onClick = { pluginToRemove = null }) { Text(tr("Annuler")) } },
                )
            }
            OutlinedButton(onClick = { pickPlugin.launch(arrayOf("application/json", "text/plain", "*/*")) }, modifier = Modifier.padding(top = 12.dp)) {
                Text(tr("Importer un plugin (JSON)"))
            }
            pluginMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)) }
            }
            SettingsCard(tr("Dossier de travail (fichiers)"), Icons.Filled.Folder, initiallyExpanded = false) {
            Text(
                if (workFolder.isBlank()) tr("Aucun dossier choisi : Jarvis ne touche à aucun fichier.")
                else trf("Dossier choisi : {0}", android.net.Uri.decode(workFolder.substringAfterLast("tree/"))),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                OutlinedButton(onClick = { pickFolder.launch(null) }) { Text(if (workFolder.isBlank()) tr("Choisir un dossier") else tr("Changer de dossier")) }
                if (workFolder.isNotBlank()) {
                    OutlinedButton(onClick = {
                        try {
                            context0.contentResolver.releasePersistableUriPermission(
                                android.net.Uri.parse(workFolder),
                                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                            )
                        } catch (_: Exception) {
                        }
                        scope.launch { configStore.setWorkFolder("") }
                    }) { Text(tr("Retirer l’accès")) }
                }
            }
            Text(
                tr("Jarvis peut lister, lire, chercher, créer, modifier, renommer, déplacer, copier, supprimer (corbeille dans le dossier) et ranger des fichiers, seulement dans ce dossier. La suppression, l’écriture et le rangement demandent votre confirmation ; tout peut être annulé."),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
            }
            MessageSendCard(configStore)
            MeetingNotesCard()
            WatchesCard()
            GoogleCard(configStore)
            UpdateCard()
            SettingsCard(tr("Sauvegarde de la mémoire"), Icons.Filled.Folder, initiallyExpanded = false) {
            Text(
                tr("Enregistre ce que Jarvis sait de vous (identité, préférences, notes, résumés récents) dans un fichier, ou le restaure sur un autre téléphone. Le fichier n’est pas chiffré : gardez-le en lieu sûr. Une restauration remplace la mémoire actuelle."),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
            val memory = remember { (context0.applicationContext as com.jarvis.android.JarvisApp).container.memoryManager }
            var backupMessage by remember { mutableStateOf<String?>(null) }
            val exportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")) { uri ->
                if (uri != null) scope.launch {
                    backupMessage = try {
                        val text = memory.exportJson()
                        withContext(kotlinx.coroutines.Dispatchers.IO) {
                            context0.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) } ?: error("flux")
                        }
                        tr("Sauvegarde enregistrée.")
                    } catch (_: Exception) {
                        tr("Impossible d’écrire la sauvegarde.")
                    }
                }
            }
            val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) scope.launch {
                    val text = withContext(kotlinx.coroutines.Dispatchers.IO) {
                        try { context0.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } } catch (_: Exception) { null }
                    }
                    backupMessage = if (text == null) tr("Fichier illisible.") else memory.importJson(text) ?: tr("Mémoire restaurée.")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                OutlinedButton(onClick = { exportLauncher.launch("jarvis-memoire.json") }) { Text(tr("Exporter")) }
                OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }) { Text(tr("Restaurer…")) }
            }
            backupMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)) }
            }
            SettingsCard(tr("Historique des sessions"), Icons.Filled.History, initiallyExpanded = false) {
            Text(
                tr("Les 30 dernières sessions vocales, avec ce qui les a lancées (mot d’activation ou bouton de l’appli). Gardé sur l’appareil seulement."),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
            if (sessions.isEmpty()) {
                Text(tr("Aucune session enregistrée."), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
            } else {
                sessions.asReversed().take(10).forEach { record ->
                    Text(
                        com.jarvis.android.core.formatSessionRecord(record, java.time.ZoneId.systemDefault()),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                OutlinedButton(
                    onClick = { sessionLog.clear(); sessions = emptyList() },
                    modifier = Modifier.padding(top = 8.dp),
                ) { Text(tr("Effacer l’historique")) }
            }
            val keepTranscripts by configStore.keepSessionTranscripts.collectAsState(initial = true)
            var keptCount by remember { mutableStateOf(0) }
            androidx.compose.runtime.LaunchedEffect(keepTranscripts) {
                keptCount = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    (context0.applicationContext as com.jarvis.android.JarvisApp).container.sessionTranscripts.list().size
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Text(
                    tr("Garder les échanges des sessions vocales (ce qui est dit) : ils s’affichent sur l’écran principal et sont rappelés à Jarvis au début de la session suivante, pour qu’il reprenne le fil. Fichier privé sur le téléphone, non chiffré ; les 30 dernières sessions, 90 jours au plus."),
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
                )
                Switch(checked = keepTranscripts, onCheckedChange = { scope.launch { configStore.setKeepSessionTranscripts(it) } })
            }
            Text(trf("{0} session(s) conservée(s).", keptCount), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            OutlinedButton(
                onClick = {
                    scope.launch {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            (context0.applicationContext as com.jarvis.android.JarvisApp).container.sessionTranscripts.clear()
                        }
                        keptCount = 0
                    }
                },
                enabled = keptCount > 0,
                modifier = Modifier.padding(top = 4.dp),
            ) { Text(tr("Effacer les échanges conservés")) }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Text(
                    tr("Garder le chat texte entre deux ouvertures de l’appli (fichier privé sur le téléphone, non chiffré). « Nouvelle conversation » l’efface."),
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
                )
                Switch(checked = chatHistoryOn, onCheckedChange = {
                    scope.launch {
                        configStore.setChatHistoryEnabled(it)
                        if (!it) (context0.applicationContext as com.jarvis.android.JarvisApp).container.restChat.clearSavedHistory()
                    }
                })
            }
            }
            SettingsCard(tr("Briefing du matin"), Icons.Filled.WbSunny, initiallyExpanded = false) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                Text(tr("Résumé de la dernière session et rappels du jour à la première session de la journée"), modifier = Modifier.weight(1f))
                Switch(checked = briefingOn, onCheckedChange = { scope.launch { configStore.setBriefingEnabled(it) } })
            }
            Text(
                tr("À la fin d’une session d’au moins deux échanges, Jarvis en garde un résumé d’une ou deux phrases (généré par Gemini, conservé sur l’appareil, trois au maximum)."),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
            }
            SettingsCard(tr("Vérifications en arrière-plan"), Icons.Filled.NotificationsActive, initiallyExpanded = false) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                Text(tr("Notifier : batterie faible, stockage presque plein, briefing du matin"), modifier = Modifier.weight(1f))
                Switch(checked = proactiveOn, onCheckedChange = { scope.launch { configStore.setProactiveEnabled(it) } })
            }
            Text(
                tr("Contrôle local toutes les 15 minutes environ, sans connexion ni micro. Chaque alerte n’est envoyée qu’une fois ; le briefing du matin (7 h à 11 h) reprend le résumé de la dernière session et vos rappels du jour. Désactivé par défaut."),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
            }
            SettingsCard(tr("Contrôle du téléphone"), Icons.Filled.PhoneAndroid, initiallyExpanded = false) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                Text(tr("Autoriser Jarvis à lire l’écran et à agir dans les autres applications"), modifier = Modifier.weight(1f))
                Switch(checked = deviceControl, onCheckedChange = { scope.launch { configStore.setDeviceControlEnabled(it) } })
            }
            Text(
                if (serviceOn) tr("Service d’accessibilité : activé ✓") else tr("Service d’accessibilité : désactivé"),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
            OutlinedButton(
                onClick = {
                    try {
                        context.startActivity(
                            android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    } catch (_: Exception) {
                    }
                },
                modifier = Modifier.padding(top = 8.dp),
            ) { Text(tr("Ouvrir les réglages d’accessibilité")) }
            OutlinedButton(
                onClick = {
                    try {
                        context.startActivity(
                            android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                                .setData(android.net.Uri.parse("package:" + context.packageName))
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    } catch (_: Exception) {
                    }
                },
                modifier = Modifier.padding(top = 8.dp),
            ) { Text(tr("Autoriser l’exécution en arrière-plan (batterie)")) }
            OutlinedButton(
                onClick = {
                    val autostart = android.content.Intent().setClassName(
                        "com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity",
                    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    try {
                        context.startActivity(autostart)
                    } catch (_: Exception) {
                        try {
                            context.startActivity(
                                android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                    .setData(android.net.Uri.parse("package:" + context.packageName))
                                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        } catch (_: Exception) {
                        }
                    }
                },
                modifier = Modifier.padding(top = 8.dp),
            ) { Text(tr("Démarrage automatique (Xiaomi) / infos de l’appli")) }
            Text(
                tr("Si le service se désactive quand vous fermez l’appli ou après une mise à jour : autorisez le démarrage automatique et l’exécution en arrière-plan avec les deux boutons ci-dessus, ") +
                    tr("et verrouillez l’appli dans la vue des applications récentes. ") +
                    if (com.jarvis.android.device.AccessibilityKeeper.canSelfEnable(context)) tr("Réactivation automatique : autorisée.")
                    else trf("Pour une réactivation automatique, autorisez une fois depuis un ordinateur : adb shell pm grant {0} android.permission.WRITE_SECURE_SETTINGS", context.packageName),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                tr("Android n’autorise le contrôle des autres applications que via un service d’accessibilité, à activer vous-même : Paramètres > Accessibilité > Jarvis : contrôle du téléphone. ") +
                    tr("Sur Xiaomi (MIUI), si l’option est grisée : Paramètres > Applications > Jarvis > menu ⋮ > Autoriser les paramètres restreints. ") +
                    (if (skipConfirmations)
                        tr("Les actions sensibles (envoyer, payer, supprimer, installer, autoriser) et le volume ne demandent plus votre confirmation (réglage ci-dessous). ")
                    else
                        tr("Les actions sensibles (envoyer, payer, supprimer, installer, autoriser) et le volume demandent votre confirmation dans une notification. ")) +
                    tr("Tout ce qui touche aux réglages système, aux autorisations ou à l’installation d’applications demande toujours votre confirmation, quel que soit ce réglage. ") +
                    tr("Jarvis ne remplit jamais un mot de passe. Le contenu lu à l’écran est transmis à Gemini pour traiter votre demande."),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            ) {
                Text(tr("Ne jamais demander de confirmation (volume, fichiers, actions sur l’écran)"), modifier = Modifier.weight(1f))
                Switch(checked = skipConfirmations, onCheckedChange = { scope.launch { configStore.setSkipConfirmations(it) } })
            }
            Text(
                tr("Toujours désactivé par défaut. Une fois activé, Jarvis agit du premier coup, sans bannière à valider, y compris à distance ou pendant que vous ne regardez pas le téléphone. Les écrans système (réglages, autorisations, installation d’applications) continuent, eux, à toujours demander confirmation : ce garde-fou n’est pas désactivable, c’est ce qui empêche Jarvis de s’accorder lui-même un accès qu’il n’a pas."),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
            }
            SettingsCard(tr("Clés API et modèles"), Icons.Filled.Key, initiallyExpanded = false) {
            OutlinedTextField(
                value = modelField,
                onValueChange = { modelField = it; scope.launch { configStore.setModel(it) } },
                label = { Text(tr("Modèle Live")) },
                singleLine = true,
                supportingText = { Text(tr("Saisissez un identifiant ou choisissez-en un dans la liste ci-dessous. Le changement s’applique au prochain démarrage de session.")) },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            OutlinedTextField(
                value = restModelField,
                onValueChange = { restModelField = it; scope.launch { configStore.setRestModel(it.trim()) } },
                label = { Text(tr("Modèle texte (chat)")) },
                singleLine = true,
                supportingText = { Text(tr("Utilisé par le chat texte et le test de clé (generateContent). Laissez vide pour la valeur par défaut.")) },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            OutlinedTextField(
                value = ttsModelField,
                onValueChange = { ttsModelField = it; scope.launch { configStore.setTtsModel(it.trim()) } },
                label = { Text(tr("Modèle voix (lecture des réponses)")) },
                singleLine = true,
                supportingText = { Text(tr("Laissez vide pour détecter automatiquement un modèle de synthèse vocale disponible avec votre clé. La voix suit le réglage de voix ci-dessus.")) },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            Text(
                trf("Clés API Gemini : jusqu’à {0}. Jarvis utilise la clé 1 ; si Gemini la refuse (quota atteint, clé invalide ou accès refusé), il passe seul à la suivante pour le chat texte et la voix du chat.", ConfigStore.MAX_API_KEYS),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                for (slot in 1..ConfigStore.MAX_API_KEYS) {
                    FilterChip(
                        selected = keySlot == slot,
                        onClick = { keySlot = slot; apiKeyField = ""; keyStatus = null },
                        label = { Text(if (filledSlots[slot - 1]) trf("Clé {0} ✓", slot) else trf("Clé {0}", slot)) },
                    )
                }
            }
            OutlinedTextField(
                value = apiKeyField,
                onValueChange = { apiKeyField = it },
                label = { Text(if (filledSlots[keySlot - 1]) trf("Remplacer la clé {0}", keySlot) else trf("Saisir la clé {0}", keySlot)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            Button(
                onClick = {
                    val candidate = validatedApiKey(apiKeyField) ?: return@Button
                    val slot = keySlot
                    keyBusy = true
                    keyStatus = null
                    scope.launch {
                        try {
                            if (configStore.saveApiKey(candidate, slot)) {
                                apiKeyField = ""
                                hasKey = true
                                filledSlots = filledSlots.toMutableList().also { it[slot - 1] = true }
                                keyStatus = trf("Clé {0} enregistrée et chiffrée sur cet appareil.", slot)
                            } else {
                                keyStatus = tr("Enregistrement impossible. Réessayez.")
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            keyStatus = tr("Enregistrement impossible. Réessayez.")
                        } finally {
                            keyBusy = false
                        }
                    }
                },
                enabled = validatedApiKey(apiKeyField) != null && !testing && !keyBusy,
                modifier = Modifier.padding(top = 8.dp),
            ) { Text(trf("Enregistrer la clé {0}", keySlot)) }
            Text(
                keyStatus ?: when (hasKey) {
                    true -> {
                        val filled = filledSlots.withIndex().filter { it.value }.joinToString(", ") { "${it.index + 1}" }
                        trf("Clés enregistrées : {0} (sur {1}).", filled, ConfigStore.MAX_API_KEYS)
                    }
                    false -> tr("Aucune clé enregistrée.")
                    null -> tr("Lecture du stockage sécurisé…")
                },
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
            )
            OutlinedButton(
                onClick = {
                    val slot = keySlot
                    keyBusy = true
                    keyStatus = null
                    scope.launch {
                        try {
                            if (configStore.deleteApiKey(slot)) {
                                filledSlots = filledSlots.toMutableList().also { it[slot - 1] = false }
                                keyStatus = trf("Clé {0} retirée.", slot)
                            } else {
                                keyStatus = tr("Suppression impossible. Réessayez.")
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            keyStatus = tr("Suppression impossible. Réessayez.")
                        } finally {
                            keyBusy = false
                        }
                    }
                },
                enabled = filledSlots[keySlot - 1] && filledSlots.count { it } > 1 && !keyBusy && !testing,
                modifier = Modifier.padding(top = 8.dp),
            ) { Text(trf("Retirer la clé {0}", keySlot)) }
            OutlinedButton(
                onClick = { confirmDeleteKey = true },
                enabled = hasKey == true && !keyBusy && !testing,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.padding(top = 8.dp),
            ) { Text(tr("Supprimer toutes les clés")) }
            Spacer(Modifier.height(16.dp))
            Text(
                tr("Le diagnostic teste l’API REST standard avec la clé enregistrée, indépendamment de la connexion vocale Live. Un succès ne garantit pas l’accès au modèle Live choisi."),
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
            ) { Text(if (testing) tr("Vérification en cours…") else tr("Tester la clé enregistrée (REST)")) }
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
            ) { Text(if (testing) tr("Vérification en cours…") else tr("Lister les modèles compatibles Live")) }
            modelsNote?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            }
            if (liveModels.isNotEmpty()) {
                Text(
                    tr("Touchez un modèle pour l’utiliser :"),
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
            }
            SettingsCard(tr("Rappels"), Icons.Filled.Alarm, initiallyExpanded = false) {
            if (reminderFeedback != null) {
                Text(reminderFeedback!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(8.dp))
            }
            OutlinedTextField(
                value = reminderText,
                onValueChange = { reminderText = it.take(1000) },
                label = { Text(tr("Rappel")) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            OutlinedTextField(
                value = reminderWhen,
                onValueChange = { reminderWhen = it },
                label = { Text(tr("Quand (yyyy-MM-dd HH:mm)")) },
                singleLine = true,
                supportingText = { Text(tr("Date stricte dans le futur, heure non ambiguë dans votre fuseau.")) },
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
                            reminderFeedback = trf("Rappel #{0} enregistré pour {1} ({2}).", record.id, record.whenIso, record.zoneId)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            reminderFeedback = tr("Le rappel n’a pas pu être enregistré. Vérifiez la date et réessayez.")
                        } finally {
                            loadingReminders = false
                        }
                    }
                },
                enabled = reminderText.isNotBlank() && reminderWhen.isNotBlank() && !loadingReminders,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) { Text(tr("Ajouter un rappel")) }
            if (loadingReminders) Text(tr("Chargement…"), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp)) {
                if (reminders.isEmpty()) {
                    item { Text(tr("Aucun rappel."), style = MaterialTheme.typography.bodySmall) }
                }
                items(reminders) { record ->
                    ReminderItem(record = record, onCancel = {
                        reminderFeedback = null
                        loadingReminders = true
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) { ReminderService.cancel(context, record.id) }
                                reminders = ReminderService.list(context)
                                reminderFeedback = trf("Rappel #{0} annulé.", record.id)
                            } catch (_: Exception) {
                                reminderFeedback = tr("L’annulation a échoué.")
                            } finally {
                                loadingReminders = false
                            }
                        }
                    })
                }
            }
            }
            SettingsCard(tr("Listes"), Icons.Filled.Checklist, initiallyExpanded = false) {
            val taskListStore = remember { (context0.applicationContext as com.jarvis.android.JarvisApp).container.taskListStore }
            var listTick by remember { mutableStateOf(0) }
            var listItems by remember { mutableStateOf<List<com.jarvis.android.tasks.TaskItem>>(emptyList()) }
            var newItemText by remember { mutableStateOf("") }
            LaunchedEffect(listTick) { listItems = taskListStore.items(com.jarvis.android.tasks.DEFAULT_LIST_NAME) }
            Text(
                tr("La liste par défaut (« ajoute du lait à la liste », « coche le lait », « vide la liste »), gérée aussi bien en ligne que hors ligne. Une liste nommée par vous (« ma liste de courses », « mes tâches ») est une liste à part, à voir et gérer à la voix."),
                style = MaterialTheme.typography.bodySmall,
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                OutlinedTextField(
                    value = newItemText,
                    onValueChange = { newItemText = it.take(200) },
                    label = { Text(tr("Ajouter à la liste")) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                FilledIconButton(
                    enabled = newItemText.isNotBlank(),
                    onClick = {
                        val text = newItemText.trim()
                        scope.launch { taskListStore.add(com.jarvis.android.tasks.DEFAULT_LIST_NAME, text); newItemText = ""; listTick++ }
                    },
                ) { Icon(Icons.Filled.Add, contentDescription = tr("Ajouter")) }
            }
            if (listItems.isEmpty()) {
                Text(tr("Liste vide."), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp))
            }
            listItems.forEach { item ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    Checkbox(checked = item.done, onCheckedChange = {
                        scope.launch { taskListStore.setDone(com.jarvis.android.tasks.DEFAULT_LIST_NAME, item.text, it); listTick++ }
                    })
                    Text(
                        item.text,
                        style = MaterialTheme.typography.bodyMedium,
                        textDecoration = if (item.done) androidx.compose.ui.text.style.TextDecoration.LineThrough else null,
                        color = if (item.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { scope.launch { taskListStore.remove(com.jarvis.android.tasks.DEFAULT_LIST_NAME, item.text); listTick++ } }) {
                        Icon(Icons.Filled.Close, contentDescription = tr("Retirer"))
                    }
                }
            }
            if (listItems.any { it.done }) {
                OutlinedButton(
                    onClick = { scope.launch { taskListStore.clearDone(com.jarvis.android.tasks.DEFAULT_LIST_NAME); listTick++ } },
                    modifier = Modifier.padding(top = 8.dp),
                ) { Text(tr("Retirer ce qui est coché")) }
            }
            }
            SettingsCard(tr("Maison connectée"), Icons.Filled.Home, initiallyExpanded = false) {
            Text(
                tr("Piloter des lumières, prises, volets ou le chauffage par la voix, via votre propre serveur Home Assistant (auto-hébergé). Créez un jeton d’accès longue durée depuis votre profil Home Assistant (icône en bas à gauche > votre nom > tout en bas de la page) et collez-le ici, avec l’adresse du serveur."),
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = haUrlField,
                onValueChange = { haUrlField = it },
                label = { Text(tr("Adresse du serveur")) },
                placeholder = { Text("http://192.168.1.50:8123") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            Button(
                onClick = { scope.launch { configStore.setHomeAssistantUrl(haUrlField.trim()) } },
                enabled = haUrlField.trim() != homeAssistantUrl,
                modifier = Modifier.padding(top = 8.dp),
            ) { Text(tr("Enregistrer l’adresse")) }
            OutlinedTextField(
                value = haTokenField,
                onValueChange = { haTokenField = it },
                label = { Text(if (hasHaToken == true) tr("Remplacer le jeton") else tr("Jeton d’accès longue durée")) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
            Row(modifier = Modifier.padding(top = 8.dp)) {
                Button(
                    onClick = {
                        val candidate = haTokenField.trim()
                        if (candidate.isEmpty()) return@Button
                        haBusy = true
                        haStatus = null
                        scope.launch {
                            try {
                                haStatus = if (configStore.saveHomeAssistantToken(candidate)) {
                                    haTokenField = ""
                                    hasHaToken = true
                                    tr("Jeton enregistré et chiffré sur cet appareil.")
                                } else tr("Enregistrement impossible. Réessayez.")
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                haStatus = tr("Enregistrement impossible. Réessayez.")
                            } finally {
                                haBusy = false
                            }
                        }
                    },
                    enabled = haTokenField.isNotBlank() && !haBusy,
                ) { Text(tr("Enregistrer le jeton")) }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(
                    onClick = {
                        haBusy = true
                        haStatus = null
                        scope.launch {
                            try {
                                haStatus = if (configStore.deleteHomeAssistantToken()) {
                                    hasHaToken = false
                                    tr("Jeton retiré.")
                                } else tr("Suppression impossible. Réessayez.")
                            } finally {
                                haBusy = false
                            }
                        }
                    },
                    enabled = hasHaToken == true && !haBusy,
                ) { Text(tr("Retirer le jeton")) }
            }
            Text(
                haStatus ?: when (hasHaToken) {
                    true -> tr("Jeton enregistré.")
                    false -> tr("Aucun jeton enregistré : la maison connectée ne répondra pas tant qu’il n’y en a pas un.")
                    null -> tr("Lecture du stockage sécurisé…")
                },
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
            )
            OutlinedButton(
                onClick = {
                    haTesting = true
                    haTestResult = null
                    scope.launch {
                        try { haTestResult = testHomeAssistant(configStore) } finally { haTesting = false }
                    }
                },
                enabled = !haTesting,
                modifier = Modifier.padding(top = 12.dp),
            ) { Text(if (haTesting) tr("Vérification en cours…") else tr("Tester la connexion")) }
            haTestResult?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            }
            Text(
                tr("Un seul garde-fou : les écrans de réglages, d’autorisations ou d’installation restent hors de portée de Jarvis, mais Home Assistant n’a pas cette limite — tout appareil que vous y avez relié devient contrôlable par la voix. Non testé contre un vrai serveur ici (l’environnement de développement n’y a pas accès) : le code suit fidèlement l’API REST documentée de Home Assistant, mais une vérification chez vous reste utile."),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
            )
            }
            Spacer(Modifier.height(32.dp))
        }
    }

    if (confirmDeleteKey) {
        AlertDialog(
            onDismissRequest = { if (!keyBusy) confirmDeleteKey = false },
            title = { Text(tr("Supprimer toutes les clés API ?")) },
            text = {
                Text(
                    trf("La session en cours sera arrêtée et Jarvis ne pourra plus se connecter tant que vous n’aurez pas saisi une nouvelle clé. Les {0} emplacements de clés seront vidés.", ConfigStore.MAX_API_KEYS)
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
                                    keyStatus = tr("Suppression impossible. Réessayez.")
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                keyStatus = tr("Suppression impossible. Réessayez.")
                            } finally {
                                keyBusy = false
                                confirmDeleteKey = false
                            }
                        }
                    },
                ) { Text(tr("Supprimer"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(enabled = !keyBusy, onClick = { confirmDeleteKey = false }) { Text(tr("Annuler")) }
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
                trf("Pour le {0} ({1})", record.whenIso, record.zoneId) +
                    if (record.approximate) tr(" · approximatif") else tr(" · exact"),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        TextButton(onClick = onCancel) { Text(tr("Annuler")) }
    }
}

private val settingsHttp = OkHttpClient()

private suspend fun testApiKey(configStore: ConfigStore): String = withContext(Dispatchers.IO) {
    try {
        val key = validatedApiKey(configStore.getApiKey().orEmpty())
            ?: return@withContext tr("Aucune clé valide enregistrée.")
        val model = configStore.snapshotRestModel().let { if (it.startsWith("models/")) it else "models/$it" }
        if (!Regex("models/[A-Za-z0-9._-]+").matches(model)) return@withContext tr("Nom de modèle texte invalide.")
        val body = """{"contents":[{"parts":[{"text":"Say OK"}]}]}"""
            .toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/$model:generateContent")
            .header("x-goog-api-key", key).post(body).build()
        settingsHttp.newCall(request).execute().use { response ->
            if (response.isSuccessful) trf("REST : succès (HTTP {0}). La clé fonctionne pour l’API standard.", response.code)
            else trf("REST : échec (HTTP {0}). Vérifiez la clé, les quotas et l’accès au modèle de diagnostic ; cet échec ne prouve pas à lui seul que la clé est invalide.", response.code)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: IOException) {
        tr("Diagnostic indisponible : connexion impossible ou délai dépassé.")
    } catch (_: Exception) {
        tr("Impossible de tester la clé enregistrée.")
    }
}

/** GET /api/ — Home Assistant's own health check, the lightest possible round trip to confirm the address and token both work. */
private suspend fun testHomeAssistant(configStore: ConfigStore): String = withContext(Dispatchers.IO) {
    try {
        val base = configStore.homeAssistantUrl.first().trim().trimEnd('/')
        if (base.isEmpty()) return@withContext tr("Indiquez d’abord l’adresse du serveur.")
        val token = configStore.getHomeAssistantToken()
        if (token.isNullOrBlank()) return@withContext tr("Indiquez d’abord un jeton d’accès.")
        val request = Request.Builder().url("$base/api/").header("Authorization", "Bearer $token").build()
        settingsHttp.newCall(request).execute().use { response ->
            when {
                response.code == 401 || response.code == 403 -> trf("Jeton refusé (HTTP {0}).", response.code)
                response.isSuccessful -> trf("Connexion réussie (HTTP {0}).", response.code)
                else -> trf("Échec (HTTP {0}). Vérifiez l’adresse.", response.code)
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: IOException) {
        tr("Connexion impossible : adresse injoignable ou délai dépassé. Vérifiez que le téléphone peut atteindre ce serveur (même réseau, ou accès distant configuré).")
    } catch (_: Exception) {
        tr("Impossible de tester la connexion.")
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
            ?: return@withContext ModelListing(emptyList(), tr("Aucune clé valide enregistrée."))
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
                ModelListing(emptyList(), trf("Aucun modèle compatible Live dans ce projet ({0} modèles vérifiés).", result.total))
            is LiveModelsResult.Found -> when {
                result.names.isEmpty() ->
                    ModelListing(emptyList(), tr("Aucun modèle compatible Live trouvé dans les pages reçues."))
                result.partial ->
                    ModelListing(result.names, tr("Liste partielle : d’autres modèles compatibles peuvent exister."))
                else -> ModelListing(result.names, null)
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: ModelListHttpException) {
        ModelListing(emptyList(), trf("Liste des modèles indisponible (HTTP {0}). Vérifiez la clé et réessayez plus tard.", e.code))
    } catch (_: IOException) {
        ModelListing(emptyList(), tr("Liste des modèles indisponible : connexion impossible ou délai dépassé."))
    } catch (_: Exception) {
        ModelListing(emptyList(), tr("Liste des modèles indisponible : réponse inexploitable ou clé inaccessible."))
    }
}
