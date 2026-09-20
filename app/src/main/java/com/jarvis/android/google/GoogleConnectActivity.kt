package com.jarvis.android.google

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.jarvis.android.JarvisApp
import com.jarvis.android.i18n.tr
import kotlinx.coroutines.launch

/**
 * The screen-less activity that asks Google for the access to Gmail and Drive (the system shows the consent sheet) and records that the
 * account is connected. It closes itself when done.
 */
class GoogleConnectActivity : ComponentActivity() {
    private val consent = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        try {
            done(Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(result.data))
        } catch (e: ApiException) {
            fail(GoogleAuth.explain(e, this))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            try {
                val result = GoogleAuth.authorize(this@GoogleConnectActivity)
                val pending = result.pendingIntent
                if (result.hasResolution() && pending != null) consent.launch(IntentSenderRequest.Builder(pending.intentSender).build()) else done(result)
            } catch (e: ApiException) {
                fail(GoogleAuth.explain(e, this@GoogleConnectActivity))
            } catch (e: Exception) {
                fail(e.message ?: tr("Connexion à Google impossible."))
            }
        }
    }

    private fun done(result: AuthorizationResult) {
        if (result.accessToken == null) {
            fail(tr("Google n’a pas accordé l’accès."))
            return
        }
        lifecycleScope.launch {
            (applicationContext as JarvisApp).container.configStore.setGoogleConnected(true)
            Toast.makeText(this@GoogleConnectActivity, tr("Google connecté."), Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun fail(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finish()
    }
}
