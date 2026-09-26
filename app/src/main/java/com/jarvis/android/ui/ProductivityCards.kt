package com.jarvis.android.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.jarvis.android.JarvisApp
import com.jarvis.android.docs.DocumentStore
import com.jarvis.android.google.GoogleAuth
import com.jarvis.android.google.GoogleConnectActivity
import com.jarvis.android.google.GoogleException
import com.jarvis.android.i18n.tr
import com.jarvis.android.i18n.trf
import com.jarvis.android.meetings.MeetingRecorderService
import com.jarvis.android.meetings.NoteFormat
import com.jarvis.android.meetings.exportNote
import com.jarvis.android.meetings.listNotes
import com.jarvis.android.memory.ConfigStore
import com.jarvis.android.update.AppUpdater
import com.jarvis.android.update.ReleaseInfo
import com.jarvis.android.update.UpdateCheck
import com.jarvis.android.update.UpdateInstall
import com.jarvis.android.watch.Watch
import com.jarvis.android.watch.WatchScheduler
import com.jarvis.android.watch.title
import com.jarvis.android.watch.watchStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Record a meeting from the settings (the reliable way: the app is in front), read and share the notes. */
@Composable
internal fun MeetingNotesCard() {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    var recording by remember { mutableStateOf(MeetingRecorderService.recording) }
    // Watch the recorder and the notes: a note appears a little after "Terminer".
    LaunchedEffect(Unit) {
        while (true) {
            recording = MeetingRecorderService.recording
            tick++
            delay(2_000)
        }
    }
    val notes = remember(tick) { listNotes(MeetingRecorderService.notesDir(context)) }
    val pending = remember(tick) { MeetingRecorderService.pendingRecordings(context) }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) MeetingRecorderService.start(context) else message = tr("Le micro n’est pas autorisé.")
    }
    SettingsCard(tr("Notes de réunion"), Icons.Filled.Description, initiallyExpanded = false) {
        Text(
            tr("Enregistrez une réunion ou une note vocale (une heure au plus) : Jarvis en tire un résumé, les décisions, les actions et la transcription. Le micro ne sert qu’à un usage à la fois : ne lancez pas une session vocale pendant l’enregistrement."),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            if (recording) {
                OutlinedButton(onClick = { MeetingRecorderService.stop(context); message = tr("Notes en cours de rédaction : une notification arrive.") }) { Text(tr("Terminer et rédiger les notes")) }
                OutlinedButton(onClick = { MeetingRecorderService.stop(context, discard = true) }) { Text(tr("Abandonner")) }
            } else {
                OutlinedButton(onClick = {
                    message = null
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) MeetingRecorderService.start(context)
                    else askMic.launch(Manifest.permission.RECORD_AUDIO)
                }) { Text(tr("Enregistrer une réunion")) }
            }
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)) }
        for (file in pending) {
            OutlinedButton(onClick = { MeetingRecorderService.retry(context, file) }, modifier = Modifier.padding(top = 6.dp)) {
                Text(trf("Réessayer les notes de l’enregistrement du {0}", java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(file.lastModified()))))
            }
        }
        if (notes.isEmpty()) {
            Text(tr("Aucune note pour l’instant."), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
        }
        for (note in notes.take(5)) {
            Text(note.title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((format, label) in listOf(NoteFormat.PDF to "PDF", NoteFormat.DOCX to "Word", NoteFormat.TXT to "Texte")) {
                    OutlinedButton(onClick = {
                        message = try {
                            val out = exportNote(context, note.file, format)
                            DocumentStore.notifyReady(context, out, trf("Notes : {0}", note.title), tr("Touchez pour ouvrir le fichier, ou partagez-le."), 7405)
                            null
                        } catch (e: Exception) {
                            trf("Export impossible : {0}", e.message ?: e.javaClass.simpleName)
                        }
                    }) { Text(if (format == NoteFormat.TXT) tr(label) else label) }
                }
                OutlinedButton(onClick = { note.file.delete(); tick++ }) { Text(tr("Supprimer")) }
            }
        }
    }
}

