package com.jarvis.android.actions

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.jarvis.android.JarvisContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.text.Normalizer

internal data class ContactRow(val contactId: Long, val name: String, val number: String, val type: String)

/** One dialable choice: a contact and one of their numbers. */
internal data class ContactChoice(val name: String, val number: String, val type: String) {
    val label: String get() = if (type.isBlank()) name else "$name ($type)"
}

private fun fold(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase().trim()

private fun digits(number: String) = number.filter { it.isDigit() || it == '+' }

/**
 * The choices matching [query], best first: an exact name, then names that contain every word of the query.
 * The same number written twice for one contact counts once.
 */
internal fun matchContacts(rows: List<ContactRow>, query: String): List<ContactChoice> {
    val words = fold(query).split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return emptyList()
    val whole = fold(query)
    val hits = rows.filter { row ->
        val name = fold(row.name)
        words.all { it in name }
    }
    return hits
        .sortedWith(compareBy({ if (fold(it.name) == whole) 0 else 1 }, { fold(it.name) }, { it.type }))
        .distinctBy { it.contactId to digits(it.number) }
        .map { ContactChoice(it.name, it.number, it.type) }
}

/** Place a call or write an SMS to a contact by name. The number is never given to the model, and nothing is dialled or sent by itself. */
object ContactTool : Tool {
    override val name = "call_contact"
    override val description =
        "Appeler ou écrire un SMS à un contact du téléphone par son nom (« appelle Maman »). Actions : call (défaut : ouvre le numéroteur avec le numéro, " +
            "l'utilisateur appuie lui-même sur appeler) ou sms (ouvre un brouillon de SMS, l'utilisateur envoie lui-même). " +
            "S'il y a plusieurs correspondances, l'outil les liste avec des numéros de choix : demandez lequel à l'utilisateur puis rappelez avec le même nom et choice. " +
            "Les numéros de téléphone ne vous sont jamais communiqués."
    override val parameters = objectSchema(required = listOf("name")) {
        string("name", "Nom du contact, ou une partie du nom.")
        string("action", "'call' (défaut) ou 'sms'.")
        string("text", "Pour sms : texte du brouillon, facultatif.")
        integer("choice", "Numéro du choix (à partir de 1) quand plusieurs contacts ou numéros correspondent.")
    }

    override suspend fun run(args: JsonObject, ctx: JarvisContainer): String {
        val context = ctx.appContext
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return "L'accès aux contacts n'est pas autorisé. Dites à l'utilisateur de l'autoriser dans les réglages de Jarvis (carte Contacts)."
        }
        val query = args.stringArg("name").trim()
        if (query.isEmpty() || query.length > 80) return "Indiquez le nom du contact (80 caractères maximum)."
        val action = args.stringArg("action").trim().lowercase().ifEmpty { "call" }
        if (action != "call" && action != "sms") return "Action inconnue : utilisez call ou sms."

        val choices = withContext(Dispatchers.IO) { matchContacts(readContacts(context), query) }
        if (choices.isEmpty()) return "Aucun contact ne correspond à « ${query.take(40)} »."
        val chosen: ContactChoice = if (choices.size == 1) {
            choices[0]
        } else {
            val index = args.intArg("choice", 0)
            if (index in 1..choices.size) choices[index - 1]
            else return "Plusieurs correspondances : " +
                choices.take(8).mapIndexed { i, c -> "${i + 1}) ${c.label}" }.joinToString(" ; ") +
                ". Demandez lequel à l'utilisateur, puis rappelez avec le même nom et choice."
        }
        val intent = if (action == "sms") {
            Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", chosen.number, null)).apply {
                args.stringArg("text").takeIf { it.isNotBlank() }?.take(1000)?.let { putExtra("sms_body", it) }
            }
        } else {
            Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", chosen.number, null))
        }
        return try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            (if (action == "sms") "Brouillon de SMS ouvert pour ${chosen.label}. Rien n'est envoyé : l'utilisateur envoie lui-même."
            else "Numéroteur ouvert pour ${chosen.label}. L'appel ne part pas tout seul : l'utilisateur appuie sur appeler.") +
                com.jarvis.android.people.PersonReminders.noteFor(context, chosen.number)
        } catch (_: Exception) {
            "Impossible d'ouvrir l'application ${if (action == "sms") "de SMS" else "téléphone"}."
        }
    }

    internal fun readContacts(context: Context): List<ContactRow> {
        val rows = mutableListOf<ContactRow>()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.TYPE,
            ContactsContract.CommonDataKinds.Phone.LABEL,
        )
        context.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, projection, null, null, null)?.use { c ->
            while (c.moveToNext() && rows.size < 20_000) {
                val number = c.getString(2) ?: continue
                val type = ContactsContract.CommonDataKinds.Phone.getTypeLabel(context.resources, c.getInt(3), c.getString(4)).toString()
                rows += ContactRow(c.getLong(0), c.getString(1) ?: continue, number, type)
            }
        }
        return rows
    }
}
