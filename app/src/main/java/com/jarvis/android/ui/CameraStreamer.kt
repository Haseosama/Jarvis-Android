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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import com.jarvis.android.core.FrameGate
import com.jarvis.android.core.VIDEO_MAX_SIDE
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
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    LaunchedEffect(active, granted) {
        if (active && !granted) permission.launch(Manifest.permission.CAMERA)
    }

    DisposableEffect(active, granted, lifecycleOwner) {
        if (!active || !granted) return@DisposableEffect onDispose { }
        val executor = Executors.newSingleThreadExecutor()
        val gate = FrameGate()
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
                    if (gate.shouldSend(now, now.toInt())) {
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
                } finally {
                    image.close()
                }
            }
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, analysis)
            } catch (_: Exception) {
                // Camera unavailable: no frames are sent.
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            provider?.unbindAll()
            executor.shutdown()
        }
    }
}
