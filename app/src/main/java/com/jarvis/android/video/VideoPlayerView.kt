package com.jarvis.android.video

import android.annotation.SuppressLint
import android.media.MediaPlayer
import android.net.Uri
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.jarvis.android.i18n.tr

/**
 * The video shown in place of the avatar: a header (the assistant's small face watching it, the title, pause, the sound, close) over
 * the picture, 16:9. [face] draws the small face, when there is one.
 */
@Composable
internal fun VideoPlayerView(panel: VideoPanel, video: VideoPanel.Video, modifier: Modifier = Modifier, face: (@Composable () -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            if (face != null) Box(Modifier.size(56.dp)) { face() }
            Text(
                video.title, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
            IconButton(onClick = { panel.command(if (video.paused) VideoPanel.Command.Resume else VideoPanel.Command.Pause) }) {
                Icon(if (video.paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                    contentDescription = if (video.paused) tr("Reprendre la vidéo") else tr("Mettre la vidéo en pause"))
            }
            IconButton(onClick = { panel.setSound(!video.sound) }) {
                Icon(
                    if (video.sound) Icons.Filled.VolumeUp else Icons.Filled.VolumeOff,
                    contentDescription = if (video.sound) tr("Couper le son de la vidéo") else tr("Mettre le son de la vidéo"),
                )
            }
            IconButton(onClick = { panel.close() }) { Icon(Icons.Filled.Close, contentDescription = tr("Fermer la vidéo")) }
        }
        // No clip and no background here: both players show their picture on a surface of its own behind the window, seen through a
        // hole the view punches in it. A rounded clip draws the box into a layer of its own (the hole is punched in that layer, not in
        // the window) and a background paints over the hole: either way the sound plays and the picture stays black.
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
            when {
                video.youtubeId != null -> YoutubePlayer(panel, video.youtubeId, video.sound)
                video.url != null -> FilePlayer(panel, video.url, video.sound) { panel.close() }
            }
        }
    }
}

/**
 * YouTube's own embedded player in a web view (the privacy-enhanced address), started muted; the sound, pause and seeking go through
 * the player's JavaScript interface. The page is given a web origin so YouTube accepts to be embedded.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun YoutubePlayer(panel: VideoPanel, id: String, sound: Boolean) {
    val web = remember(id) { arrayOfNulls<WebView>(1) }
    AndroidView(
        factory = { ctx ->
            // a debug build can be inspected from a computer (chrome://inspect)
            if (ctx.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) WebView.setWebContentsDebuggingEnabled(true)
            WebView(ctx).apply {
                // a view put in Compose is sized "wrap content" by default, and a web view that wraps its content lays the page out
                // 0 pixels high: the player's frame (height 100%) had no height at all, and only its sound was left
                layoutParams = android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
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
                loadDataWithBaseURL("https://jarvis.android.local/", youtubePage(id), "text/html", "utf-8", null)
                web[0] = this
            }
        },
        modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
    )
    LaunchedEffect(sound) {
        // the player may still be loading: the command is sent a few times
        repeat(6) {
            web[0]?.evaluateJavascript(if (sound) "setSound(true)" else "setSound(false)", null)
            kotlinx.coroutines.delay(500)
        }
    }
    LaunchedEffect(panel, id) {
        panel.commands.collect { c ->
            web[0]?.evaluateJavascript(
                when (c) {
                    VideoPanel.Command.Pause -> "cmd('pauseVideo')"
                    VideoPanel.Command.Resume -> "cmd('playVideo')"
                    VideoPanel.Command.Restart -> "cmd('seekTo',[0,true]);cmd('playVideo')"
                    is VideoPanel.Command.SeekBy -> "seekBy(${c.seconds})"
                }, null,
            )
        }
    }
    DisposableEffect(id) { onDispose { web[0]?.apply { loadUrl("about:blank"); destroy() }; web[0] = null } }
}

internal fun youtubePage(id: String): String = """
<!DOCTYPE html><html><head><meta name="viewport" content="width=device-width, initial-scale=1">
<style>html,body{margin:0;height:100%;background:#000}iframe{position:absolute;inset:0;width:100%;height:100%;border:0}</style></head>
<body><iframe id="p" referrerpolicy="strict-origin-when-cross-origin" allow="autoplay; encrypted-media; picture-in-picture" allowfullscreen
 src="https://www.youtube-nocookie.com/embed/$id?autoplay=1&mute=1&playsinline=1&rel=0&modestbranding=1&enablejsapi=1&origin=https%3A%2F%2Fjarvis.android.local"></iframe>
<script>
var cur=0;
function cmd(f,a){document.getElementById('p').contentWindow.postMessage(JSON.stringify({event:'command',func:f,args:a||[]}),'*');}
function setSound(on){ if(on){cmd('unMute');cmd('setVolume',[100]);} else {cmd('mute');} }
function seekBy(s){ cmd('seekTo',[Math.max(0,cur+s),true]); }
// the player reports where it is once asked to talk to this page
window.addEventListener('message',function(e){ try{ var m=JSON.parse(e.data); if(m.info&&typeof m.info.currentTime==='number') cur=m.info.currentTime; }catch(x){} });
setInterval(function(){ document.getElementById('p').contentWindow.postMessage(JSON.stringify({event:'listening',id:1}),'*'); },1000);
</script></body></html>
""".trimIndent()

/** A video file (on the web or on the phone), in the phone's own player; closed when it ends. */
@Composable
private fun FilePlayer(panel: VideoPanel, url: String, sound: Boolean, onEnd: () -> Unit) {
    val player = remember(url) { arrayOfNulls<MediaPlayer>(1) }
    val view = remember(url) { arrayOfNulls<VideoView>(1) }
    AndroidView(
        factory = { ctx ->
            VideoView(ctx).apply {
                setOnPreparedListener { mp ->
                    player[0] = mp
                    val v = if (sound) 1f else 0f
                    mp.setVolume(v, v)
                    start()
                }
                setOnCompletionListener { onEnd() }
                setOnErrorListener { _, _, _ -> onEnd(); true }
                setVideoURI(Uri.parse(url))
                view[0] = this
            }
        },
        modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
    )
    LaunchedEffect(sound) {
        val v = if (sound) 1f else 0f
        try { player[0]?.setVolume(v, v) } catch (_: IllegalStateException) {}
    }
    LaunchedEffect(panel, url) {
        panel.commands.collect { c ->
            val vv = view[0] ?: return@collect
            try {
                when (c) {
                    VideoPanel.Command.Pause -> vv.pause()
                    VideoPanel.Command.Resume -> vv.start()
                    VideoPanel.Command.Restart -> { vv.seekTo(0); vv.start() }
                    is VideoPanel.Command.SeekBy -> vv.seekTo((vv.currentPosition + c.seconds * 1000).coerceIn(0, maxOf(vv.duration, 0)))
                }
            } catch (_: IllegalStateException) {}
        }
    }
    DisposableEffect(url) { onDispose { view[0]?.stopPlayback(); view[0] = null; player[0] = null } }
}
