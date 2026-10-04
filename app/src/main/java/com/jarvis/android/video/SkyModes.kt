package com.jarvis.android.video

/** What the sky view shows: "all", "satellites", "planes", "stars" (the night sky), or "pass:<name>" (a satellite's next pass). */
internal object SkyModes {
    const val ALL = "all"
    const val STARS = "stars"
    const val SATELLITES = "satellites"
    const val PLANES = "planes"
    const val PASS = "pass:"
    /** The world map: the ISS and Tiangong over the Earth, day and night. */
    const val MAP = "map"
    /** The rain radar around the user, animated. */
    const val RADAR = "radar"
    /** A followed flight on the world map: "flight:<callsign>|<flight number>". */
    const val FLIGHT = "flight:"
    /** The sky through the camera, with names on what is there. */
    const val AR = "ar"

    /** Fuel stations and their prices. */
    const val FUEL = "fuel"

    /** The nearest pharmacies, bakeries, cash machines, toilets or chargers, open or closed. */
    const val NEARBY = "nearby"

    /** The rivers under flood watch (Vigicrues). */
    const val FLOODS = "floods"

    /** Tonight's sky for observing, hour by hour. */
    const val OBSERVE = "observe"

    /** The earthquakes of the day and the week. */
    const val QUAKES = "quakes"

    /** The air quality around a place. */
    const val AIR = "air"

    /** A drive and its weather on the world map. */
    const val ROUTE = "route"

    /** The aurora oval on the world map. */
    const val AURORA = "aurora"

    /** The next rocket launches with their countdown. */
    const val LAUNCHES = "launches"

    /** The camera guiding to one object: "ar:<name>". */
    const val AR_TARGET = "ar:"

    fun isAr(mode: String) = mode == AR || mode.startsWith(AR_TARGET)

    /** Whether a mode is drawn on the world map rather than as a sky chart. */
    fun onMap(mode: String) = mode == MAP || mode == RADAR || mode == AURORA || mode == ROUTE || mode == AIR || mode == QUAKES || mode == FLOODS || mode == FUEL || mode == NEARBY || mode.startsWith(FLIGHT)
}
