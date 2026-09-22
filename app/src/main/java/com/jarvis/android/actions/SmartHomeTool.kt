package com.jarvis.android.actions

import com.jarvis.android.JarvisContainer
import com.jarvis.android.offline.normalize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/*
 * Home Assistant control — not a Mark-LIII port, no direct equivalent there. The user runs their own
 * Home Assistant server (a self-hosted home automation hub: home-assistant.io) and pastes its address
 * and a Long-Lived Access Token (created from their Home Assistant profile page) into Settings >
 * "Maison connectée". Chosen over Google Home / Amazon Alexa because those need the USER to first
 * register their own Google Cloud project and a paid "Device Access" console (Google) or a developer
 * account (Amazon) before any of this code could even be tried — Home Assistant's REST API needs only
 * a URL and a token, like the Gemini key already does.
 */

/** One entity as Home Assistant reports it: `light.salon` is entityId, `light` is domain. */
internal data class HaEntity(val entityId: String, val domain: String, val friendlyName: String, val state: String, val attributes: JsonObject)

/** Domains this tool will look inside when matching a name. Anything else (sensors, automations…) is read-only clutter, left out. */
internal val HA_CONTROLLABLE_DOMAINS = setOf(
    "light", "switch", "fan", "cover", "climate", "media_player", "lock", "scene", "script", "input_boolean", "humidifier", "vacuum",
)

