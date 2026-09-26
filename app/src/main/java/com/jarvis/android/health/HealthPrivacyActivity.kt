package com.jarvis.android.health

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jarvis.android.i18n.tr
import com.jarvis.android.ui.theme.JarvisTheme

/** What Jarvis does with health data: Health Connect shows this page from its permission screen, as Android requires. */
class HealthPrivacyActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            JarvisTheme(hue = 190f) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(modifier = Modifier.padding(24.dp).verticalScroll(rememberScrollState())) {
                        Text(tr("Jarvis et vos données de santé"), style = MaterialTheme.typography.titleLarge)
                        Text(
                            tr("Jarvis lit dans Health Connect vos pas, la distance parcourue, votre sommeil et votre rythme cardiaque, seulement pour vous répondre quand vous le demandez (« combien de pas aujourd’hui ? », « comment j’ai dormi ? ») et pour le briefing du matin si vous l’avez activé. Il n’écrit rien dans Health Connect et ne garde aucune de ces données. Les chiffres demandés sont transmis au modèle de langue (Gemini) pour formuler la réponse, comme le reste de la conversation. Vous pouvez retirer l’accès à tout moment dans Health Connect."),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                        Button(onClick = { finish() }, modifier = Modifier.padding(top = 20.dp)) { Text(tr("Fermer")) }
                    }
                }
            }
        }
    }
}
