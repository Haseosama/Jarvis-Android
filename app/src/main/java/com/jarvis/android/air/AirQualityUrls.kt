package com.jarvis.android.air

import okhttp3.HttpUrl.Companion.toHttpUrl

/** The European Air Quality Index's own bands (health-relevant thresholds, not something this app invented). */
internal fun describeEuropeanAqi(aqi: Int): String = when {
    aqi <= 20 -> "bon"
    aqi <= 40 -> "moyen"
    aqi <= 60 -> "dégradé"
    aqi <= 80 -> "mauvais"
    aqi <= 100 -> "très mauvais"
    else -> "extrêmement mauvais"
}

internal fun airQualityUrl(latitude: Double, longitude: Double): String =
    "https://air-quality-api.open-meteo.com/v1/air-quality".toHttpUrl().newBuilder()
        .addQueryParameter("latitude", latitude.toString())
        .addQueryParameter("longitude", longitude.toString())
        .addQueryParameter("current", "european_aqi,pm2_5,pm10," + com.jarvis.android.air.POLLENS.joinToString(",") { it.key })
        .build().toString()
