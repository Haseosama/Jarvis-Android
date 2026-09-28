package com.jarvis.android.video

import android.annotation.SuppressLint
import android.media.MediaPlayer
import android.view.TextureView
import android.view.Surface
import android.graphics.SurfaceTexture
import android.graphics.Matrix
import android.net.Uri
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.jarvis.android.i18n.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** The height of the header over the picture (the small face, the title, the buttons). */
internal val VIDEO_HEADER = 64.dp

/**
 * The video shown in place of the avatar: a header (the assistant's small face watching it, the title, the buttons) over the
 * picture, 16:9. [big]: the picture alone over the whole screen (full screen, or the phone turned), with its buttons over it, none
 * in the picture-in-picture window ([pip]). [face] draws the small face, when there is one; a tap on it, while the sound is on,
 * lets the user talk over the video.
 */
@Composable
internal fun VideoPlayerView(
    panel: VideoPanel, video: VideoPanel.Video, modifier: Modifier = Modifier, big: Boolean = false, pip: Boolean = false,
    face: (@Composable () -> Unit)? = null,
) {
    // the floor closes by itself when its time is up, even with no session listening (the microphone's loop also checks it)
    if (video.ducked) LaunchedEffect(Unit) { while (true) { delay(500); panel.micOpen() } }
    Column(if (big) modifier.fillMaxSize() else modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        if (!big) Header(panel, video, face)
        // No clip and no background here: the web view may show YouTube's picture on a surface of its own behind the window, seen
        // through a hole it punches in it. A rounded clip draws the box into a layer of its own (the hole is punched in that layer, not
        // in the window) and a background paints over the hole: either way the sound plays and the picture stays black.
        Box(if (big) Modifier.fillMaxSize() else Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
            when {
                video.isSlideshow -> key(video.photos) { Slideshow(panel, video) }
                // one player for all the videos of a search, the next one loaded into it: a player made anew while the app is its small
                // video window got no picture until the app came back
                video.youtubeId != null -> YoutubePlayer(panel, video.youtubeId, volumeOf(video))
                video.url != null -> FilePlayer(panel, video.url, volumeOf(video)) { if (!panel.step(1)) panel.close() }
            }
            if (big && !pip) {
                CompositionLocalProvider(LocalContentColor provides Color.White) {
                    Row(
                        Modifier.align(Alignment.TopEnd).padding(12.dp).background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(24.dp)),
                        verticalAlignment = Alignment.CenterVertically,
                    ) { Controls(panel, video, previous = true) }
                }
            }
        }
    }
}

/** The sound asked for, 0 to 100: none, turned down while the user talks over it, or full. */
internal fun volumeOf(v: VideoPanel.Video): Int = when {
    !v.sound -> 0
    v.ducked -> DUCKED_VOLUME
    else -> 100
}

private const val DUCKED_VOLUME = 12

