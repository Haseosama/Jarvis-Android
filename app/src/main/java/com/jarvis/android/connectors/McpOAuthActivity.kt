package com.jarvis.android.connectors

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.jarvis.android.JarvisApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where the browser comes back after a connector's login: hands the code to the connectors, says how it went, then shows Jarvis again. */
class McpOAuthActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val data = intent?.data
        val container = (applicationContext as JarvisApp).container
        if (data != null) {
            val code = data.getQueryParameter("code")
            val state = data.getQueryParameter("state")
            val error = data.getQueryParameter("error_description") ?: data.getQueryParameter("error")
            val app = applicationContext
            container.appScope.launch {
                val outcome = container.connectors.finishLogin(code, state, error)
                withContext(Dispatchers.Main) { Toast.makeText(app, outcome.message, Toast.LENGTH_LONG).show() }
            }
        }
        packageManager.getLaunchIntentForPackage(packageName)?.let {
            startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        }
        finish()
    }
}
