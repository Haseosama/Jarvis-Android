package com.jarvis.android.google

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

/** What Google needs to know about this app to accept its sign-in: the package name and the SHA-1 fingerprint of the key that signed it. */
internal object AppIdentity {
    /** The SHA-1 of the signing certificate, as `AA:BB:…` (the form Google Cloud asks for), or null if it cannot be read. */
    @Suppress("DEPRECATION")
    fun sha1(context: Context): String? = try {
        val info = if (Build.VERSION.SDK_INT >= 28) {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
        }
        val signature = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners?.firstOrNull() else info.signatures?.firstOrNull()
        signature?.let { fingerprint(it.toByteArray()) }
    } catch (_: Exception) {
        null
    }

    fun fingerprint(certificate: ByteArray): String =
        MessageDigest.getInstance("SHA-1").digest(certificate).joinToString(":") { "%02X".format(it) }
}
