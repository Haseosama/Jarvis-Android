package com.jarvis.android.device

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.jarvis.android.rest.scaledSize
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/** What one read of the screen produced, kept so the next action can refer to elements by number. */
internal class ScreenSnapshot(
    val packageName: String,
    val elements: List<ScreenElement>,
    val nodes: List<AccessibilityNodeInfo>,
)

/** Outcome of an action, in words the assistant can pass on. */
internal sealed interface ActionResult {
    data object Done : ActionResult
    data class Failed(val reason: String) : ActionResult
}

/**
 * The hands and eyes of the assistant on the whole phone. Android only lets an accessibility
 * service read other apps' screens and tap or type in them, and only after the user has switched
 * it on in the system settings; it can be switched off there at any time.
 */
class JarvisAccessibilityService : AccessibilityService() {
    @Volatile private var last: ScreenSnapshot? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (instance === this) instance = null
        last = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    /** True when a password field is visible: what is shown may then reveal secrets, so nothing must be streamed. */
    internal fun hasVisiblePasswordField(): Boolean {
        val root = rootInActiveWindow ?: return false
        fun walk(node: AccessibilityNodeInfo, depth: Int): Boolean {
            if (depth > MAX_DEPTH) return false
            if (node.isVisibleToUser && node.isPassword) return true
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                if (walk(child, depth + 1)) return true
            }
            return false
        }
        return walk(root, 0)
    }

    internal fun activePackage(): String? = rootInActiveWindow?.packageName?.toString()

    internal fun appLabel(packageName: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
    } catch (_: Exception) {
        packageName
    }

    /** Reads what is on screen now. Null when nothing can be read (locked screen, secure window). */
    internal fun readScreen(): ScreenSnapshot? {
        val root = rootInActiveWindow ?: return null
        val elements = mutableListOf<ScreenElement>()
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        collect(root, elements, nodes, depth = 0)
        val snapshot = ScreenSnapshot(root.packageName?.toString().orEmpty(), elements, nodes)
        last = snapshot
        return snapshot
    }

    private fun collect(
        node: AccessibilityNodeInfo,
        elements: MutableList<ScreenElement>,
        nodes: MutableList<AccessibilityNodeInfo>,
        depth: Int,
    ) {
        if (depth > MAX_DEPTH || elements.size >= MAX_SCREEN_ELEMENTS * 2) return
        if (node.isVisibleToUser) {
            val actionable = node.isClickable || node.isEditable || node.isScrollable || node.isCheckable
            var label = ownLabel(node)
            if (label.isEmpty() && node.isClickable) label = descendantLabel(node)
            if (label.isNotEmpty() || actionable) {
                val bounds = Rect().also { node.getBoundsInScreen(it) }
                elements += ScreenElement(
                    index = elements.size,
                    label = label,
                    role = roleOf(node),
                    clickable = node.isClickable,
                    editable = node.isEditable,
                    scrollable = node.isScrollable,
                    checked = if (node.isCheckable) node.isChecked else null,
                    password = node.isPassword,
                    left = bounds.left, top = bounds.top, right = bounds.right, bottom = bounds.bottom,
                )
                nodes += node
            }
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collect(it, elements, nodes, depth + 1) }
        }
    }

    private fun ownLabel(node: AccessibilityNodeInfo): String =
        (node.text?.toString() ?: node.contentDescription?.toString() ?: node.hintText?.toString())
            .orEmpty().replace('\n', ' ').trim()

    private fun descendantLabel(node: AccessibilityNodeInfo, depth: Int = 0): String {
        if (depth > 4) return ""
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val own = ownLabel(child)
            if (own.isNotEmpty()) return own
            descendantLabel(child, depth + 1).takeIf { it.isNotEmpty() }?.let { return it }
        }
        return ""
    }

    private fun roleOf(node: AccessibilityNodeInfo): String {
        val name = node.className?.toString().orEmpty().substringAfterLast('.')
        return when {
            node.isEditable -> "champ"
            name.contains("Button") -> "bouton"
            name.contains("Switch") || name.contains("CheckBox") || name.contains("Radio") -> "case"
            name.contains("Image") -> "image"
            name.contains("Tab") -> "onglet"
            node.isClickable -> "élément"
            else -> "texte"
        }
    }

    private fun nodeAt(index: Int): AccessibilityNodeInfo? {
        val snapshot = last ?: return null
        val node = snapshot.nodes.getOrNull(index) ?: return null
        return if (node.refresh()) node else null
    }

    internal fun elementAt(index: Int): ScreenElement? = last?.elements?.getOrNull(index)

    internal fun lastSnapshot(): ScreenSnapshot? = last

    private val STALE = ActionResult.Failed("L’écran a changé : relisez l’écran avant d’agir.")

    internal suspend fun tap(index: Int): ActionResult {
        val node = nodeAt(index) ?: return STALE
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) target = target.parent
        if (target != null && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return ActionResult.Done
        // Not clickable through the tree: tap its centre instead.
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        return if (dispatchTap(bounds.exactCenterX(), bounds.exactCenterY())) ActionResult.Done
        else ActionResult.Failed("Impossible d’appuyer sur cet élément.")
    }

    internal fun type(index: Int?, text: String): ActionResult {
        val node = if (index != null) nodeAt(index) ?: return STALE
        else findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return ActionResult.Failed("Aucun champ de saisie actif : indiquez le numéro du champ.")
        if (node.isPassword) return ActionResult.Failed("Champ de mot de passe : saisie refusée. L’utilisateur doit le remplir lui-même.")
        if (!node.isEditable) return ActionResult.Failed("Cet élément n’est pas un champ de saisie.")
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) ActionResult.Done
        else ActionResult.Failed("Le champ a refusé le texte.")
    }

    /**
     * Scrolls the content in [direction] ("down" shows what is below). The screen is read again first: by voice the model calls this without
     * having read the screen, and an old snapshot (or none) used to answer "nothing to scroll". Every scrollable area that supports the
     * movement is tried, the largest first (a carousel must not hide the page); when none takes it (web pages, games, custom views), the
     * same movement is made with a finger swipe.
     */
    internal suspend fun scroll(direction: String, index: Int?): ActionResult {
        val action = when (direction) {
            "up" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP
            "down" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN
            "left" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT
            else -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT
        }
        if (index != null) {
            var target: AccessibilityNodeInfo? = nodeAt(index) ?: return STALE
            while (target != null) {
                if (target.isScrollable && target.performAction(action.id)) return ActionResult.Done
                target = target.parent
            }
            return ActionResult.Failed("Défilement impossible dans cette direction.")
        }
        val candidates = readScreen()?.nodes.orEmpty()
            .filter { it.isScrollable && it.actionList.any { a -> a.id == action.id } }
            .sortedByDescending { node -> Rect().also { node.getBoundsInScreen(it) }.let { it.width().toLong() * it.height() } }
        for (node in candidates) if (node.performAction(action.id)) return ActionResult.Done
        return swipe(swipeForScroll(direction))
    }

    /** The finger moves in [direction]: "left" pulls the content to the left (next page or photo). */
    internal suspend fun swipe(direction: String): ActionResult {
        val metrics = resources.displayMetrics
        val w = metrics.widthPixels.toFloat()
        val h = metrics.heightPixels.toFloat()
        val path = Path()
        when (direction) {
            "left" -> { path.moveTo(w * 0.85f, h / 2); path.lineTo(w * 0.15f, h / 2) }
            "right" -> { path.moveTo(w * 0.15f, h / 2); path.lineTo(w * 0.85f, h / 2) }
            "up" -> { path.moveTo(w / 2, h * 0.75f); path.lineTo(w / 2, h * 0.25f) }
            else -> { path.moveTo(w / 2, h * 0.25f); path.lineTo(w / 2, h * 0.75f) }
        }
        return if (dispatch(path, 300)) ActionResult.Done else ActionResult.Failed("Geste refusé par le système.")
    }

    /** Taps at a screen position given as fractions of the screen size (0..1). */
    internal suspend fun tapAtFraction(x: Float, y: Float): ActionResult {
        val metrics = resources.displayMetrics
        return if (dispatchTap(metrics.widthPixels * x, metrics.heightPixels * y)) ActionResult.Done
        else ActionResult.Failed("Geste refusé par le système.")
    }

    /** The current screen as a JPEG scaled to [com.jarvis.android.rest.MAX_IMAGE_SIDE], or the reason it could not be captured. */
    internal suspend fun screenshotJpeg(maxSide: Int = com.jarvis.android.rest.MAX_IMAGE_SIDE): Pair<ByteArray?, String?> {
        if (android.os.Build.VERSION.SDK_INT < 30) return null to "La capture d’écran demande Android 11 ou plus."
        val done = CompletableDeferred<Pair<ByteArray?, String?>>()
        takeScreenshot(
            android.view.Display.DEFAULT_DISPLAY,
            java.util.concurrent.Executors.newSingleThreadExecutor(),
            object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    try {
                        val hardware = android.graphics.Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                        val soft = hardware?.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                        result.hardwareBuffer.close()
                        if (soft == null) {
                            done.complete(null to "Capture d’écran illisible.")
                            return
                        }
                        val (w, h) = scaledSize(soft.width, soft.height, maxSide)
                        val scaled = if (w == soft.width && h == soft.height) soft
                        else android.graphics.Bitmap.createScaledBitmap(soft, w, h, true)
                        val out = java.io.ByteArrayOutputStream()
                        scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, out)
                        done.complete(out.toByteArray() to null)
                    } catch (e: Exception) {
                        done.complete(null to "Capture d’écran impossible.")
                    }
                }

                override fun onFailure(errorCode: Int) {
                    done.complete(
                        null to when (errorCode) {
                            ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> "Cette fenêtre est protégée : la capture d’écran est interdite par l’application."
                            ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "Captures trop rapprochées : réessayez dans une seconde."
                            else -> "Capture d’écran refusée par le système (code $errorCode)."
                        }
                    )
                }
            },
        )
        return withTimeoutOrNull(5_000) { done.await() } ?: (null to "Capture d’écran trop lente.")
    }

    internal fun global(action: String): ActionResult {
        val code = when (action) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS
            "lock_screen" -> GLOBAL_ACTION_LOCK_SCREEN
            "take_screenshot" -> GLOBAL_ACTION_TAKE_SCREENSHOT
            else -> return ActionResult.Failed("Action inconnue.")
        }
        return if (performGlobalAction(code)) ActionResult.Done else ActionResult.Failed("Action refusée par le système.")
    }

    private suspend fun dispatchTap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y); lineTo(x, y) }
        return dispatch(path, 60)
    }

    /** Runs a gesture and waits briefly to learn whether the system completed it. */
    private suspend fun dispatch(path: Path, durationMs: Long): Boolean {
        val done = CompletableDeferred<Boolean>()
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                done.complete(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                done.complete(false)
            }
        }, null)
        if (!accepted) return false
        return withTimeoutOrNull(durationMs + 1_500) { done.await() } == true
    }

    companion object {
        private const val MAX_DEPTH = 40

        /** The connected service, or null when the user has not enabled it. */
        @Volatile
        internal var instance: JarvisAccessibilityService? = null
    }
}
