package com.jarvis.android.actions

import android.content.Intent
import android.provider.MediaStore
import com.jarvis.android.JarvisContainer
import com.jarvis.android.device.ActionResult
import com.jarvis.android.device.ElementMatch
import com.jarvis.android.device.JarvisAccessibilityService
import com.jarvis.android.device.ScreenElement
import com.jarvis.android.device.normalizeLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

private val SHUTTER_WORDS = listOf(
    "shutter", "obturateur", "declencheur", "prendre une photo", "prendre photo", "take photo",
    "take picture", "capture", "photographier",
)
private val FLIP_WORDS = listOf("selfie", "camera avant", "front camera", "switch camera", "flip", "retourner", "basculer", "inverser")

/** The most likely shutter button among the visible elements, if any. */
internal fun findShutter(elements: List<ScreenElement>): ScreenElement? =
    elements.firstOrNull { e ->
        e.clickable && normalizeLabel(e.label).let { l -> SHUTTER_WORDS.any { l.contains(it) } }
    }

internal fun findCameraFlip(elements: List<ScreenElement>): ScreenElement? =
    elements.firstOrNull { e ->
        e.clickable && normalizeLabel(e.label).let { l -> FLIP_WORDS.any { l.contains(it) } }
    }

/**
 * Opens the camera and presses the shutter. Honest about its limits: it reports what it did, not
 * that the picture exists, because a phone app cannot see whether the camera saved it.
 */
object TakePhotoTool : Tool {
    override val name = "take_photo"
    override val description =
        "Prendre une photo : ouvre l’appareil photo et appuie sur le déclencheur. Ne peut pas confirmer que la photo est enregistrée."
    override val parameters = objectSchema {
        string("camera", "Facultatif : 'front' pour le selfie, sinon l’appareil arrière.")
    }

    override suspend fun run(args: JsonObject, ctx: JarvisContainer): String {
        val front = args["camera"]?.jsonPrimitive?.contentOrNull.orEmpty().trim().lowercase() in setOf("front", "avant", "selfie")
        try {
            ctx.appContext.startActivity(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            return "Impossible d’ouvrir l’appareil photo. Aucune photo prise."
        }
        val service = JarvisAccessibilityService.instance
        if (service == null || !ctx.configStore.deviceControlEnabled.first()) {
            return "L’appareil photo est ouvert, mais je ne peux pas appuyer sur le déclencheur : le contrôle du téléphone n’est pas activé. " +
                "Aucune photo n’a été prise ; l’utilisateur doit appuyer lui-même sur le déclencheur."
        }
        return withContext(Dispatchers.Default) {
            delay(2_000)
            var snapshot = service.readScreen()
            var tries = 0
            while (snapshot != null && findShutter(snapshot.elements) == null && tries < 3) {
                delay(700)
                snapshot = service.readScreen()
                tries++
            }
            if (snapshot == null) return@withContext "L’appareil photo est ouvert mais l’écran est illisible. Aucune photo prise."
            if (front) {
                findCameraFlip(snapshot.elements)?.let {
                    service.tap(it.index)
                    delay(900)
                    snapshot = service.readScreen() ?: snapshot
                }
            }
            val shutter = snapshot?.let { findShutter(it.elements) }
            if (shutter == null) {
                return@withContext "L’appareil photo est ouvert mais je n’ai pas trouvé le bouton déclencheur : aucune photo prise. " +
                    "Dites à l’utilisateur d’appuyer lui-même sur le déclencheur."
            }
            when (val result = service.tap(shutter.index)) {
                is ActionResult.Failed -> "Le déclencheur n’a pas répondu : ${result.reason} Aucune photo prise."
                ActionResult.Done -> {
                    delay(1_200)
                    "J’ai appuyé sur le déclencheur (« ${shutter.label} »). Je ne peux pas confirmer que la photo est enregistrée : " +
                        "dites à l’utilisateur de vérifier dans la galerie."
                }
            }
        }
    }
}
