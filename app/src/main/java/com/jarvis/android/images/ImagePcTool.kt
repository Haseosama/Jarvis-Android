package com.jarvis.android.images

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.jarvis.android.JarvisContainer
import com.jarvis.android.pc.PcImageReply
import com.jarvis.android.tool.Tool
import com.jarvis.android.tool.intArg
import com.jarvis.android.tool.objectSchema
import com.jarvis.android.tool.stringArg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/*
 * Pictures made from a description by the image generator on the user's PC: Jarvis 2.0 (2.0.34+, electron/imageGen.cjs) hands
 * the request to Fooocus, ComfyUI or Forge, or installs ComfyUI itself when there is none. Gemini cannot make adult pictures,
 * and Stable Diffusion does not run on a phone, hence the PC, reached over the pairing (pc/PcRemote.kt).
 *
 * Only text goes in, never a photo: no picture is made from a real person. Adult content is allowed only when the user turned
 * it on (Réglages > Images IA); anything about a child or a minor is refused here and again on the PC. Ordinary pictures go to
 * the gallery (Pictures/Jarvis); adult ones stay in the app's private folder, out of the gallery.
 */
object ImagePcTool : Tool {
    override val name = "image_pc"
    override val description =
        "Crée une image à partir d'une description, avec le générateur d'images du PC appairé (Fooocus, ComfyUI ou Forge, via " +
            "Jarvis 2.0), puis l'affiche et l'enregistre sur le téléphone. « description » : ce qu'il faut voir, de préférence en " +
            "anglais et détaillé (sujet, style, lumière, cadrage). « format » : portrait (défaut), paysage ou carre. « eviter » : ce " +
            "qu'il ne faut pas voir. « graine » : pour refaire la même image. action « installer » : installe le générateur sur le " +
            "PC (environ 9 Go), « etat » : où en est l'installation. Jamais d'image d'une personne réelle identifiable (nom, " +
            "célébrité, photo) ni d'enfant ou de mineur : refusez ces demandes. Le contenu adulte n'est permis que si l'utilisateur " +
            "l'a activé dans les réglages."
    override val parameters = objectSchema {
        string("description", "L'image à créer, de préférence en anglais : sujet, style, lumière, cadrage.")
        string("format", "portrait (défaut), paysage ou carre.")
        string("eviter", "Ce qu'il ne faut pas voir dans l'image (facultatif).")
        integer("graine", "Graine d'une image précédente pour la refaire (facultatif).")
        string("action", "creer (défaut), installer ou etat.")
    }

    override suspend fun run(args: JsonObject, ctx: JarvisContainer): String {
        val action = imageAction(args.stringArg("action"))
        if (action != "create") return ctx.pcRemote.image(buildJsonObject { put("action", action) }).text
        val adult = ctx.configStore.imagesAdult.first()
        val made = create(ctx, args.stringArg("description"), args.stringArg("format"), args.stringArg("eviter"), args.intArg("graine", -1), adult)
        val picture = made.picture ?: return made.text
        open(ctx.appContext, picture)
        return made.text
    }

    /** A picture made and saved, or why not. Shared by the tool and the settings card. */
    class Made(val text: String, val picture: SavedPicture? = null, val png: ByteArray? = null)

    suspend fun create(ctx: JarvisContainer, prompt: String, format: String, avoid: String, seed: Int, adult: Boolean): Made {
        imageRefusal(prompt, adult)?.let { return Made(it) }
        val reply: PcImageReply = ctx.pcRemote.image(imageRequest(prompt, format, avoid, seed, adult))
        val png = reply.png
        if (!reply.ok || png == null) return Made(reply.text)
        ctx.log("Image créée sur le PC")
        val saved = withContext(Dispatchers.IO) { runCatching { save(ctx.appContext, png, adult) }.getOrNull() }
            ?: return Made("${reply.text} Mais elle n'a pas pu être enregistrée sur le téléphone.", png = png)
        val where = if (adult) "dans le dossier privé de Jarvis (hors galerie)" else "dans la galerie (Pictures/Jarvis)"
        return Made("${reply.text} Enregistrée $where.", saved, png)
    }

    /** Where a picture was saved, and the address to open it with. */
    class SavedPicture(val uri: Uri, val isPrivate: Boolean)

    private fun save(context: Context, png: ByteArray, adult: Boolean): SavedPicture {
        val fileName = "jarvis-${System.currentTimeMillis()}.png"
        if (adult || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val dir = File(context.filesDir, "images").apply { mkdirs() }
            val file = File(dir, fileName).apply { writeBytes(png) }
            return SavedPicture(FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file), isPrivate = true)
        }
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Jarvis")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("MediaStore refused")
        resolver.openOutputStream(uri)?.use { it.write(png) } ?: error("no stream")
        resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        return SavedPicture(uri, isPrivate = false)
    }

    /** Shows the picture in the phone's image viewer; Android may refuse when Jarvis is in the background, which is fine. */
    fun open(context: Context, picture: SavedPicture) {
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW).setDataAndType(picture.uri, "image/png")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (_: Exception) {
        }
    }
}
