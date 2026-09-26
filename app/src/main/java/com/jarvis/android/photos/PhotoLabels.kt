package com.jarvis.android.photos

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeler
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import kotlin.coroutines.resume

/*
 * "Mes photos de chien": what is in each photo, as the English labels of ML Kit's on-device model ("Dog", "Beach",
 * "Food"…). A photo is looked at once, from its small thumbnail, and its labels are kept in a file of the app, so the
 * next search is instant; the photos not yet looked at are done while the phone charges. Nothing leaves the phone.
 */

@Serializable
private data class LabelData(val labels: Map<Long, List<String>> = emptyMap())

internal class PhotoLabelIndex(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true }
    private var cache: MutableMap<Long, List<String>>? = null

    @Synchronized
    private fun all(): MutableMap<Long, List<String>> = cache ?: (try {
        if (file.exists()) json.decodeFromString<LabelData>(file.readText()).labels.toMutableMap() else mutableMapOf()
    } catch (_: Exception) {
        mutableMapOf()
    }).also { cache = it }

    @Synchronized
    fun get(id: Long): List<String>? = all()[id]

    @Synchronized
    fun size(): Int = all().size

    @Synchronized
    fun putAll(found: Map<Long, List<String>>) {
        if (found.isEmpty()) return
        val map = all()
        map.putAll(found)
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.encodeToString(LabelData(map)))
            if (!tmp.renameTo(file)) { file.writeText(tmp.readText()); tmp.delete() }
        } catch (_: Exception) {
        }
    }
}

/** The words asked for, as lower-case English labels: "dog, puppy" becomes [dog, puppy]. */
internal fun labelTerms(labels: String): List<String> =
    labels.split(',', ';', '/').map { it.trim().lowercase() }.filter { it.length >= 2 }.distinct()

/** Whether a photo with [photoLabels] shows one of [terms]: "dog" matches the label "Dog", "car" matches "Car" but not "Cartoon". */
internal fun matchesLabels(photoLabels: List<String>, terms: List<String>): Boolean =
    terms.any { t -> photoLabels.any { l -> l.equals(t, ignoreCase = true) || l.lowercase().split(' ').contains(t) } }

private object Labeler {
    val client: ImageLabeler by lazy { ImageLabeling.getClient(ImageLabelerOptions.Builder().setConfidenceThreshold(0.6f).build()) }
}

private suspend fun <T> Task<T>.awaitOrNull(): T? = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { if (cont.isActive) cont.resume(it) }
    addOnFailureListener { if (cont.isActive) cont.resume(null) }
    addOnCanceledListener { if (cont.isActive) cont.resume(null) }
}

private fun thumbnail(context: Context, photo: Photo): Bitmap? = try {
    if (Build.VERSION.SDK_INT >= 29) context.contentResolver.loadThumbnail(photo.uri, Size(384, 384), null)
    else @Suppress("DEPRECATION") MediaStore.Images.Thumbnails.getThumbnail(context.contentResolver, photo.id, MediaStore.Images.Thumbnails.MINI_KIND, null)
} catch (_: Exception) {
    null
}

/** The labels of [photo], or null when it cannot be read. */
internal suspend fun labelPhoto(context: Context, photo: Photo): List<String>? {
    val bitmap = thumbnail(context, photo) ?: return null
    val labels = Labeler.client.process(InputImage.fromBitmap(bitmap, 0)).awaitOrNull() ?: return null
    return labels.map { it.text }
}

/** Labels the photos of [photos] not yet in [index], newest first, until [deadlineMs]. Returns how many are still to do. */
internal suspend fun labelMissing(context: Context, index: PhotoLabelIndex, photos: List<Photo>, deadlineMs: Long): Int {
    val missing = photos.filter { index.get(it.id) == null }
    val found = HashMap<Long, List<String>>()
    var done = 0
    for (p in missing) {
        if (System.currentTimeMillis() > deadlineMs) break
        // An unreadable photo is kept with no labels, so it is not tried again every time.
        found[p.id] = labelPhoto(context, p) ?: emptyList()
        done++
        if (found.size >= 100) { index.putAll(found); found.clear() }
    }
    index.putAll(found)
    return missing.size - done
}

internal fun photoLabelIndex(context: Context) = PhotoLabelIndex(File(context.filesDir, "photo_labels.json"))

/** Looks at the photos of the last two years not yet labelled, while the phone charges. */
class PhotoIndexWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!hasPhotoPermission(applicationContext)) return Result.success()
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val photos = try {
            queryPhotos(applicationContext, today.minusYears(2).atStartOfDay(zone).toInstant().toEpochMilli(), System.currentTimeMillis() + 86_400_000L, "", limit = 20_000)
        } catch (_: Exception) {
            return Result.success()
        }
        val left = labelMissing(applicationContext, photoLabelIndex(applicationContext), photos, System.currentTimeMillis() + 8 * 60_000L)
        return if (left > 0) Result.retry() else Result.success()
    }

    companion object {
        fun schedule(context: Context) {
            val request = OneTimeWorkRequestBuilder<PhotoIndexWorker>()
                .setConstraints(Constraints.Builder().setRequiresCharging(true).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("photo_labels", ExistingWorkPolicy.KEEP, request)
        }
    }
}
