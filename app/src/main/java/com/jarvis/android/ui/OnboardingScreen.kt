package com.jarvis.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun OnboardingScreen(onSave: (String) -> Unit) {
    var key by remember { mutableStateOf("") }
    val validKey = validatedApiKey(key)

    Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(Modifier.verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("JARVIS", style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                tr("Collez votre clé API Gemini pour commencer. Elle est conservée chiffrée sur cet appareil et transmise uniquement à l’API Gemini de Google pour l’authentification."),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            OutlinedTextField(
                value = key,
                onValueChange = { key = it },
                label = { Text(tr("Clé API Gemini")) },
                singleLine = true,
                isError = key.isNotEmpty() && validKey == null,
                supportingText = {
                    if (key.isNotEmpty() && validKey == null) Text(tr("Saisissez une clé sans espaces ni caractères de contrôle."))
                },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { validKey?.let(onSave) },
                enabled = validKey != null,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(tr("Continuer")) }
            Spacer(Modifier.height(12.dp))
            Text(
                tr("Obtenez une clé sur aistudio.google.com/apikey"),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

internal fun validatedApiKey(input: String): String? {
    val key = input.trim()
    return key.takeIf { it.isNotEmpty() && it.length <= 512 && it.all { char -> char in '!'..'~' } }
}