@Composable
private fun Header(panel: VideoPanel, video: VideoPanel.Video, face: (@Composable () -> Unit)?) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(VIDEO_HEADER)) {
        if (face != null) {
            Box(Modifier.size(VIDEO_HEADER).clickable(enabled = video.sound, onClickLabel = tr("Parler par-dessus la vidéo")) { panel.openFloor() }) { face() }
        }
        Column(Modifier.weight(1f).padding(start = 4.dp)) {
            Text(video.title, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val more = listOfNotNull(
                if (video.count > 1) "${video.position}/${video.count}" else null,
                if (video.ducked) tr("je vous écoute…") else null,
            )
            if (more.isNotEmpty()) {
                Text(more.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
        Controls(panel, video, previous = false)
    }
}

/** The buttons: previous (when asked and there is one), next, pause, the sound, full screen, close. */
@Composable
private fun RowScope.Controls(panel: VideoPanel, video: VideoPanel.Video, previous: Boolean) {
    val several = video.count > 1 || video.isSlideshow
    val small = Modifier.size(40.dp)
    if (previous && several) {
        IconButton(onClick = { panel.step(-1) }, modifier = small) { Icon(Icons.Filled.SkipPrevious, contentDescription = tr("Vidéo précédente")) }
    }
    IconButton(onClick = { panel.command(if (video.paused) VideoPanel.Command.Resume else VideoPanel.Command.Pause) }, modifier = small) {
        Icon(if (video.paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
            contentDescription = if (video.paused) tr("Reprendre la vidéo") else tr("Mettre la vidéo en pause"))
    }
    if (several) {
        IconButton(onClick = { panel.step(1) }, modifier = small) { Icon(Icons.Filled.SkipNext, contentDescription = tr("Vidéo suivante")) }
    }
    if (!video.isSlideshow) {
        IconButton(onClick = { panel.setSound(!video.sound) }, modifier = small) {
            Icon(
                if (video.sound) Icons.Filled.VolumeUp else Icons.Filled.VolumeOff,
                contentDescription = if (video.sound) tr("Couper le son de la vidéo") else tr("Mettre le son de la vidéo"),
            )
        }
    }
    IconButton(onClick = { panel.setFullscreen(!video.fullscreen) }, modifier = small) {
        Icon(if (video.fullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
            contentDescription = if (video.fullscreen) tr("Quitter le plein écran") else tr("Plein écran"))
    }
    IconButton(onClick = { panel.close() }, modifier = small) { Icon(Icons.Filled.Close, contentDescription = tr("Fermer la vidéo")) }
}

/**
 * YouTube's own embedded player in a web view (the privacy-enhanced address), started muted; the sound, pause and seeking go through
 * the player's JavaScript interface. The page is given a web origin so YouTube accepts to be embedded.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun YoutubePlayer(panel: VideoPanel, id: String, volume: Int) {
    val web = remember { arrayOfNulls<WebView>(1) }
    AndroidView(
        factory = { ctx ->
            // a debug build can be inspected from a computer (chrome://inspect)
            if (ctx.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) WebView.setWebContentsDebuggingEnabled(true)
            WebView(ctx).apply {
                // a view put in Compose is sized "wrap content" by default, and a web view that wraps its content lays the page out
                // 0 pixels high: the player's frame (height 100%) had no height at all, and only its sound was left
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                webViewClient = WebViewClient()
                webChromeClient = WebChromeClient()
                // the app's theme is dark, and a web view may then darken the pages it shows by itself: a video player is left as it is
                if (android.os.Build.VERSION.SDK_INT >= 33) settings.isAlgorithmicDarkeningAllowed = false
                @Suppress("DEPRECATION")
                if (android.os.Build.VERSION.SDK_INT in 29..32) settings.forceDark = android.webkit.WebSettings.FORCE_DARK_OFF
                setBackgroundColor(android.graphics.Color.BLACK)
                web[0] = this
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
    LaunchedEffect(id) { web[0]?.loadDataWithBaseURL("https://jarvis.android.local/", youtubePage(id), "text/html", "utf-8", null) }
    LaunchedEffect(volume, id) {
        // the player may still be loading: the command is sent a few times
        repeat(6) {
            web[0]?.evaluateJavascript("setVol($volume)", null)
            delay(500)
        }
    }
    LaunchedEffect(panel) {
        panel.commands.collect { c ->
            val js = when (c) {
                VideoPanel.Command.Pause -> "cmd('pauseVideo')"
                VideoPanel.Command.Resume -> "cmd('playVideo')"
                VideoPanel.Command.Restart -> "cmd('seekTo',[0,true]);cmd('playVideo')"
                is VideoPanel.Command.SeekBy -> "seekBy(${c.seconds})"
                is VideoPanel.Command.Step -> null
            }
            if (js != null) web[0]?.evaluateJavascript(js, null)
        }
    }
    DisposableEffect(Unit) { onDispose { web[0]?.apply { loadUrl("about:blank"); destroy() }; web[0] = null } }
}

internal fun youtubePage(id: String): String = """
<!DOCTYPE html><html><head><meta name="viewport" content="width=device-width, initial-scale=1">
<style>html,body{margin:0;height:100%;background:#000}iframe{position:absolute;inset:0;width:100%;height:100%;border:0}</style></head>
<body><iframe id="p" referrerpolicy="strict-origin-when-cross-origin" allow="autoplay; encrypted-media; picture-in-picture" allowfullscreen
 src="https://www.youtube-nocookie.com/embed/$id?autoplay=1&mute=1&playsinline=1&rel=0&modestbranding=1&enablejsapi=1&origin=https%3A%2F%2Fjarvis.android.local"></iframe>
<script>
var cur=0;
function cmd(f,a){document.getElementById('p').contentWindow.postMessage(JSON.stringify({event:'command',func:f,args:a||[]}),'*');}
function setVol(v){ if(v<=0){cmd('mute');} else {cmd('unMute');cmd('setVolume',[v]);} }
function seekBy(s){ cmd('seekTo',[Math.max(0,cur+s),true]); }
// the player reports where it is once asked to talk to this page
window.addEventListener('message',function(e){ try{ var m=JSON.parse(e.data); if(m.info&&typeof m.info.currentTime==='number') cur=m.info.currentTime; }catch(x){} });
setInterval(function(){ document.getElementById('p').contentWindow.postMessage(JSON.stringify({event:'listening',id:1}),'*'); },1000);
// YouTube stops a player smaller than 200 by 200 (the app's small video window): it is laid out larger, then shrunk to fit
function fit(){ var k=Math.max(1,220/Math.max(1,Math.min(innerWidth,innerHeight))); var f=document.getElementById('p');
 f.style.width=(100*k)+'%'; f.style.height=(100*k)+'%'; f.style.transform=k>1?'scale('+(1/k)+')':''; f.style.transformOrigin='0 0'; }
window.addEventListener('resize',fit); fit();
</script></body></html>
""".trimIndent()

/**
 * A video file (on the web or on the phone), played by the phone's media player into a texture view, centred at its own
 * proportions; [onEnd] when it ends. A texture view draws the picture inside the app's own views, where a surface view (the
 * phone's usual video view) shows it through a hole in the window: that hole let nothing paint over it, the picture froze on the
 * emulator while the sound went on, and the player started again from the beginning whenever its surface was made anew (the app
 * shrunk to its video window). Here the player lives as long as the view, whatever happens to its surface.
 */
@Composable
private fun FilePlayer(panel: VideoPanel, url: String, volume: Int, onEnd: () -> Unit) {
    val context = LocalContext.current
    val ended by androidx.compose.runtime.rememberUpdatedState(onEnd)
    val mp = remember { MediaPlayer() }
    val prepared = remember { booleanArrayOf(false) }
    val texture = remember { arrayOfNulls<TextureView>(1) }

    /** The picture fitted in the view at the video's proportions, centred (a texture view stretches it otherwise). */
    fun fit() {
        val tv = texture[0] ?: return
        val vw = mp.videoWidth.toFloat()
        val vh = mp.videoHeight.toFloat()
        val w = tv.width.toFloat()
        val h = tv.height.toFloat()
        if (vw <= 0f || vh <= 0f || w <= 0f || h <= 0f) return
        val s = minOf(w / vw, h / vh)
        tv.setTransform(Matrix().apply {
            setScale(vw * s / w, vh * s / h)
            postTranslate((w - vw * s) / 2f, (h - vh * s) / 2f)
        })
    }

    AndroidView(
        factory = { ctx ->
            TextureView(ctx).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                        mp.setSurface(Surface(st))
                        fit()
                    }
                    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = fit()
                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                        try { mp.setSurface(null) } catch (_: IllegalStateException) {}
                        return true
                    }
                    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                }
                addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fit() }
                texture[0] = this
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
    LaunchedEffect(url) {
        prepared[0] = false
        try {
            mp.reset()
            texture[0]?.surfaceTexture?.let { mp.setSurface(Surface(it)) }
            mp.setOnPreparedListener {
                prepared[0] = true
                val v = panel.video.value?.let { volumeOf(it) / 100f } ?: 0f
                it.setVolume(v, v)
                fit()
                if (panel.video.value?.paused != true) it.start()
            }
            mp.setOnVideoSizeChangedListener { _, _, _ -> fit() }
            mp.setOnCompletionListener { ended() }
            mp.setOnErrorListener { _, _, _ -> ended(); true }
            mp.setDataSource(context, Uri.parse(url))
            mp.prepareAsync()
        } catch (_: Exception) {
            ended()
        }
    }
    LaunchedEffect(volume) {
        val v = volume / 100f
        try { if (prepared[0]) mp.setVolume(v, v) } catch (_: IllegalStateException) {}
    }
    LaunchedEffect(panel) {
        panel.commands.collect { c ->
            if (!prepared[0]) return@collect
            try {
                when (c) {
                    VideoPanel.Command.Pause -> mp.pause()
                    VideoPanel.Command.Resume -> mp.start()
                    VideoPanel.Command.Restart -> { mp.seekTo(0); mp.start() }
                    is VideoPanel.Command.SeekBy ->
                        mp.seekTo((mp.currentPosition + c.seconds * 1000L).coerceIn(0L, maxOf(mp.duration, 0).toLong()), MediaPlayer.SEEK_CLOSEST)
                    is VideoPanel.Command.Step -> {}
                }
            } catch (_: IllegalStateException) {}
        }
    }
    DisposableEffect(Unit) { onDispose { prepared[0] = false; mp.release(); texture[0] = null } }
}

/** How long each photo of a slideshow stays. */
private const val SLIDE_MS = 5_000

/** Photos of the phone one after the other, a slow zoom on each, a cross-fade between them; round again after the last. */
@Composable
private fun Slideshow(panel: VideoPanel, video: VideoPanel.Video) {
    val photos = video.photos
    var index by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    // the photo shown and the next one, read ahead so that it is ready when its turn comes
    val ready = remember { HashMap<Int, ImageBitmap>() }
    suspend fun read(i: Int): ImageBitmap? = ready[i] ?: withContext(Dispatchers.IO) {
        com.jarvis.android.photos.loadPhoto(context, Uri.parse(photos[i]), 1600)?.asImageBitmap()
    }?.also { ready[i] = it }
    val shown by produceState<Pair<Int, ImageBitmap>?>(null, index) {
        val i = index
        val b = read(i)
        // a photo that cannot be read is skipped
        if (b == null) { index = (i + 1) % photos.size; return@produceState }
        value = i to b
        val next = (i + 1) % photos.size
        ready.keys.retainAll(setOf(i, next))
        read(next)
    }
    LaunchedEffect(index, video.paused) {
        if (video.paused) return@LaunchedEffect
        delay(SLIDE_MS.toLong())
        index = (index + 1) % photos.size
    }
    LaunchedEffect(panel) {
        panel.commands.collect { c ->
            when (c) {
                is VideoPanel.Command.Step -> index = Math.floorMod(index + c.by, photos.size)
                is VideoPanel.Command.SeekBy -> index = Math.floorMod(index + (if (c.seconds < 0) -1 else 1), photos.size)
                VideoPanel.Command.Restart -> index = 0
                else -> {}
            }
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Crossfade(shown, animationSpec = tween(700), label = "photo") { s ->
            if (s != null) {
                val zoom = remember { Animatable(1f) }
                LaunchedEffect(Unit) { zoom.animateTo(1.07f, tween(SLIDE_MS + 700, easing = LinearEasing)) }
                Image(
                    s.second, contentDescription = null, contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().clipToBounds().graphicsLayer { scaleX = zoom.value; scaleY = zoom.value },
                )
            }
        }
        Text(
            "${(shown?.first ?: index) + 1}/${photos.size}", style = MaterialTheme.typography.labelSmall, color = Color.White,
            modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp).background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}