/** Parses the JSON array returned by GET /api/states. */
internal fun parseHaStates(body: String): List<HaEntity> {
    val root = Json.parseToJsonElement(body)
    val array = root as? JsonArray ?: error("Invalid Home Assistant states payload")
    return array.mapNotNull { el ->
        val obj = el as? JsonObject ?: return@mapNotNull null
        val entityId = (obj["entity_id"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
        val domain = entityId.substringBefore('.', "")
        if (domain.isEmpty()) return@mapNotNull null
        val state = (obj["state"] as? JsonPrimitive)?.contentOrNull ?: "unknown"
        val attributes = obj["attributes"] as? JsonObject ?: JsonObject(emptyMap())
        val friendlyName = (attributes["friendly_name"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: entityId
        HaEntity(entityId, domain, friendlyName, state, attributes)
    }
}

/** Entities whose friendly name matches every word of [query] (accent/case-insensitive), restricted to [domains], exact match first. */
internal fun findHaEntities(entities: List<HaEntity>, query: String, domains: Set<String>): List<HaEntity> {
    val q = normalize(query)
    if (q.isEmpty()) return emptyList()
    val words = q.split(Regex("\\s+")).filter { it.isNotEmpty() }
    return entities.filter { it.domain in domains }
        .filter { e -> val name = normalize(e.friendlyName); words.all { it in name } }
        .sortedWith(compareBy({ if (normalize(it.friendlyName) == q) 0 else 1 }, { normalize(it.friendlyName) }))
        .distinctBy { it.entityId }
}

/** The domains a given action makes sense for, or null when the action name is not recognised. */
internal fun haDomainsFor(action: String): Set<String>? = when (action) {
    "status", "turn_on", "turn_off", "toggle" -> HA_CONTROLLABLE_DOMAINS
    "set_brightness" -> setOf("light")
    "set_temperature" -> setOf("climate")
    else -> null
}

/**
 * The (domain, service) to POST to /api/services/. turn_on/off/toggle go through Home Assistant's own
 * generic "homeassistant" domain, which dispatches to the right per-domain service by itself (lights,
 * switches, covers, climate, media players, scenes, scripts… all understand it) — simpler and more
 * future-proof than hand-mapping each domain's own verb (cover uses open_cover/close_cover, lock uses
 * lock/unlock, etc.). Null means [entity]'s domain does not support that action.
 */
internal fun haService(action: String, entity: HaEntity): Pair<String, String>? = when (action) {
    "turn_on" -> "homeassistant" to "turn_on"
    "turn_off" -> "homeassistant" to "turn_off"
    "toggle" -> "homeassistant" to "toggle"
    "set_brightness" -> "light" to "turn_on".takeIf { entity.domain == "light" }
    "set_temperature" -> "climate" to "set_temperature".takeIf { entity.domain == "climate" }
    else -> null
}?.let { (d, s) -> if (s == null) null else d to s }

internal fun haServiceBody(action: String, entity: HaEntity, value: Int?): JsonObject = buildJsonObject {
    put("entity_id", entity.entityId)
    when (action) {
        "set_brightness" -> value?.let { put("brightness_pct", it) }
        "set_temperature" -> value?.let { put("temperature", it) }
    }
}

internal fun haSuccessMessage(action: String, entity: HaEntity, value: Int?): String = when (action) {
    "turn_on" -> "« ${entity.friendlyName} » allumé."
    "turn_off" -> "« ${entity.friendlyName} » éteint."
    "toggle" -> "« ${entity.friendlyName} » basculé."
    "set_brightness" -> "Luminosité de « ${entity.friendlyName} » réglée à $value %."
    "set_temperature" -> "Température de « ${entity.friendlyName} » réglée à $value °C."
    else -> "« ${entity.friendlyName} » : fait."
}

internal fun describeHaEntity(e: HaEntity): String {
    val stateLabel = when (e.state) {
        "on" -> "allumé"
        "off" -> "éteint"
        "unavailable" -> "indisponible"
        "unknown" -> "état inconnu"
        else -> e.state
    }
    val extra = when (e.domain) {
        "light" -> (e.attributes["brightness"] as? JsonPrimitive)?.intOrNull
            ?.takeIf { e.state == "on" }?.let { " (${it * 100 / 255} %)" }.orEmpty()
        "climate" -> (e.attributes["current_temperature"] as? JsonPrimitive)?.doubleOrNull
            ?.let { " ($it °C)" }.orEmpty()
        else -> ""
    }
    return "${e.friendlyName} : $stateLabel$extra"
}

internal fun formatHaStatusList(entities: List<HaEntity>): String = entities.joinToString("\n") { describeHaEntity(it) }

object SmartHomeTool : Tool {
    override val name = "smart_home"
    override val description =
        "Contrôler la maison connectée via le serveur Home Assistant de l'utilisateur (adresse et jeton configurés dans Réglages > " +
            "Maison connectée ; si absent, dites-le et indiquez où le configurer). Actions : status (état d'un appareil nommé, ou de tous " +
            "les appareils contrôlables si aucun nom n'est donné), turn_on, turn_off, toggle, set_brightness (value = 0 à 100), " +
            "set_temperature (value = degrés Celsius). Donnez name tel que l'utilisateur l'a dit (pièce ou appareil, ex. « lampe du salon », " +
            "« chauffage », « volet de la cuisine »). S'il y a plusieurs correspondances, l'outil les liste avec des numéros : redemandez " +
            "lequel à l'utilisateur puis rappelez avec le même name et choice."
    override val parameters = objectSchema(required = listOf("action")) {
        string("action", "'status' (défaut), 'turn_on', 'turn_off', 'toggle', 'set_brightness' ou 'set_temperature'.")
        string("name", "Nom de l'appareil ou de la pièce tel que dit par l'utilisateur ; vide pour la liste complète (status seulement).")
        integer("value", "set_brightness : luminosité 0-100. set_temperature : température en degrés Celsius.")
        integer("choice", "Numéro du choix (à partir de 1) quand plusieurs appareils correspondent.")
    }

    override suspend fun run(args: JsonObject, ctx: JarvisContainer): String {
        val action = args.stringArg("action").trim().lowercase().ifEmpty { "status" }
        val domains = haDomainsFor(action)
            ?: return "Action inconnue : utilisez status, turn_on, turn_off, toggle, set_brightness ou set_temperature."

        val base = ctx.configStore.homeAssistantUrl.first().trim().trimEnd('/')
        if (base.isEmpty()) return "Home Assistant n'est pas configuré : Réglages > Maison connectée, indiquez l'adresse du serveur et un jeton d'accès."
        val token = ctx.configStore.getHomeAssistantToken()
        if (token.isNullOrBlank()) return "Home Assistant n'est pas configuré : Réglages > Maison connectée, ajoutez un jeton d'accès longue durée (créé depuis le profil Home Assistant)."

        val (entities, fetchError) = fetchHaStates(ctx, base, token)
        if (entities == null) return fetchError!!

        val name = args.stringArg("name").trim()
        if (action == "status" && name.isEmpty()) {
            val list = formatHaStatusList(entities.filter { it.domain in HA_CONTROLLABLE_DOMAINS }.sortedBy { normalize(it.friendlyName) }.take(40))
            return list.ifEmpty { "Aucun appareil contrôlable trouvé sur ce serveur Home Assistant." }
        }
        if (name.isEmpty()) return "Indiquez le nom de l'appareil ou de la pièce."

        val matches = findHaEntities(entities, name, domains)
        if (matches.isEmpty()) return "Aucun appareil ne correspond à « ${name.take(60)} »."
        val chosen = if (matches.size == 1) matches[0] else {
            val index = args.intArg("choice", 0)
            if (index in 1..matches.size) matches[index - 1]
            else return "Plusieurs correspondances : " + matches.take(8).mapIndexed { i, e -> "${i + 1}) ${e.friendlyName}" }.joinToString(" ; ") +
                ". Demandez laquelle à l'utilisateur, puis rappelez avec le même name et choice."
        }

        if (action == "status") return describeHaEntity(chosen)

        val value = args.intArg("value", -1).takeIf { it in 0..100 }
        if ((action == "set_brightness" || action == "set_temperature") && value == null) {
            return "Indiquez une valeur (0 à 100 pour la luminosité, en degrés pour la température)."
        }
        val (svcDomain, service) = haService(action, chosen)
            ?: return "« ${chosen.friendlyName} » (${chosen.domain}) ne prend pas en charge cette action."
        val error = callHaService(ctx, base, token, svcDomain, service, haServiceBody(action, chosen, value))
        return error ?: haSuccessMessage(action, chosen, value)
    }
}

/** GET /api/states. A null entity list means [fetchError] explains why (bad token, bad address, network, or an unexpected reply). */
private suspend fun fetchHaStates(ctx: JarvisContainer, base: String, token: String): Pair<List<HaEntity>?, String?> = withContext(Dispatchers.IO) {
    try {
        val request = Request.Builder().url("$base/api/states").header("Authorization", "Bearer $token").build()
        ctx.http.newCall(request).execute().use { resp ->
            when {
                resp.code == 401 || resp.code == 403 -> null to "Jeton Home Assistant refusé : vérifiez le jeton dans Réglages > Maison connectée."
                !resp.isSuccessful -> null to "Home Assistant a répondu une erreur (HTTP ${resp.code}). Vérifiez l'adresse du serveur dans les réglages."
                else -> try {
                    parseHaStates(resp.body?.string().orEmpty()) to null
                } catch (_: Exception) {
                    null to "Réponse de Home Assistant inattendue : vérifiez que l'adresse pointe bien sur le serveur Home Assistant lui-même."
                }
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: IOException) {
        null to "Impossible de joindre Home Assistant : connexion impossible ou délai dépassé. Vérifiez l'adresse, et que le téléphone peut l'atteindre (même réseau, ou accès distant configuré côté Home Assistant)."
    }
}

/** POST /api/services/{domain}/{service}. Null means success; otherwise the message to report. */
private suspend fun callHaService(ctx: JarvisContainer, base: String, token: String, domain: String, service: String, body: JsonObject): String? =
    withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url("$base/api/services/$domain/$service")
                .header("Authorization", "Bearer $token")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            ctx.http.newCall(request).execute().use { resp ->
                when {
                    resp.code == 401 || resp.code == 403 -> "Jeton Home Assistant refusé : vérifiez le jeton dans Réglages > Maison connectée."
                    !resp.isSuccessful -> "Home Assistant a refusé l'action (HTTP ${resp.code})."
                    else -> null
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: IOException) {
            "Impossible de joindre Home Assistant : connexion impossible ou délai dépassé."
        }
    }
