package com.jarvis.android.meetings

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.jarvis.android.JarvisApp
import com.jarvis.android.R
import com.jarvis.android.docs.DocumentStore
import com.jarvis.android.files.parseFileAnswer
import com.jarvis.android.i18n.tr
import com.jarvis.android.i18n.trf
import com.jarvis.android.rest.RestChatException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDateTime

private const val CHANNEL_ID = "jarvis_meeting"
private const val NOTIFICATION_ID = 7400

/**
 * Records a meeting or a voice note (AAC, 32 kbit/s) while the phone is in a pocket, then has Gemini write the notes: summary, decisions,
 * actions, transcript. It runs as a microphone foreground service with a notification whose buttons finish or drop the recording.
 * The audio is deleted once the notes are saved; if the notes cannot be written, the audio is kept so that they can be tried again.
 */
class MeetingRecorderService : Service() {
    private var recorder: MediaRecorder? = null
    private var audioFile: File? = null
    private var startedAt = 0L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> finish(process = true)
            ACTION_DISCARD -> finish(process = false)
            ACTION_RETRY -> retry(intent.getStringExtra(EXTRA_FILE))
            else -> begin()
        }
        return START_NOT_STICKY
    }

    private fun channel() {
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL_ID, tr("Notes de réunion"), NotificationManager.IMPORTANCE_LOW))
    }

    private fun action(name: String, label: String, code: Int): NotificationCompat.Action {
        val intent = Intent(this, MeetingRecorderService::class.java).setAction(name)
        return NotificationCompat.Action(0, label, PendingIntent.getService(this, code, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
    }

    private fun recordingNotification(): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.mipmap.ic_launcher)
        .setContentTitle(tr("Jarvis enregistre la réunion"))
        .setContentText(tr("Touchez « Terminer » pour obtenir les notes."))
        .setOngoing(true)
        .setUsesChronometer(true)
        .setWhen(System.currentTimeMillis())
        .addAction(action(ACTION_STOP, tr("Terminer"), 1))
        .addAction(action(ACTION_DISCARD, tr("Abandonner"), 2))
        .build()

    private fun foreground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(NOTIFICATION_ID, notification)
    }

    private fun begin() {
        if (recording) return
        channel()
        try {
            foreground(recordingNotification())
            val file = File(audioDir(this), "rec_${System.currentTimeMillis()}.m4a")
            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(32_000)
            r.setAudioSamplingRate(22_050)
            r.setAudioChannels(1)
            r.setMaxDuration(MAX_RECORDING_MINUTES * 60 * 1000)
            r.setOnInfoListener { _, what, _ -> if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) finish(process = true) }
            r.setOutputFile(file.absolutePath)
            r.prepare()
            r.start()
            recorder = r
            audioFile = file
            busyFile = file.name
            startedAt = SystemClock.elapsedRealtime()
            recording = true
        } catch (e: Exception) {
            recording = false
            recorder?.release()
            recorder = null
            report(tr("Enregistrement impossible"), trf("Le micro n’a pas pu être ouvert ({0}). Fermez la session vocale et réessayez.", e.message ?: e.javaClass.simpleName))
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun finish(process: Boolean) {
        val r = recorder
        val file = audioFile
        recorder = null
        audioFile = null
        recording = false
        var usable = r != null && file != null
        try {
            r?.stop()
        } catch (_: RuntimeException) {
            usable = false     // stopped before anything was recorded
        }
        r?.release()
        if (!usable || file == null || !process || file.length() < MIN_RECORDING_BYTES) {
            file?.delete()
            busyFile = null
            if (process) report(tr("Enregistrement trop court"), tr("Rien d’exploitable n’a été enregistré."))
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        // Keep the service in the foreground while the notes are written: it can take a minute.
        foreground(
            NotificationCompat.Builder(this, CHANNEL_ID).setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(tr("Jarvis rédige les notes…")).setContentText(tr("Un instant, l’enregistrement est en cours d’analyse."))
                .setOngoing(true).build()
        )
        scope.launch {
            write(file)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun retry(name: String?) {
        val file = name?.let { File(audioDir(this), it) }?.takeIf { it.isFile }
        if (file == null) { stopSelf(); return }
        channel()
        foreground(
            NotificationCompat.Builder(this, CHANNEL_ID).setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(tr("Jarvis rédige les notes…")).setOngoing(true).build()
        )
        scope.launch {
            write(file)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    /** Sends [audio] to Gemini, saves the notes, deletes the audio. On failure the audio is kept and a notification says why. */
    private suspend fun write(audio: File) {
        busyFile = audio.name
        try {
            writeNotes(audio)
        } finally {
            busyFile = null
        }
    }

    private suspend fun writeNotes(audio: File) {
        val container = (applicationContext as JarvisApp).container
        try {
            val request = buildMeetingRequest(audio.readBytes())
            val model = container.configStore.snapshotRestModel()
            val markdown = parseFileAnswer(container.restChat.transport.generate(model, request)).trim()
            val title = noteTitle(markdown)
            val dir = notesDir(this)
            val file = File(dir, noteFileName(LocalDateTime.now(), title))
            file.writeText(markdown)
            audio.delete()
            // the notification opens and shares a PDF: the Markdown file has no viewer on most phones
            val pdf = try { exportNote(this, file, NoteFormat.PDF) } catch (_: Exception) { file }
            DocumentStore.notifyReady(this, pdf, trf("Notes prêtes : {0}", title), tr("Touchez pour ouvrir le PDF, ou partagez-le."), NOTIFICATION_ID + 1)
        } catch (e: RestChatException) {
            fail(audio, e.message ?: "")
        } catch (e: java.io.IOException) {
            fail(audio, e.message ?: "")
        }
    }

    private fun fail(audio: File, reason: String) {
        val retry = Intent(this, MeetingRecorderService::class.java).setAction(ACTION_RETRY).putExtra(EXTRA_FILE, audio.name)
        val notification = NotificationCompat.Builder(this, CHANNEL_ID).setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(tr("Notes de réunion : échec"))
            .setContentText(trf("L’enregistrement est conservé. {0}", reason))
            .addAction(0, tr("Réessayer"), PendingIntent.getService(this, 3, retry, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .setAutoCancel(true).build()
        try { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID + 2, notification) } catch (_: SecurityException) { }
    }

    private fun report(title: String, text: String) {
        channel()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID).setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title).setContentText(text).setAutoCancel(true).build()
        try { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID + 3, notification) } catch (_: SecurityException) { }
    }

    override fun onDestroy() {
        recorder?.let { runCatching { it.stop() }; it.release() }
        recorder = null
        recording = false
        busyFile = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.jarvis.android.meeting.STOP"
        const val ACTION_DISCARD = "com.jarvis.android.meeting.DISCARD"
        const val ACTION_RETRY = "com.jarvis.android.meeting.RETRY"
        private const val EXTRA_FILE = "file"

        private val recordingState = kotlinx.coroutines.flow.MutableStateFlow(false)

        /** Whether a meeting is being recorded, to react to (the wake word stops listening meanwhile). */
        val recordingFlow: kotlinx.coroutines.flow.StateFlow<Boolean> get() = recordingState

        @Volatile var recording = false
            private set(value) {
                field = value
                recordingState.value = value
            }

        /** The recording being made, or whose notes are being written: not offered for a retry. */
        @Volatile private var busyFile: String? = null

        fun audioDir(context: Context): File = File(context.noBackupFilesDir, "meeting_audio").also { it.mkdirs() }

        /** The notes: in `filesDir` (a FileProvider path), excluded from backups. */
        fun notesDir(context: Context): File = File(context.filesDir, "meetings").also { it.mkdirs() }

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, MeetingRecorderService::class.java))
        }

        /**
         * Starts the recording; when Android refuses a microphone service started from the background, a notification
         * offers to start it with one touch (a tap on a notification is always allowed). Returns true when it started.
         */
        fun startOrOffer(context: Context): Boolean = try {
            start(context)
            true
        } catch (e: Exception) {
            offerStart(context)
            false
        }

        private fun offerStart(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, tr("Notes de réunion"), NotificationManager.IMPORTANCE_HIGH))
            val tap = PendingIntent.getForegroundService(context, 3, Intent(context, MeetingRecorderService::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(tr("Prêt à enregistrer la réunion"))
                .setContentText(tr("Android a demandé une confirmation : touchez ici pour démarrer."))
                .setContentIntent(tap)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
            try { manager.notify(NOTIFICATION_ID + 4, notification) } catch (_: SecurityException) { }
        }

        fun stop(context: Context, discard: Boolean = false) {
            context.startService(Intent(context, MeetingRecorderService::class.java).setAction(if (discard) ACTION_DISCARD else ACTION_STOP))
        }

        /** Recordings whose notes could not be written. */
        fun pendingRecordings(context: Context): List<File> =
            audioDir(context).listFiles { f -> f.extension == "m4a" && f.name != busyFile }?.sortedBy { it.lastModified() } ?: emptyList()

        fun retry(context: Context, file: File) {
            ContextCompat.startForegroundService(context, Intent(context, MeetingRecorderService::class.java).setAction(ACTION_RETRY).putExtra(EXTRA_FILE, file.name))
        }
    }
}
