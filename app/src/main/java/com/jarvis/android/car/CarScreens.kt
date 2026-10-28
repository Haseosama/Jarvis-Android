package com.jarvis.android.car

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarLocation
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Metadata
import androidx.car.app.model.Place
import androidx.car.app.model.PlaceListMapTemplate
import androidx.car.app.model.PlaceMarker
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.lifecycleScope
import com.jarvis.android.JarvisApp
import com.jarvis.android.actions.Fuel
import com.jarvis.android.actions.FuelMap
import com.jarvis.android.actions.FuelPriceTool
import com.jarvis.android.i18n.tr
import com.jarvis.android.nearby.NearbyMap
import com.jarvis.android.nearby.NearbyTool
import com.jarvis.android.nearby.PlaceKind
import com.jarvis.android.nearby.distanceWords
import com.jarvis.android.nearby.openWords
import com.jarvis.android.rest.RestChatException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.time.LocalDateTime
import java.util.Locale

/*
 * Jarvis's own screens in Android Auto (Google's car templates: lists, a map with places, a message; nothing else may be drawn in a car).
 * The first page holds what is worth having while driving: talking to Jarvis with the car's microphone, the day, messages, what is
 * around, the cheapest fuel, and the news. Everything Jarvis does on the phone is reached by talking to it.
 */

/** An entry of the car's first page: what is shown, and what Jarvis is asked when it is a question (null: a screen of its own). */
internal data class CarEntry(val id: String, val title: String, val subtitle: String, val question: String?)

/** The car's first page; Android Auto shows six rows at most while driving, so there are six. */
internal fun carHomeEntries(): List<CarEntry> = listOf(
    CarEntry("talk", tr("Parler à Jarvis"), tr("Le micro de la voiture : tout ce que fait le téléphone"), null),
    CarEntry("day", tr("Ma journée"), tr("Agenda, rappels et météo"), tr("Qu’est-ce que j’ai aujourd’hui ? Mon agenda, mes rappels et la météo, en quelques phrases.")),
    CarEntry("messages", tr("Mes messages"), tr("Les derniers reçus, lus à voix haute"), tr("Lis-moi mes derniers messages non lus, en bref.")),
    CarEntry("nearby", tr("Autour de moi"), tr("Toilettes, recharge, pharmacie, distributeur…"), null),
    CarEntry("fuel", tr("Carburant le moins cher"), tr("Les stations autour de vous"), null),
    CarEntry("news", tr("Le journal"), tr("Le dernier bulletin d’info"), null),
)

/** What the car's "around me" list offers, the most useful on the road first. */
internal val CAR_PLACE_KINDS = listOf(PlaceKind.TOILETS, PlaceKind.CHARGER, PlaceKind.PHARMACY, PlaceKind.ATM, PlaceKind.BAKERY)

/** A navigation request for a place, as Android Auto hands it to the chosen navigation app. */
internal fun navigationUri(lat: Double, lon: Double, label: String): String =
    "geo:0,0?q=${String.format(Locale.ROOT, "%.6f,%.6f", lat, lon)}(${java.net.URLEncoder.encode(label, "UTF-8").replace("+", "%20")})"

private fun Screen.navigateTo(lat: Double, lon: Double, label: String) {
    try {
        carContext.startCarApp(Intent(CarContext.ACTION_NAVIGATE, Uri.parse(navigationUri(lat, lon, label))))
    } catch (_: Exception) {
        screenManager.push(CarAnswerScreen(carContext, label, initial = tr("Aucune appli de navigation ne répond.")) { null })
    }
}

internal class CarHomeScreen(carContext: CarContext) : Screen(carContext) {
    override fun onGetTemplate(): Template {
        val list = ItemList.Builder()
        carHomeEntries().forEach { e ->
            list.addItem(Row.Builder().setTitle(e.title).addText(e.subtitle).setBrowsable(e.question == null && e.id != "news").setOnClickListener { open(e) }.build())
        }
        return ListTemplate.Builder().setTitle("Jarvis").setHeaderAction(Action.APP_ICON).setSingleList(list.build()).build()
    }

