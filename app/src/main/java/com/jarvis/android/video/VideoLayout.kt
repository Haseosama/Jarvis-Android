package com.jarvis.android.video

/** The shape of the player's box under its header: 16:9 for a picture, taller for the live sky (a chart and its list). */
internal fun panelRatio(v: VideoPanel.Video): Float = when {
    v.sky != null && com.jarvis.android.video.SkyModes.isAr(v.sky) -> 0.75f
    v.sky != null && com.jarvis.android.video.SkyModes.onMap(v.sky) -> 1f
    v.sky != null -> 0.8f
    else -> 16f / 9f
}

/** The sound asked for, 0 to 100: none, turned down while the user talks over it, or full. */
internal fun volumeOf(v: VideoPanel.Video): Int = when {
    !v.sound -> 0
    v.ducked -> DUCKED_VOLUME
    else -> 100
}

private const val DUCKED_VOLUME = 12

/** YouTube's page for video [id], started at [start] seconds, with subtitles in [subtitles] (a language code) when set. */
internal fun youtubePage(id: String, start: Int = 0, subtitles: String? = null): String {
    val lang = subtitles?.filter { it.isLetter() || it == '-' }?.take(8)
    val extra = (if (start > 0) "&start=$start" else "") + (if (!lang.isNullOrEmpty()) "&cc_load_policy=1&cc_lang_pref=$lang&hl=$lang" else "")
    return youtubePageFor(id, extra)
}

private fun youtubePageFor(id: String, extra: String): String = """
<!DOCTYPE html><html><head><meta name="viewport" content="width=device-width, initial-scale=1">
<style>html,body{margin:0;height:100%;background:#000}iframe{position:absolute;inset:0;width:100%;height:100%;border:0}</style></head>
<body><iframe id="p" referrerpolicy="strict-origin-when-cross-origin" allow="autoplay; encrypted-media; picture-in-picture" allowfullscreen
 src="https://www.youtube-nocookie.com/embed/$id?autoplay=1&mute=1&playsinline=1&rel=0&modestbranding=1&enablejsapi=1&origin=https%3A%2F%2Fjarvis.android.local$extra"></iframe>
<script>
var cur=0, dur=0, st=-1;
function cmd(f,a){document.getElementById('p').contentWindow.postMessage(JSON.stringify({event:'command',func:f,args:a||[]}),'*');}
function setVol(v){ if(v<=0){cmd('mute');} else {cmd('unMute');cmd('setVolume',[v]);} }
function seekBy(s){ cmd('seekTo',[Math.max(0,cur+s),true]); }
function subs(l){ if(l){ cmd('loadModule',['captions']); cmd('setOption',['captions','track',{languageCode:l}]); } else { cmd('unloadModule',['captions']); } }
// the player reports where it is once asked to talk to this page
window.addEventListener('message',function(e){ try{ var m=JSON.parse(e.data); if(!m.info) return; if(typeof m.info.currentTime==='number') cur=m.info.currentTime;
 if(typeof m.info.duration==='number') dur=m.info.duration; if(typeof m.info.playerState==='number') st=m.info.playerState; }catch(x){} });
setInterval(function(){ document.getElementById('p').contentWindow.postMessage(JSON.stringify({event:'listening',id:1}),'*'); },1000);
// YouTube stops a player smaller than 200 by 200 (the app's small video window): it is laid out larger, then shrunk to fit
function fit(){ var k=Math.max(1,220/Math.max(1,Math.min(innerWidth,innerHeight))); var f=document.getElementById('p');
 f.style.width=(100*k)+'%'; f.style.height=(100*k)+'%'; f.style.transform=k>1?'scale('+(1/k)+')':''; f.style.transformOrigin='0 0'; }
window.addEventListener('resize',fit); fit();
</script></body></html>
""".trimIndent()

/** How far a photo can be zoomed. */
internal const val MAX_PHOTO_ZOOM = 6f

/**
 * The pan that keeps the point [focus] of the box (of [box] size) where it is when the zoom goes from [s1] (with pan [t]) to [s2]:
 * the picture is scaled about the box's middle, then moved by the pan.
 */
internal fun zoomAround(focus: androidx.compose.ui.geometry.Offset, box: androidx.compose.ui.geometry.Size, t: androidx.compose.ui.geometry.Offset, s1: Float, s2: Float): androidx.compose.ui.geometry.Offset {
    val c = androidx.compose.ui.geometry.Offset(box.width / 2f, box.height / 2f)
    return (focus - c) - (focus - c - t) * (s2 / s1)
}

/** A pan that never shows past the photo's edges at zoom [s]. */
internal fun clampPan(t: androidx.compose.ui.geometry.Offset, s: Float, box: androidx.compose.ui.geometry.Size): androidx.compose.ui.geometry.Offset {
    val mx = (s - 1f) * box.width / 2f
    val my = (s - 1f) * box.height / 2f
    // + 0f: no negative zero (-0.0 is not 0.0 to an Offset)
    return androidx.compose.ui.geometry.Offset(t.x.coerceIn(-mx, mx) + 0f, t.y.coerceIn(-my, my) + 0f)
}