/** The background watches Jarvis was asked to keep, with a way to remove them. */
@Composable
internal fun WatchesCard() {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val watches: List<Watch> = remember(tick) { watchStore(context).load() }
    SettingsCard(tr("Surveillances"), Icons.Filled.Visibility, initiallyExpanded = false) {
        Text(
            tr("Demandez à Jarvis de surveiller un prix (« préviens-moi si le bitcoin dépasse 70 000 € »), un site (« dis-moi s’il tombe »), la température de la batterie ou la mémoire libre. Il vérifie environ toutes les 15 minutes et vous prévient par notification."),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (watches.isEmpty()) Text(tr("Aucune surveillance active."), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
        for (watch in watches) {
            Text("${watch.title()}" + (watch.lastValue?.let { " — " + String.format(java.util.Locale.FRANCE, "%.2f", it) } ?: ""), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
            OutlinedButton(onClick = { watchStore(context).remove(watch.id); WatchScheduler.sync(context); tick++ }) { Text(tr("Supprimer")) }
        }
    }
}

/** Connect the Google account for Gmail and Drive. */
@Composable
internal fun GoogleCard(configStore: ConfigStore) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val connected by configStore.googleConnected.collectAsState(initial = false)
    val gmailAutoSend by configStore.gmailAutoSend.collectAsState(initial = false)
    var checking by remember { mutableStateOf(false) }
    var checkResult by remember { mutableStateOf<String?>(null) }
    SettingsCard(tr("Google (Gmail, Drive)"), Icons.Filled.Mail, initiallyExpanded = false) {
        Text(
            if (connected) tr("Compte Google connecté : Jarvis peut lire vos mails et vos fichiers Drive, et préparer des brouillons Gmail. Par défaut il n’envoie rien tout seul (réglage ci-dessous pour changer ça).")
            else tr("Non connecté. Une fois connecté, Jarvis peut lire vos mails, préparer des brouillons Gmail et lire ou ajouter des fichiers dans Drive."),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 4.dp),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) {
            Text(tr("Envoyer les mails sans confirmation quand je dis « envoie »"), modifier = Modifier.weight(1f))
            Switch(checked = gmailAutoSend, onCheckedChange = { scope.launch { configStore.setGmailAutoSend(it) } })
        }
        Text(
            tr("Toujours désactivé par défaut. Une fois activé, un mail que vous demandez clairement d’envoyer part pour de vrai au lieu de rester un brouillon. Si le compte a été connecté avant l’ajout de ce réglage, reconnectez-le une fois (« Reconnecter Google » ci-dessous) pour que Google vous demande cette permission supplémentaire."),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            OutlinedButton(onClick = { context.startActivity(Intent(context, GoogleConnectActivity::class.java)) }) {
                Text(if (connected) tr("Reconnecter Google") else tr("Connecter Google"))
            }
            OutlinedButton(enabled = !checking, onClick = {
                checking = true
                checkResult = tr("Vérification en cours…")
                scope.launch {
                    GoogleAuth.accessToken(context).fold(
                        onSuccess = { checkResult = tr("Connexion Google en état de marche."); configStore.setGoogleConnected(true) },
                        onFailure = { e ->
                            checkResult = e.message
                            if ((e as? GoogleException)?.needsReconnect == true) configStore.setGoogleConnected(false)
                        },
                    )
                    checking = false
                }
            }) { Text(tr("Vérifier la connexion")) }
            if (connected) OutlinedButton(onClick = { scope.launch { configStore.setGoogleConnected(false) } }) { Text(tr("Oublier")) }
        }
        checkResult?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp)) }
        val sha1 = remember { com.jarvis.android.google.AppIdentity.sha1(context) }
        Text(
            trf("Nom de paquet : {0}", context.packageName) + "\n" + trf("Empreinte SHA-1 : {0}", sha1 ?: "?"),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            tr("Cela demande un client OAuth de type Android pour cette application dans un projet Google Cloud (voir le README). Pour retirer l’accès côté Google : compte Google > Sécurité > Applications tierces."),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/** Look for a newer version on the GitHub releases of the project, download it and hand it to Android's installer. */
@Composable
internal fun UpdateCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val updater = remember { AppUpdater(context, (context.applicationContext as JarvisApp).container.http) }
    var message by remember { mutableStateOf<String?>(null) }
    var available by remember { mutableStateOf<ReleaseInfo?>(null) }
    var downloaded by remember { mutableStateOf<java.io.File?>(null) }
    var busy by remember { mutableStateOf(false) }
    var waitingPermission by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Float?>(null) }
    val installerSays by UpdateInstall.status.collectAsState()

    fun startInstall(apk: java.io.File) {
        val problem = updater.install(apk)
        if (problem == null) {
            message = tr("Installation lancée : Android demande la confirmation.")
        } else {
            message = problem
            if (!updater.canInstall()) {
                waitingPermission = true
                updater.openInstallPermission()
            }
        }
    }
    // Back from the settings page where the install permission is given: go on with the file already downloaded.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val apk = downloaded
        if (apk != null && waitingPermission && !busy && updater.canInstall() && apk.exists()) {
            waitingPermission = false
            startInstall(apk)
        }
    }
    SettingsCard(tr("Mise à jour"), Icons.Filled.SystemUpdate, initiallyExpanded = false) {
        Text(
            trf("Version installée : {0}", updater.installedVersion()),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            trf("Jarvis cherche les nouvelles versions parmi les publications GitHub du projet ({0}). Android demandera de confirmer l’installation, et la mise à jour garde vos réglages et votre mémoire.", AppUpdater.REPO),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            OutlinedButton(enabled = !busy, onClick = {
                busy = true
                message = tr("Recherche en cours…")
                UpdateInstall.status.value = null
                scope.launch {
                    when (val result = updater.check()) {
                        is UpdateCheck.Available -> { available = result.release; downloaded = null; message = trf("Version {0} disponible.", result.release.version) }
                        UpdateCheck.UpToDate -> { available = null; message = tr("Jarvis est à jour.") }
                        is UpdateCheck.Failed -> { available = null; message = result.reason }
                    }
                    busy = false
                }
            }) { Text(tr("Rechercher une mise à jour")) }
            available?.let { release ->
                if (downloaded == null) {
                    OutlinedButton(enabled = !busy, onClick = {
                        busy = true
                        progress = 0f
                        message = tr("Téléchargement en cours…")
                        UpdateInstall.status.value = null
                        scope.launch {
                            updater.download(release) { progress = it }.fold(
                                onSuccess = { apk -> downloaded = apk; startInstall(apk) },
                                onFailure = { message = it.message },
                            )
                            progress = null
                            busy = false
                        }
                    }) { Text(tr("Télécharger et installer")) }
                }
            }
            downloaded?.let { apk ->
                OutlinedButton(enabled = !busy, onClick = { startInstall(apk) }) { Text(tr("Installer")) }
            }
        }
        progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp)) }
        installerSays?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp)) }
        if (downloaded != null) {
            Text(
                tr("Le téléphone peut ajouter ses propres étapes : Google Play Protect propose « Analyser » ou, sous « Plus de détails », « Installer sans analyser » ; d’autres marques ont leur analyse de sécurité. Choisissez de continuer pour que l’installation se fasse."),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        available?.notes?.takeIf { it.isNotBlank() }?.let {
            Text(it.take(600), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
        }
    }
}


/** Sending messages on the user's word: off by default, with what it changes said plainly, and what was sent in their name. */
@Composable
internal fun MessageSendCard(configStore: ConfigStore) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val container = remember { (context.applicationContext as JarvisApp).container }
    val on by configStore.messageAutoSend.collectAsState(initial = false)
    var tick by remember { mutableIntStateOf(0) }
    var smsGranted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED) }
    val askSms = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { smsGranted = it }
    LaunchedEffect(Unit) {
        while (true) {
            tick++
            smsGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
            delay(3_000)
        }
    }
    val history = remember(tick) { container.sentMessages.recent(5) }
    SettingsCard(tr("Envoi de messages"), Icons.Filled.Send, initiallyExpanded = false) {
        Row(
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) {
            Text(tr("Envoyer les messages sans confirmation quand je dis « envoie »"), modifier = Modifier.weight(1f))
            Switch(checked = on, onCheckedChange = { value ->
                scope.launch { configStore.setMessageAutoSend(value) }
                if (value && !smsGranted) askSms.launch(Manifest.permission.SEND_SMS)
            })
        }
        Text(
            tr("Désactivé, Jarvis ne fait que préparer un brouillon. Activé, quand vous dites clairement d’envoyer, il envoie vraiment le message : par SMS ou par WhatsApp à un contact de votre téléphone (il ouvre la conversation et appuie sur Envoyer, ce qui demande le contrôle du téléphone), ou par Messenger à la personne nommée comme dans Messenger (il la cherche dans l’écran « Envoyer à » et appuie sur Envoyer à côté de son nom, jamais sur celui d’un autre). 5 messages au plus toutes les 10 minutes, chaque envoi est annoncé par une notification et noté ci-dessous. Un texte lu dans un mail, une page ou une notification ne doit jamais déclencher un envoi, mais un envoi parti ne se rattrape pas : n’activez que si vous l’acceptez."),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 6.dp),
        )
        if (on) {
            Text(
                if (smsGranted) tr("Autorisation d’envoyer des SMS : accordée ✓") else tr("Autorisation d’envoyer des SMS : non accordée (les SMS passeront par l’application de SMS, avec le contrôle du téléphone)."),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 6.dp),
            )
            if (!smsGranted) OutlinedButton(onClick = { askSms.launch(Manifest.permission.SEND_SMS) }, modifier = Modifier.padding(top = 4.dp)) { Text(tr("Autoriser l’envoi de SMS")) }
        }
        Text(tr("Derniers messages envoyés par Jarvis"), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 10.dp))
        if (history.isEmpty()) Text(tr("Aucun pour l’instant."), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
        val format = remember { java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT) }
        for (m in history) {
            Text(
                "${format.format(java.util.Date(m.time))} · ${m.to} (${m.via}) : ${m.text.take(120)}",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (history.isNotEmpty()) OutlinedButton(onClick = { container.sentMessages.clear(); tick++ }, modifier = Modifier.padding(top = 6.dp)) { Text(tr("Effacer l’historique")) }
    }
}
