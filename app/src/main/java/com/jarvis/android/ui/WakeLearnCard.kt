package com.jarvis.android.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.jarvis.android.JarvisApp
import com.jarvis.android.core.JarvisState
import com.jarvis.android.i18n.tr
import com.jarvis.android.i18n.trf
import com.jarvis.android.meetings.MeetingRecorderService
import com.jarvis.android.wake.LearnQuality
import com.jarvis.android.wake.LearnResult
import com.jarvis.android.wake.MIN_GOOD_REPETITIONS
import com.jarvis.android.wake.TEACH_BACKGROUND_STEPS
import com.jarvis.android.wake.TEACH_LEAD_STEPS
import com.jarvis.android.wake.TEACH_REPETITIONS
import com.jarvis.android.wake.TEACH_SPEAK_STEPS
import com.jarvis.android.wake.TEACH_WARMUP_STEPS
import com.jarvis.android.wake.TeachClip
import com.jarvis.android.wake.WAKE_MODEL_MARKER
import com.jarvis.android.wake.WakeDecision
import com.jarvis.android.wake.WakeTeacher
import com.jarvis.android.wake.WakeTeaching
import com.jarvis.android.wake.adjustLearnedThreshold
import com.jarvis.android.wake.findSpeech
import com.jarvis.android.wake.learnWord
import com.jarvis.android.wake.windowEndingAt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val TEST_STEPS = 125

/**
 * Teach Jarvis a new wake word: six repetitions of the phrase, twelve seconds of ordinary speech, then a live test before keeping it.
 * See WakeLearning.kt for how the word is recognised.
 */
