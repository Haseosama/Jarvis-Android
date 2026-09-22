package com.jarvis.android.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import com.jarvis.android.core.VIDEO_MAX_SIDE
import com.jarvis.android.core.VIDEO_MIN_INTERVAL_MS
import com.jarvis.android.rest.scaledSize
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/**
 * While [active], reads camera pictures and hands each accepted one to [onFrame] as a JPEG.
 * The camera is bound to the screen's lifecycle, so it only runs while the app is in front.
 */
@Composable
internal fun CameraStreamer(active: Boolean, onFrame: (ByteArray) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    // A plain val re-reads the permission only when this composable happens to recompose for some
    // other reason; the grant callback below used to be a no-op, so after granting the permission
    // the very first time nothing ever told Compose to look again — the camera stayed unbound
    // forever and Jarvis never received a single frame, even though the system dialog said yes.
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted -> granted = isGranted }

    LaunchedEffect(active, granted) {
        if (active && !granted) permission.launch(Manifest.permission.CAMERA)
    }

    DisposableEffect(active, granted, lifecycleOwner) {
        if (!active || !granted) return@DisposableEffect onDispose { }
        val executor = Executors.newSingleThreadExecutor()
        var lastSentAt = Long.MIN_VALUE
        val providerFuture = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        providerFuture.addListener({
            val cameraProvider = providerFuture.get()
            provider = cameraProvider
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(executor) { image ->
                try {
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (lastSentAt == Long.MIN_VALUE || now - lastSentAt >= VIDEO_MIN_INTERVAL_MS) {
                        lastSentAt = now
                        val bitmap = image.toBitmap()
                        val rotated = Bitmap.createBitmap(
                            bitmap, 0, 0, bitmap.width, bitmap.height,
                            Matrix().apply { postRotate(image.imageInfo.rotationDegrees.toFloat()) }, true,
                        )
                        val (w, h) = scaledSize(rotated.width, rotated.height, VIDEO_MAX_SIDE)
                        val small = Bitmap.createScaledBitmap(rotated, w, h, true)
                        val out = ByteArrayOutputStream()
                        small.compress(Bitmap.CompressFormat.JPEG, 70, out)
                        onFrame(out.toByteArray())
                    }
                } catch (e: Exception) {
                    android.util.Log.w("JarvisCamera", "Image de la caméra ignorée : ${e.message}")
                } finally {
                    image.close()
                }
            }
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, analysis)
            } catch (e: Exception) {
                android.util.Log.w("JarvisCamera", "Caméra indisponible : ${e.message}")
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            provider?.unbindAll()
            executor.shutdown()
        }
    }
}
