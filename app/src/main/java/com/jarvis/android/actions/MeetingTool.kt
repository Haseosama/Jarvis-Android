package com.jarvis.android.actions

import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.jarvis.android.JarvisContainer
import com.jarvis.android.docs.DocumentStore
import com.jarvis.android.i18n.tr
import com.jarvis.android.i18n.trf
import com.jarvis.android.meetings.MeetingRecorderService
import com.jarvis.android.meetings.findNote
import com.jarvis.android.meetings.listNotes
import kotlinx.serialization.json.JsonObject
import java.text.DateFormat
import java.util.Date

internal const val MAX_NOTE_CHARS_READ = 6_000

/** Records a meeting or a voice note and turns it into notes (summary, decisions, actions, transcript). */
object MeetingTool : Tool {
    override val name = "meeting_notes"
    override val description =
        "Prendre les notes d’une réunion ou d’une note vocale. action=start : enregistrer (micro ouvert jusqu’à ce que l’utilisateur touche « Terminer » dans la notification ou demande d’arrêter) ; " +
            "stop : terminer et rédiger les notes (résumé, points clés, décisions, actions, transcription ; une notification prévient quand elles sont prêtes) ; cancel : abandonner sans notes ; status ; " +
            "list : les notes enregistrées ; read : lire des notes (name = leur nom, vide pour les dernières) ; share : proposer de partager des notes ; delete : les supprimer. " +
            "L’enregistrement dure une heure au plus. La session vocale se ferme dès le démarrage (le micro ne peut servir qu’à un usage à la fois) : annoncer l’enregistrement en une courte phrase."
    override val parameters = objectSchema(required = listOf("action")) {
        string("action", "start, stop, cancel, status, list, read, share ou delete.")
        string("name", "Nom (ou mot du titre) des notes pour read, share et delete ; vide pour les dernières.")
    }

    override suspend fun run(args: JsonObject, ctx: JarvisContainer): String {
        val context = ctx.appContext
        val notesDir = MeetingRecorderService.notesDir(context)
        return when (val action = args.stringArg("action").trim().lowercase()) {
            "start" -> {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    return "Le micro n’est pas autorisé : l’utilisateur doit accorder l’autorisation Micro à Jarvis dans les réglages d’Android."
                }
                if (MeetingRecorderService.recording) return "Un enregistrement est déjà en cours."
                // The microphone serves one use at a time: the voice session closes first (after the assistant's short goodbye),
                // then the recorder opens the microphone at once, while the voice service still lets a background start through.
                if (ctx.engine.requestEndSession { MeetingRecorderService.startOrOffer(context) }) {
                    "L’enregistrement démarre dès que la session vocale se ferme, dans un instant. Dis en une courte phrase que tu lances l’enregistrement, sans poser de question : la session se ferme après cette phrase. L’utilisateur termine avec le bouton « Terminer » de la notification, et les notes arrivent par notification."
                } else {
                    MeetingRecorderService.start(context)
                    "Enregistrement démarré. L’utilisateur le termine avec le bouton « Terminer » de la notification (ou en le demandant), et les notes arrivent par notification."
                }
            }
            "stop" -> if (MeetingRecorderService.recording) {
                MeetingRecorderService.stop(context)
                "Enregistrement terminé. Les notes sont en cours de rédaction : une notification préviendra quand elles seront prêtes."
            } else "Aucun enregistrement en cours."
            "cancel" -> if (MeetingRecorderService.recording) {
                MeetingRecorderService.stop(context, discard = true)
                "Enregistrement abandonné, rien n’est conservé."
            } else "Aucun enregistrement en cours."
            "status" -> {
                val pending = MeetingRecorderService.pendingRecordings(context).size
                (if (MeetingRecorderService.recording) "Un enregistrement est en cours." else "Aucun enregistrement en cours.") +
                    " ${listNotes(notesDir).size} note(s) enregistrée(s)." + if (pending > 0) " $pending enregistrement(s) dont les notes n’ont pas pu être rédigées (à réessayer depuis la notification ou les réglages)." else ""
            }
            "list" -> {
                val notes = listNotes(notesDir)
                if (notes.isEmpty()) "Aucune note de réunion enregistrée."
                else notes.take(15).joinToString("\n") { "- ${it.file.name} : ${it.title} (${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it.modified))})" }
            }
            "read", "share", "delete" -> {
                val note = findNote(listNotes(notesDir), args.stringArg("name").ifBlank { null })
                    ?: return "Aucune note ne correspond."
                when (action) {
                    "read" -> "Notes « ${note.title} » :\n" + note.file.readText().take(MAX_NOTE_CHARS_READ)
                    "share" -> {
                        DocumentStore.notifyReady(context, note.file, trf("Notes : {0}", note.title), tr("Touchez pour les ouvrir, ou partagez-les."), 7405)
                        "Une notification permet d’ouvrir ou de partager les notes « ${note.title} »."
                    }
                    else -> { note.file.delete(); "Notes « ${note.title} » supprimées." }
                }
            }
            else -> "Action inconnue : $action."
        }
    }
}
