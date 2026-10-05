package com.jarvis.android.ar

import android.Manifest
import android.content.pm.PackageManager
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.ar.core.Anchor
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Plane
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.jarvis.android.JarvisApp
import com.jarvis.android.avatar.AvatarView
import com.jarvis.android.core.JarvisState
import com.jarvis.android.i18n.tr
import com.jarvis.android.ui.theme.JarvisTheme
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.ConcurrentLinkedQueue
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Haseo in augmented reality: the face put down on a table seen through the camera, turning to you as you walk round it (see [ArLook]).
 * With ARCore ("Services Google Play pour la RA"), a touch on a table found by the camera puts the head there, at the size of a small
 * bust; it stays on that spot when the phone moves. Without ARCore (a phone it does not support, or its install refused), the simple
 * mode: the face floats over the camera's picture where the screen is touched, looking at you. A voice session going on goes on: the face
 * speaks and moves its lips on the table as on the main screen.
 */
class ArFaceActivity : ComponentActivity() {
    private enum class Mode { CHECKING, AR, SIMPLE, NO_CAMERA }

    private val mode = mutableStateOf(Mode.CHECKING)
    /** Why the simple mode, said on screen; empty when nothing needs saying. */
    private val simpleWhy = mutableStateOf("")
    private val hint = MutableStateFlow(HINT_LOOK)
    private val placement = MutableStateFlow<ArPlacement?>(null)

