package com.jarvis.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jarvis.android.engine.LiveModels
import com.jarvis.android.i18n.tr
import com.jarvis.android.i18n.trf
import com.jarvis.android.rest.ModelLadder

/**
 * Which Gemini models are answering now, and which are set aside and why (see ModelLadder and LiveModels), with a button that puts
 * them all back: after fixing a key, or once a quota was raised.
 */
@Composable
internal fun ModelStatus() {
    var tick by remember { mutableIntStateOf(0) }
    @Suppress("UNUSED_EXPRESSION") tick
    Column(Modifier.padding(top = 12.dp)) {
        Text(tr("Modèles en service"), style = MaterialTheme.typography.labelLarge)
        Text(
            trf("Texte : {0} · Voix : {1}", ModelLadder.shared.lastAnswered ?: tr("pas encore utilisé"),
                LiveModels.ladder.lastAnswered ?: tr("pas encore utilisé")),
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp),
        )
        val resting = ModelLadder.shared.resting() + LiveModels.ladder.resting()
        if (resting.isEmpty()) {
            Text(tr("Aucun modèle mis de côté."), style = MaterialTheme.typography.bodySmall)
        } else {
            for (r in resting) {
                val why = when (r.why) {
                    ModelLadder.Failure.QUOTA -> tr("quota atteint")
                    ModelLadder.Failure.UNAVAILABLE -> tr("ne répond pas")
                    ModelLadder.Failure.GONE -> tr("inaccessible avec cette clé")
                }
                Text(trf("{0} : mis de côté ({1}), encore {2} min", r.model, why, r.minutesLeft), style = MaterialTheme.typography.bodySmall)
            }
            OutlinedButton(onClick = { ModelLadder.shared.reset(); LiveModels.ladder.reset(); tick++ }, modifier = Modifier.padding(top = 4.dp)) {
                Text(tr("Tout remettre en service"))
            }
        }
        Text(
            tr("Quand un modèle est à court de quota, ne répond pas ou n’est pas accessible avec votre clé, Jarvis passe seul au suivant et le met de côté un moment."),
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp),
        )
    }
}