    private fun open(e: CarEntry) {
        val sm = screenManager
        when {
            e.id == "talk" -> sm.push(talkScreen(carContext))
            e.id == "nearby" -> sm.push(CarPlaceKindsScreen(carContext))
            e.id == "fuel" -> sm.push(CarFuelScreen(carContext))
            e.id == "news" -> {
                val container = (carContext.applicationContext as JarvisApp).container
                container.appScope.launch { CarLibrary.play(container, "news:", null) }
                sm.push(CarAnswerScreen(carContext, e.title, initial = tr("Le journal démarre : il passe par le son de la voiture.")) { null })
            }
            e.question != null -> sm.push(CarAnswerScreen(carContext, e.title) { CarChat.ask(carContext, e.question) })
        }
    }
}

/** The microphone screen: listens, asks, says the answer; « Encore » listens again. */
internal fun talkScreen(carContext: CarContext): CarAnswerScreen =
    CarAnswerScreen(carContext, tr("Parler à Jarvis"), again = true) { screen ->
        val said = CarVoice.listen(carContext)
        screen.heard(said)
        CarChat.ask(carContext, said)
    }

/**
 * A message screen for one answer: « … » while it is worked out, then the answer, said aloud. [work] returns the answer (null: [initial]
 * stays); a [RestChatException] shows its words.
 */
