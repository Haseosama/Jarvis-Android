package com.jarvis.android.instagram

import com.jarvis.android.JarvisContainer
import com.jarvis.android.photos.hasPhotoPermission
import com.jarvis.android.photos.photoRange
import com.jarvis.android.photos.queryPhotos
import com.jarvis.android.rest.parseVisionAnswer
import com.jarvis.android.tool.Tool
import com.jarvis.android.tool.intArg
import com.jarvis.android.tool.objectSchema
import com.jarvis.android.tool.stringArg
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * Posts the user's urbex photos on their Instagram professional account: the latest outing of their urbex album not posted yet
 * (or the days asked for), as one photo or a carousel, with a caption Gemini writes from the photos (or the user's own). Every
 * photo is re-encoded on the phone first, so no GPS position or other metadata leaves it (see InstagramPublish.kt).
 */
object InstagramTool : Tool {
    override val name = "instagram_publier"
    override val description =
        "Publier des photos de la galerie sur le compte Instagram de l’utilisateur (« publie mes photos d’urbex sur Instagram », " +
            "« poste ma dernière photo d’urbex », « publie les photos d’hier avec la légende … »). Sans dates, prend la dernière sortie : " +
            "les photos du jour le plus récent des dossiers d’urbex choisis dans les réglages qui n’ont pas encore été publiées, jusqu’à 10 en un carrousel. " +
            "La légende est écrite d’après les photos (en français, avec des hashtags urbex) sauf si l’utilisateur la dicte (legende). " +
            "La position GPS et toutes les métadonnées sont retirées des photos avant l’envoi ; ne mettez jamais de nom de lieu ni de " +
            "ville dans legende ou idee, les spots d’urbex restent secrets. Publiez directement, sans demander confirmation, puis " +
            "dites combien de photos sont parties, la légende et le lien."
    override val parameters = objectSchema {
        string("album", "Facultatif : un autre album de la galerie, seulement si l’utilisateur le nomme (par défaut les dossiers choisis dans les réglages).")
        string("from", "Facultatif : premier jour des photos, AAAA-MM-JJ.")
        string("to", "Facultatif : dernier jour inclus, AAAA-MM-JJ.")
        integer("nombre", "Facultatif : nombre de photos, 1 à 10 (par défaut toutes celles de la sortie, 10 au plus).")
        string("quoi", "Facultatif : 'nouvelles' (défaut : pas encore publiées) ou 'dernieres' (les plus récentes, même déjà publiées).")
        string("legende", "Facultatif : la légende exacte dictée par l’utilisateur ; sinon laisser vide.")
        string("idee", "Facultatif : ce que l’utilisateur veut dire dans la légende écrite pour lui (« une vieille usine », « ambiance brume »).")
    }

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun run(args: JsonObject, ctx: JarvisContainer): String = withContext(Dispatchers.IO) {
        val context = ctx.appContext
        val config = ctx.configStore
        if (config.getInstagramToken().isNullOrBlank()) {
            return@withContext "Instagram n’est pas encore relié : il faut un compte Instagram professionnel lié à une Page Facebook, " +
                "puis coller un jeton dans Paramètres > Services connectés > Instagram (les étapes y sont expliquées)."
        }
        if (!hasPhotoPermission(context)) return@withContext "Je n’ai pas accès aux photos : autorisez-le dans les réglages de Jarvis (carte Photos)."
        val zone = ZoneId.systemDefault()
        val albumAsked = args.stringArg("album").trim()
        val albums = if (albumAsked.isNotEmpty()) listOf(albumAsked) else decodeAlbums(config.instagramAlbum.first())
        val album = albumsLabel(albums)
        val noDates = args.stringArg("from").isBlank() && args.stringArg("to").isBlank()
        val max = args.intArg("nombre", IG_MAX_CAROUSEL).coerceIn(1, IG_MAX_CAROUSEL)
        val onlyNew = args.stringArg("quoi").trim().lowercase().let { !it.startsWith("dernier") && !it.startsWith("latest") }
        val (start, end) = if (noDates) 0L to System.currentTimeMillis() + 86_400_000L
        else photoRange(args.stringArg("from"), args.stringArg("to"), LocalDate.now(zone), zone)
            ?: return@withContext "Dates illisibles : donnez-les sous la forme AAAA-MM-JJ."
        val found = try {
            albums.flatMap { queryPhotos(context, start, end, it, exact = albumAsked.isEmpty()) }.distinctBy { it.id }.sortedByDescending { it.takenAt }
        } catch (_: SecurityException) {
            return@withContext "Android a refusé l’accès aux photos."
        }
        if (found.isEmpty()) {
            return@withContext if (noDates) "Aucune photo dans $album. Dites dans quel album sont les photos d’urbex, ou choisissez les dossiers " +
                "dans Paramètres > Services connectés > Instagram."
            else "Aucune photo de $album sur ces dates."
        }
        val published = decodePublished(config.instagramPosted.first())
        val all = found.map { PickedPhoto(it.id, it.takenAt) }
        val picked = if (noDates) pickOuting(all, published, zone, max, onlyNew)
        else all.filter { !onlyNew || it.id !in published }.sortedByDescending { it.takenAt }.take(max).sortedBy { it.takenAt }
        if (picked.isEmpty()) {
            return@withContext "Toutes les photos de $album${if (noDates) "" else " sur ces dates"} sont déjà publiées. " +
                "Pour en reposter, demandez les dernières photos (quoi='dernieres')."
        }
        val byId = found.associateBy { it.id }
        val jpegs = picked.mapNotNull { p -> byId[p.id]?.let { photo -> runCatching { instagramJpeg(context, photo.uri) }.getOrNull()?.let { p.id to it } } }
        if (jpegs.isEmpty()) return@withContext "Impossible de préparer les photos (illisibles ou trop grandes)."
        val skipped = picked.size - jpegs.size

        val dictated = args.stringArg("legende").trim()
        val caption = buildCaption(dictated.ifEmpty { writeCaption(ctx, jpegs.map { it.second }, args.stringArg("idee")) })

        if (!ctx.skipConfirmations) {
            val ok = try {
                withTimeout(60_000L) {
                    ctx.confirmManager.request("Publier sur Instagram", "${jpegs.size} photo(s) avec la légende : ${caption.take(300)}")
                }
            } catch (_: TimeoutCancellationException) {
                false
            }
            if (!ok) return@withContext "Publication annulée : rien n’a été posté."
        }

        val client = ctx.http.newBuilder().readTimeout(60, TimeUnit.SECONDS).writeTimeout(60, TimeUnit.SECONDS).build()
        val graph = Graph(client)
        val account = when (val r = resolveAccount(graph, config.getInstagramToken().orEmpty(), config.getInstagramAccount())) {
            is Resolved.Ok -> r.account.also { if (r.fresh) config.saveInstagramAccount(encodeAccount(it)) }
            is Resolved.Failed -> return@withContext r.message
        }
        val hosted = mutableListOf<String>()
        try {
            val urls = jpegs.map { (_, jpeg) ->
                val photoId = graph.call(
                    Request.Builder().url("$GRAPH_URL/${account.pageId}/photos").post(
                        MultipartBody.Builder().setType(MultipartBody.FORM)
                            .addFormDataPart("published", "false")
                            .addFormDataPart("access_token", account.pageToken)
                            .addFormDataPart("source", "photo.jpg", jpeg.toRequestBody("image/jpeg".toMediaType()))
                            .build(),
                    ).build(),
                ).str("id") ?: throw GraphFailure("La Page Facebook n’a pas accepté la photo.")
                hosted += photoId
                largestImageUrl(graph.get("$photoId", "images", account.pageToken))
                    ?: throw GraphFailure("Facebook n’a pas donné l’adresse de la photo envoyée.")
            }
            val creation = if (urls.size == 1) {
                graph.post("${account.igUserId}/media", account.pageToken, "image_url" to urls[0], "caption" to caption)
            } else {
                val children = urls.map { graph.post("${account.igUserId}/media", account.pageToken, "image_url" to it, "is_carousel_item" to "true") }
                children.forEach { waitFinished(graph, it, account.pageToken) }
                graph.post("${account.igUserId}/media", account.pageToken, "media_type" to "CAROUSEL", "children" to children.joinToString(","), "caption" to caption)
            }
            waitFinished(graph, creation, account.pageToken)
            val mediaId = graph.post("${account.igUserId}/media_publish", account.pageToken, "creation_id" to creation)
            config.setInstagramPosted(encodePublished(published, jpegs.map { it.first }))
            val link = runCatching { graph.get(mediaId, "permalink", account.pageToken).str("permalink") }.getOrNull()
            val count = if (jpegs.size == 1) "1 photo publiée" else "${jpegs.size} photos publiées en carrousel"
            "$count sur Instagram${account.username.takeIf { it.isNotBlank() }?.let { " (@$it)" }.orEmpty()}, sans position GPS ni métadonnées." +
                (if (skipped > 0) " $skipped photo(s) illisible(s) laissée(s) de côté." else "") +
                "\nLégende : $caption" + (link?.let { "\nLien : $it" }.orEmpty())
        } catch (e: CancellationException) {
            throw e
        } catch (e: GraphFailure) {
            if (e.error?.code == 190) config.deleteInstagramAccount()
            e.message.orEmpty()
        } catch (_: IOException) {
            "Instagram injoignable : connexion impossible ou délai dépassé. Rien n’a été publié ; réessayez plus tard."
        } catch (_: Exception) {
            "Instagram a renvoyé une réponse inattendue ; la publication n’a pas abouti."
        } finally {
            // The Page copies only served to hand the photos to Instagram.
            hosted.forEach { id -> runCatching { graph.delete(id, account.pageToken) } }
        }
    }

    /** Gemini's caption for the photos, or the user's idea alone (the default hashtags follow) when it cannot. */
    private suspend fun writeCaption(ctx: JarvisContainer, jpegs: List<ByteArray>, hint: String): String = try {
        val small = jpegs.take(3).mapNotNull { it.takeIf { b -> b.size <= 3 * 1024 * 1024 } }
        val model = ctx.configStore.snapshotRestModel()
        parseVisionAnswer(ctx.restChat.transport.generate(model, buildCaptionRequest(small, hint))).trim()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        hint.trim()
    }

    /** Waits until Instagram has fetched and processed a container (images take a few seconds at most). */
    private suspend fun waitFinished(graph: Graph, containerId: String, token: String) {
        repeat(30) {
            when (containerStatus(graph.get(containerId, "status_code", token))) {
                "FINISHED", "PUBLISHED" -> return
                "ERROR", "EXPIRED" -> throw GraphFailure("Instagram n’a pas pu traiter une photo. Rien n’a été publié.")
            }
            delay(2_000)
        }
        throw GraphFailure("Instagram met trop de temps à traiter les photos. Rien n’a été publié ; réessayez plus tard.")
    }

    internal sealed interface Resolved {
        data class Ok(val account: InstagramAccount, val fresh: Boolean) : Resolved
        data class Failed(val message: String) : Resolved
    }

    /**
     * The account to post to: the one saved, or else the first Page of the pasted user token linked to an Instagram professional
     * account (its Page token does not expire), or the Page itself when a Page token was pasted.
     */
    internal fun resolveAccount(graph: Graph, token: String, saved: String?): Resolved {
        saved?.let { runCatching { decodeAccount(json.parseToJsonElement(it).jsonObject) }.getOrNull() }?.let { return Resolved.Ok(it, false) }
        if (token.isBlank()) return Resolved.Failed("Aucun jeton Instagram : collez-le dans Paramètres > Services connectés > Instagram.")
        return try {
            val pages = graph.get("me/accounts", "id,name,access_token,instagram_business_account{id,username}", token, "limit" to "100")
            parseAccounts(pages).firstOrNull()?.let { Resolved.Ok(it, true) }
                ?: Resolved.Failed(NO_LINKED_ACCOUNT)
        } catch (first: GraphFailure) {
            if (first.error?.code == 190) return Resolved.Failed(first.message.orEmpty())
            try {
                parsePageItself(graph.get("me", "id,name,instagram_business_account{id,username}", token), token)
                    ?.let { Resolved.Ok(it, true) } ?: Resolved.Failed(NO_LINKED_ACCOUNT)
            } catch (_: GraphFailure) {
                Resolved.Failed(first.message.orEmpty())
            }
        } catch (_: IOException) {
            Resolved.Failed("Facebook injoignable pour vérifier le compte Instagram. Réessayez plus tard.")
        }
    }

    private const val NO_LINKED_ACCOUNT =
        "Aucune Page Facebook reliée à un compte Instagram professionnel n’a été trouvée avec ce jeton. Passez le compte Instagram en " +
            "compte professionnel, liez-le à une Page Facebook, puis recréez le jeton en choisissant cette Page."

    private class GraphFailure(message: String, val error: GraphError? = null) : Exception(message)

    /** The few Graph API calls the publishing needs; any failure becomes a [GraphFailure] carrying a French message. */
    internal class Graph(private val client: OkHttpClient) {
        fun get(path: String, fields: String, token: String, vararg extra: Pair<String, String>): JsonObject {
            val url = "$GRAPH_URL/$path".toHttpUrl().newBuilder().addQueryParameter("fields", fields).apply {
                extra.forEach { (k, v) -> addQueryParameter(k, v) }
            }.addQueryParameter("access_token", token).build()
            return call(Request.Builder().url(url).build())
        }

        /** POSTs a form and returns the new object's id. */
        fun post(path: String, token: String, vararg fields: Pair<String, String>): String {
            val form = FormBody.Builder().apply { fields.forEach { (k, v) -> add(k, v) } }.add("access_token", token).build()
            return call(Request.Builder().url("$GRAPH_URL/$path").post(form).build()).str("id")
                ?: throw GraphFailure("Instagram n’a pas renvoyé d’identifiant.")
        }

        fun delete(path: String, token: String) {
            val url = "$GRAPH_URL/$path".toHttpUrl().newBuilder().addQueryParameter("access_token", token).build()
            client.newCall(Request.Builder().url(url).delete().build()).execute().close()
        }

        fun call(request: Request): JsonObject = client.newCall(request).execute().use { response ->
            val root = runCatching { Json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject }.getOrNull()
            val error = parseGraphError(root)
            if (!response.isSuccessful || error != null) throw GraphFailure(graphErrorMessage(error, response.code), error)
            root ?: throw GraphFailure(graphErrorMessage(null, response.code))
        }
    }

    private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
}