@Composable
internal fun WakeLearnCard(onChanged: () -> Unit) {
    val context = LocalContext.current
    val container = remember { (context.applicationContext as JarvisApp).container }
    val manager = container.wakeModel
    val scope = rememberCoroutineScope()
    var label by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var speakNow by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var level by remember { mutableFloatStateOf(0f) }
    var pending by remember { mutableStateOf<LearnResult.Learned?>(null) }
    var testInfo by remember { mutableStateOf<String?>(null) }
    var words by remember { mutableStateOf(manager.learnedWords()) }
    var selected by remember { mutableStateOf(manager.selected()) }

    /** Why teaching cannot start now, or null. */
    fun blocker(): String? = when {
        !File(manager.dir, WAKE_MODEL_MARKER).exists() -> tr("Téléchargez d’abord les modèles openWakeWord (carte « Mot d’activation »).")
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED -> tr("Le micro n’est pas autorisé.")
        container.engine.state.value != JarvisState.ASLEEP -> tr("Fermez la session vocale avant d’apprendre un mot : le micro ne sert qu’à un usage à la fois.")
        MeetingRecorderService.recording -> tr("Un enregistrement de réunion est en cours.")
        else -> null
    }

    /** Runs [work] with the microphone to ourselves (the wake-word detector lets go of it first). */
    fun withTeacher(work: suspend (WakeTeacher) -> Unit) {
        scope.launch {
            busy = true
            status = null
            testInfo = null
            try {
                WakeTeaching.active.value = true
                delay(1_800)
                withContext(Dispatchers.IO) {
                    val teacher = WakeTeacher(context, manager.dir)
                    try {
                        val problem = teacher.open()
                        if (problem != null) status = problem else work(teacher)
                    } finally {
                        teacher.close()
                    }
                }
            } finally {
                WakeTeaching.active.value = false
                speakNow = false
                busy = false
                level = 0f
            }
        }
    }

    fun learn() {
        val problem = blocker()
        if (problem != null) { status = problem; return }
        val phrase = label.trim()
        if (phrase.isEmpty()) { status = tr("Écrivez d’abord le mot ou l’expression à apprendre."); return }
        pending = null
        progress = 0f
        withTeacher { teacher ->
            val failed = tr("Le micro a cessé de répondre.")
            status = tr("Ne dites rien pendant deux secondes…")
            if (!teacher.read(TEACH_WARMUP_STEPS)) { status = failed; return@withTeacher }
            val clips = mutableListOf<TeachClip>()
            var attempts = 0
            while (clips.size < TEACH_REPETITIONS && attempts < TEACH_REPETITIONS + 3) {
                attempts++
                status = trf("Répétition {0} sur {1} : restez silencieux…", clips.size + 1, TEACH_REPETITIONS)
                if (!teacher.read(TEACH_LEAD_STEPS) { level = (it.rms / 3000f).coerceIn(0f, 1f) }) { status = failed; return@withTeacher }
                val prompt = teacher.steps.size
                speakNow = true
                status = trf("Dites maintenant : « {0} »", phrase)
                if (!teacher.read(TEACH_SPEAK_STEPS) { level = (it.rms / 3000f).coerceIn(0f, 1f) }) { status = failed; return@withTeacher }
                speakNow = false
                val clip = teacher.clip(prompt)
                if (findSpeech(clip) == null) {
                    status = tr("Je n’ai pas bien entendu, on la refait.")
                    delay(1_200)
                } else {
                    clips += clip
                    progress = clips.size / (TEACH_REPETITIONS + 1f)
                }
            }
            if (clips.size < MIN_GOOD_REPETITIONS) {
                status = trf("Je n’ai bien entendu que {0} répétition(s) sur {1} : parlez plus fort et plus près du micro, dans le calme.", clips.size, attempts)
                return@withTeacher
            }
            status = tr("Maintenant, parlez normalement pendant douze secondes : racontez votre journée ou lisez un texte, sans dire le mot.")
            val backgroundStart = teacher.steps.size
            if (!teacher.read(TEACH_BACKGROUND_STEPS) { level = (it.rms / 3000f).coerceIn(0f, 1f) }) { status = failed; return@withTeacher }
            val background = teacher.steps.subList(backgroundStart, teacher.steps.size).toList()
            progress = 1f
            when (val result = learnWord(phrase, clips, background)) {
                is LearnResult.Failed -> status = result.reason
                is LearnResult.Learned -> {
                    pending = result
                    status = when (result.quality) {
                        LearnQuality.SOLID -> tr("Appris : la reconnaissance devrait être solide. Testez-la avant de l’enregistrer.")
                        LearnQuality.CORRECT -> tr("Appris : reconnaissance correcte. Testez-la avant de l’enregistrer.")
                        LearnQuality.WEAK -> tr("Appris, mais la reconnaissance sera fragile : essayez une expression plus longue, ou refaites l’apprentissage plus calmement. Testez-la avant de décider.")
                    }
                }
            }
        }
    }

    fun test() {
        val word = pending?.word ?: return
        val problem = blocker()
        if (problem != null) { status = problem; return }
        withTeacher { teacher ->
            status = tr("Test pendant dix secondes : dites le mot, puis quelques autres phrases.")
            val decision = WakeDecision()
            val limit = adjustLearnedThreshold(word.threshold, com.jarvis.android.wake.WAKE_THRESHOLD)
            var hits = 0
            var peak = 0f
            teacher.read(TEST_STEPS) { step ->
                level = (step.rms / 3000f).coerceIn(0f, 1f)
                val window = windowEndingAt(teacher.steps, teacher.steps.size - 1) ?: return@read
                val score = word.score(window)
                peak = maxOf(peak, score)
                if (decision.accept(score, limit)) hits++
                testInfo = trf("Score {0} · seuil {1} · reconnu {2} fois", "%.2f".format(score), "%.2f".format(limit), hits)
            }
            status = if (hits > 0) trf("Test terminé : reconnu {0} fois (score maximal {1}).", hits, "%.2f".format(peak))
            else trf("Test terminé : jamais reconnu (score maximal {0}, seuil {1}). Refaites l’apprentissage ou choisissez la sensibilité « Sensible ».", "%.2f".format(peak), "%.2f".format(limit))
        }
    }

    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) learn() else status = tr("Le micro n’est pas autorisé.")
    }

    SettingsCard(tr("Apprendre mon mot d’activation"), Icons.Filled.RecordVoiceOver, initiallyExpanded = false) {
        Text(
            tr("Apprenez à Jarvis un mot ou une expression de votre choix (par exemple « Debout Jarvis ») : dites-le six fois, puis parlez normalement douze secondes pour qu’il apprenne à ne pas confondre. Tout se fait sur le téléphone, avec les modèles openWakeWord, et rien n’est envoyé. Le mot reconnu est celui de votre voix : choisissez une expression d’au moins trois syllabes, dans un endroit calme."),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp),
        )
        OutlinedTextField(
            value = label,
            onValueChange = { label = it.take(40) },
            enabled = !busy,
            singleLine = true,
            label = { Text(tr("Mot ou expression")) },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            OutlinedButton(enabled = !busy, onClick = {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) learn() else askMic.launch(Manifest.permission.RECORD_AUDIO)
            }) { Text(tr("Apprendre ce mot")) }
            if (pending != null) {
                OutlinedButton(enabled = !busy, onClick = { test() }) { Text(tr("Tester")) }
                OutlinedButton(enabled = !busy, onClick = {
                    pending?.let { manager.saveLearned(it.word) }
                    pending = null
                    words = manager.learnedWords()
                    selected = manager.selected()
                    status = tr("Enregistré et choisi comme mot d’activation. Activez l’écoute du mot d’activation dans la carte du dessus si ce n’est pas fait.")
                    onChanged()
                }) { Text(tr("Enregistrer")) }
            }
        }
        if (busy) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp).height(28.dp).clip(RoundedCornerShape(8.dp))
                    .background(if (speakNow) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant),
            )
            LinearProgressIndicator(progress = { level }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
            if (progress > 0f) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
        }
        status?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp)) }
        testInfo?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)) }
        if (words.isNotEmpty()) {
            Text(tr("Mes mots appris"), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp))
            for ((file, name) in words) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                    FilterChip(selected = selected == file, enabled = !busy, label = { Text("« $name »") }, onClick = {
                        manager.select(file)
                        selected = manager.selected()
                        onChanged()
                    })
                    OutlinedButton(enabled = !busy, onClick = {
                        manager.deleteLearned(file)
                        words = manager.learnedWords()
                        selected = manager.selected()
                        onChanged()
                    }) { Text(tr("Supprimer")) }
                }
            }
        }
    }
}