    private var session: Session? = null
    private var installRequested = false
    private var glView: GLSurfaceView? = null
    private val taps = ConcurrentLinkedQueue<FloatArray>()

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) mode.value = Mode.NO_CAMERA
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val container = (application as JarvisApp).container
        setContent {
            val hue by container.configStore.themeHue.collectAsState(initial = 190f)
            JarvisTheme(hue = hue) {
                val state by container.engine.state.collectAsState()
                val level by container.engine.outputLevel.collectAsState()
                Screen(state, level)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        start()
    }

    /** Asks for the camera, then starts ARCore (or the simple mode without it). */
    private fun start() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            if (mode.value != Mode.NO_CAMERA) permission.launch(Manifest.permission.CAMERA)
            return
        }
        if (mode.value == Mode.NO_CAMERA) mode.value = Mode.CHECKING
        if (mode.value == Mode.SIMPLE) return
        if (session == null && !openSession()) return
        try {
            session?.resume()
            glView?.onResume()
            mode.value = Mode.AR
        } catch (_: Exception) {
            // the camera taken by another app, or ARCore failing to start: the simple mode still shows the face
            closeSession()
            goSimple(tr("La réalité augmentée n'a pas pu démarrer : Haseo flotte devant la caméra."))
        }
    }

    /** Makes the ARCore session, asking for ARCore's install once; false when the screen waits (an install) or went to the simple mode. */
    private fun openSession(): Boolean {
        val availability = ArCoreApk.getInstance().checkAvailability(this)
        if (availability.isTransient) {
            // ARCore is still finding out whether the phone supports it: ask again in a moment
            window.decorView.postDelayed({ if (!isFinishing && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) start() }, 250)
            return false
        }
        if (availability.isUnsupported) {
            goSimple(tr("Ce téléphone ne prend pas en charge ARCore : Haseo flotte devant la caméra au lieu de se poser sur la table."))
            return false
        }
        return try {
            when (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
                ArCoreApk.InstallStatus.INSTALL_REQUESTED -> { installRequested = true; false }
                else -> {
                    val s = Session(this)
                    val config = Config(s).apply {
                        planeFindingMode = Config.PlaneFindingMode.HORIZONTAL
                        updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                        focusMode = Config.FocusMode.AUTO
                        lightEstimationMode = Config.LightEstimationMode.DISABLED
                    }
                    s.configure(config)
                    session = s
                    true
                }
            }
        } catch (_: com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException) {
            goSimple(tr("Sans « Services Google Play pour la RA », Haseo flotte devant la caméra. Installe-les pour le poser sur la table."))
            false
        } catch (_: Exception) {
            goSimple(tr("ARCore n'est pas disponible : Haseo flotte devant la caméra au lieu de se poser sur la table."))
            false
        }
    }

    private fun goSimple(why: String) {
        simpleWhy.value = why
        mode.value = Mode.SIMPLE
    }

    override fun onPause() {
        super.onPause()
        glView?.onPause()
        session?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        closeSession()
        // back on the main screen the face wanders again
        (application as JarvisApp).container.avatar.avatar.aim = null
    }

    private fun closeSession() {
        val s = session ?: return
        session = null
        try { s.close() } catch (_: Exception) {}
    }

    @Composable
    private fun Screen(state: JarvisState, level: Float) {
        val container = (application as JarvisApp).container
        val current by mode
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            when (current) {
                Mode.AR -> {
                    AndroidView(factory = { context -> arView(context) }, modifier = Modifier.fillMaxSize())
                    ArFace(container.avatar, state, level)
                }
                Mode.SIMPLE -> SimpleFace(container.avatar, state, level)
                Mode.NO_CAMERA -> Message(tr("Sans l'appareil photo, Haseo ne peut pas se poser dans la pièce. Autorise la caméra pour Jarvis."))
                Mode.CHECKING -> Unit
            }
            IconButton(onClick = { finish() }, modifier = Modifier.align(Alignment.TopEnd).safeDrawingPadding().padding(8.dp)) {
                Icon(Icons.Filled.Close, contentDescription = tr("Fermer"), tint = Color.White)
            }
        }
    }

    /** The face on the table, where the GL thread last placed it, with its soft shadow under it. */
    @Composable
    private fun ArFace(controller: com.jarvis.android.avatar.AvatarController, state: JarvisState, level: Float) {
        val placed by placement.collectAsState()
        val line by hint.collectAsState()
        val shown = placed
        if (shown != null) {
            Canvas(Modifier.fillMaxSize()) {
                val w = shown.shadowWidth; val h = shown.shadowHeight
                drawOval(
                    Brush.radialGradient(listOf(Color.Black.copy(alpha = 0.45f), Color.Transparent), center = Offset(shown.shadowX, shown.shadowY), radius = w / 2f),
                    topLeft = Offset(shown.shadowX - w / 2f, shown.shadowY - h / 2f), size = Size(w, h),
                )
            }
            AvatarView(controller, state, level, Modifier.layout { measurable, _ ->
                // read in the layout step: the head moves with the camera without the screen being composed again
                val p = placement.value ?: shown
                val side = p.side.roundToInt().coerceAtLeast(1)
                val placeable = measurable.measure(Constraints.fixed(side, side))
                layout(0, 0) { placeable.place(p.left.roundToInt(), p.top.roundToInt()) }
            })
        }
        if (line.isNotEmpty()) Hint(line)
    }

    /** No ARCore: the camera's picture, and the face floating where the screen was last touched, looking at you. */
    @Composable
    private fun SimpleFace(controller: com.jarvis.android.avatar.AvatarController, state: JarvisState, level: Float) {
        val owner = LocalLifecycleOwner.current
        val spot = androidx.compose.runtime.remember { mutableStateOf<Offset?>(null) }
        DisposableEffect(controller) {
            controller.avatar.aim = floatArrayOf(0f, 0f, 0f, 0f)
            onDispose { controller.avatar.aim = null }
        }
        AndroidView(factory = { context ->
            PreviewView(context).also { view ->
                val future = ProcessCameraProvider.getInstance(context)
                future.addListener({
                    try {
                        val provider = future.get()
                        val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
                        provider.unbindAll()
                        provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview)
                    } catch (_: Exception) {
                        // no back camera: the face shows on black
                    }
                }, ContextCompat.getMainExecutor(context))
            }
        }, modifier = Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { spot.value = it } })
        val at by spot
        AvatarView(controller, state, level, Modifier.layout { measurable, constraints ->
            val side = (SIMPLE_SHARE * constraints.maxWidth).roundToInt().coerceAtLeast(1)
            val placeable = measurable.measure(Constraints.fixed(side, side))
            val centre = at ?: Offset(constraints.maxWidth / 2f, constraints.maxHeight * 0.62f)
            layout(constraints.maxWidth, constraints.maxHeight) {
                placeable.place((centre.x - side / 2f).roundToInt(), (centre.y - FACE_CENTRE_SHARE * side).roundToInt())
            }
        })
        val why by simpleWhy
        Hint(if (why.isEmpty()) tr("Touche l'écran pour déplacer Haseo.") else why + " " + tr("Touche l'écran pour déplacer Haseo."))
    }

    @Composable
    private fun Hint(text: String) {
        Box(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp), contentAlignment = Alignment.BottomCenter) {
            Text(
                text, color = Color.White, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(14.dp)).padding(12.dp),
            )
        }
    }

    @Composable
    private fun Message(text: String) {
        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Text(text, color = Color.White, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge)
        }
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun arView(context: android.content.Context): GLSurfaceView = GLSurfaceView(context).also { view ->
        view.preserveEGLContextOnPause = true
        view.setEGLContextClientVersion(2)
        view.setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        view.setRenderer(ArRenderer(view))
        view.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        view.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_UP) taps.offer(floatArrayOf(e.x, e.y))
            true
        }
        glView = view
    }

    /** Draws the camera's picture and works out where the face stands, on the GL thread, at the camera's pace. */
    private inner class ArRenderer(private val view: GLSurfaceView) : GLSurfaceView.Renderer {
        private val background = ArBackground()
        private val look = ArLook()
        private val viewMatrix = FloatArray(16)
        private val projection = FloatArray(16)
        private var anchor: Anchor? = null
        private var width = 0
        private var height = 0
        private var textureGiven: Session? = null
        private var geometryGiven: Session? = null
        private var last = 0L

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            background.create()
            textureGiven = null
        }

        override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
            GLES20.glViewport(0, 0, w, h)
            width = w; height = h
            geometryGiven = null
        }

        override fun onDrawFrame(gl: GL10?) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
            val s = session ?: return
            if (textureGiven !== s) { s.setCameraTextureName(background.textureId); textureGiven = s }
            if (geometryGiven !== s && width > 0) {
                @Suppress("DEPRECATION") val rotation = view.display?.rotation ?: android.view.Surface.ROTATION_0
                s.setDisplayGeometry(rotation, width, height)
                geometryGiven = s
            }
            val frame = try { s.update() } catch (_: Exception) { return }
            background.draw(frame)
            val camera = frame.camera
            val now = SystemClock.elapsedRealtimeNanos()
            val dt = if (last == 0L) 0f else ((now - last) / 1e9f).coerceAtMost(0.2f)
            last = now
            if (camera.trackingState != TrackingState.TRACKING) {
                hint.value = if (anchor == null) HINT_LOOK else HINT_LOST
                return
            }
            // a touch on a table puts the head there (another touch moves it)
            while (true) {
                val tap = taps.poll() ?: break
                val hit = frame.hitTest(tap[0], tap[1]).firstOrNull { h ->
                    val plane = h.trackable as? Plane
                    plane != null && plane.type == Plane.Type.HORIZONTAL_UPWARD_FACING && plane.isPoseInPolygon(h.hitPose)
                }
                if (hit != null) {
                    anchor?.detach()
                    anchor = hit.createAnchor()
                    look.reset()
                }
            }
            val a = anchor
            if (a == null || a.trackingState != TrackingState.TRACKING) {
                placement.value = null
                hint.value = when {
                    a != null -> HINT_LOST
                    s.getAllTrackables(Plane::class.java).any { it.trackingState == TrackingState.TRACKING && it.type == Plane.Type.HORIZONTAL_UPWARD_FACING && it.subsumedBy == null } -> HINT_TOUCH
                    else -> HINT_LOOK
                }
                return
            }
            camera.getViewMatrix(viewMatrix, 0)
            camera.getProjectionMatrix(projection, 0, 0.05f, 50f)
            val base = a.pose
            val hx = base.tx(); val hy = base.ty() + AR_HEAD_LIFT; val hz = base.tz()
            val eye = camera.pose
            (application as JarvisApp).container.avatar.avatar.aim = look.step(dt, hx, hy, hz, eye.tx(), eye.ty(), eye.tz())
            val head = projectPoint(viewMatrix, projection, hx, hy, hz, width, height)
            val foot = projectPoint(viewMatrix, projection, base.tx(), base.ty(), base.tz(), width, height)
            if (head == null || foot == null) {
                placement.value = null
                hint.value = HINT_BEHIND
                return
            }
            val square = faceSquare(head, max(width, height) * 3f)
            // the shadow: a disc on the table seen from the camera's height, flatter the lower the camera
            val flat = kotlin.math.hypot(eye.tx() - base.tx(), eye.tz() - base.tz())
            val squash = (kotlin.math.sin(kotlin.math.atan2(eye.ty() - base.ty(), flat))).coerceIn(0.2f, 1f)
            val shadow = 2.2f * AR_HEAD_HALF * foot.scale
            placement.value = ArPlacement(square[0], square[1], square[2], foot.x, foot.y, shadow, shadow * squash)
            hint.value = ""
        }
    }

    private companion object {
        val HINT_LOOK = tr("Bouge doucement le téléphone en visant la table, le temps que je la trouve.")
        val HINT_TOUCH = tr("Touche la table pour y poser Haseo.")
        val HINT_LOST = tr("Je ne vois plus bien la pièce : vise à nouveau la table.")
        val HINT_BEHIND = tr("Haseo est derrière toi : retourne-toi vers la table.")
        const val SIMPLE_SHARE = 0.62f
    }
}

/** Where the face's square view goes on the screen ([left], [top], [side] in pixels) and its shadow on the table (centre, width, height). */
internal data class ArPlacement(
    val left: Float, val top: Float, val side: Float,
    val shadowX: Float, val shadowY: Float, val shadowWidth: Float, val shadowHeight: Float,
)