internal class CarAnswerScreen(
    carContext: CarContext,
    private val title: String,
    private val initial: String? = null,
    private val again: Boolean = false,
    private val work: suspend (CarAnswerScreen) -> String?,
) : Screen(carContext) {
    private var text: String? = initial
    private var loading = initial == null
    private var started = false

    /** What the car heard, shown while Jarvis works on it. */
    fun heard(said: String) {
        text = "« $said »"
        invalidate()
    }

    private fun run() {
        loading = true
        text = initial
        invalidate()
        lifecycleScope.launch {
            val answer = try {
                work(this@CarAnswerScreen)?.let { carSpoken(it) }?.also { com.jarvis.android.driving.DrivingMode.speak(carContext, it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: RestChatException) {
                e.message
            } catch (_: Exception) {
                tr("Erreur inattendue. Réessayez.")
            }
            if (answer != null) text = answer
            loading = false
            invalidate()
        }
    }

    override fun onGetTemplate(): Template {
        if (!started) {
            started = true
            if (initial == null) run()
        }
        val shown = text
        if (loading && shown == null) return MessageTemplate.Builder(tr("Un instant…")).setTitle(title).setHeaderAction(Action.BACK).setLoading(true).build()
        val b = MessageTemplate.Builder(shown ?: tr("Je n’ai pas de réponse.")).setTitle(title).setHeaderAction(Action.BACK)
        if (!loading && again) b.addAction(Action.Builder().setTitle(tr("Encore")).setOnClickListener { run() }.build())
        return b.build()
    }
}

internal class CarPlaceKindsScreen(carContext: CarContext) : Screen(carContext) {
    override fun onGetTemplate(): Template {
        val list = ItemList.Builder()
        CAR_PLACE_KINDS.forEach { k -> list.addItem(Row.Builder().setTitle(k.plural).setBrowsable(true).setOnClickListener { screenManager.push(CarPlacesScreen(carContext, k)) }.build()) }
        return ListTemplate.Builder().setTitle(tr("Autour de moi")).setHeaderAction(Action.BACK).setSingleList(list.build()).build()
    }
}

/** One spot on a car map: its name, what is said under it, and where it is. */
internal data class CarSpot(val title: String, val detail: String, val lat: Double, val lon: Double)

/** A map with the places found (six at most, as Android Auto allows) and the way there from each one. */
internal abstract class CarMapScreen(carContext: CarContext, private val title: String) : Screen(carContext) {
    private var spots: List<CarSpot>? = null
    private var origin: Pair<Double, Double>? = null
    private var problem: String? = null
    private var started = false

    /** The spots and where they were looked from, or the words to say when there are none. */
    abstract suspend fun find(): Triple<List<CarSpot>, Pair<Double, Double>?, String>

    override fun onGetTemplate(): Template {
        if (!started) {
            started = true
            lifecycleScope.launch {
                try {
                    val (found, from, words) = find()
                    spots = found.take(6)
                    origin = from
                    if (found.isEmpty()) problem = carSpoken(words)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    problem = tr("Service indisponible pour le moment.")
                }
                if (spots.isNullOrEmpty()) problem?.let { com.jarvis.android.driving.DrivingMode.speak(carContext, it) }
                invalidate()
            }
        }
        problem?.takeIf { spots.isNullOrEmpty() }?.let { return MessageTemplate.Builder(it).setTitle(title).setHeaderAction(Action.BACK).build() }
        val b = PlaceListMapTemplate.Builder().setTitle(title).setHeaderAction(Action.BACK)
        val found = spots ?: return b.setLoading(true).build()
        val list = ItemList.Builder()
        found.forEachIndexed { i, s ->
            val place = Place.Builder(CarLocation.create(s.lat, s.lon)).setMarker(PlaceMarker.Builder().setLabel("${i + 1}").build()).build()
            list.addItem(Row.Builder().setTitle(s.title).addText(s.detail).setMetadata(Metadata.Builder().setPlace(place).build()).setOnClickListener { navigateTo(s.lat, s.lon, s.title) }.build())
        }
        origin?.let { (lat, lon) -> b.setAnchor(Place.Builder(CarLocation.create(lat, lon)).build()) }
        return b.setItemList(list.build()).build()
    }
}

internal class CarPlacesScreen(carContext: CarContext, private val kind: PlaceKind) : CarMapScreen(carContext, kind.plural) {
    override suspend fun find(): Triple<List<CarSpot>, Pair<Double, Double>?, String> {
        val container = (carContext.applicationContext as JarvisApp).container
        NearbyMap.hits = emptyList()
        val words = NearbyTool.run(buildJsonObject { put("quoi", JsonPrimitive(kind.singular)); put("ville", JsonPrimitive("ici")) }, container)
        val now = LocalDateTime.now()
        val spots = NearbyMap.hits.map { h ->
            CarSpot(h.place.name ?: kind.singular, "${distanceWords(h.distanceM).removePrefix("à ")} · ${openWords(kind, h, now)}", h.place.lat, h.place.lon)
        }
        return Triple(spots, NearbyMap.origin, words)
    }
}

/** The fuel chosen last in the car, kept for the next drive. */
private fun savedFuel(context: Context): Fuel? =
    context.getSharedPreferences("car", Context.MODE_PRIVATE).getString("fuel", null)?.let { name -> Fuel.entries.firstOrNull { it.name == name } }

internal class CarFuelScreen(carContext: CarContext) : Screen(carContext) {
    override fun onGetTemplate(): Template {
        val last = savedFuel(carContext)
        val list = ItemList.Builder()
        (listOfNotNull(last) + Fuel.entries.filter { it != last }).take(6).forEach { f ->
            list.addItem(Row.Builder().setTitle(f.label).apply { if (f == last) addText(tr("Votre dernier choix")) }.setBrowsable(true).setOnClickListener {
                carContext.getSharedPreferences("car", Context.MODE_PRIVATE).edit().putString("fuel", f.name).apply()
                screenManager.push(CarStationsScreen(carContext, f))
            }.build())
        }
        return ListTemplate.Builder().setTitle(tr("Carburant le moins cher")).setHeaderAction(Action.BACK).setSingleList(list.build()).build()
    }
}

internal class CarStationsScreen(carContext: CarContext, private val fuel: Fuel) : CarMapScreen(carContext, "${fuel.label} · ${tr("le moins cher")}") {
    override suspend fun find(): Triple<List<CarSpot>, Pair<Double, Double>?, String> {
        val container = (carContext.applicationContext as JarvisApp).container
        FuelMap.stations = emptyList()
        val words = FuelPriceTool.run(buildJsonObject { put("carburant", JsonPrimitive(fuel.label)); put("ville", JsonPrimitive("ici")) }, container)
        val spots = FuelMap.stations.mapNotNull { s ->
            val lat = s.latitude ?: return@mapNotNull null
            val lon = s.longitude ?: return@mapNotNull null
            CarSpot("${String.format(Locale.FRANCE, "%.3f", s.price)} € · ${s.city}", s.address, lat, lon)
        }
        return Triple(spots, FuelMap.origin, words)
    }
}
