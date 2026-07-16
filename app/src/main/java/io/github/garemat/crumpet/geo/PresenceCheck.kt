package io.github.garemat.crumpet.geo

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Pure presence classification — no Android types, so the transition logic is unit-tested
 *  on the JVM. Given a fix and the places, decide where the user is and which events a
 *  change implies. This replaces Play services' GeofencingClient (deliberately: the app
 *  stays fully local/Google-free), so the enter/exit hysteresis lives here. */
object PresenceCheck {

    /** Exit multiplier: you ENTER a place inside its radius but only LEAVE it beyond
     *  radius × this — a fix wobbling around the boundary can't flap arrived/left. */
    private const val EXIT_FACTOR = 1.6

    /** A fix this uncertain can't prove you LEFT somewhere — staying put is the safe read
     *  (a false "left Home" is worse than a late one; it says "house empty" to any consumer). */
    private const val MAX_EXIT_ACCURACY_M = 200.0

    data class Fix(val lat: Double, val lng: Double, val accuracyM: Double)

    data class Outcome(val label: String, val events: List<PresenceEvent>)

    /** Classify [fix] against [places] given the previously-known [lastLabel] ("" = away).
     *  Returns the new label + the transition events to send ([] = no change). [area] is the
     *  town for away states (geocoded by the caller — this layer never sees a geocoder).
     *  [at] is the event timestamp (ISO, local). */
    fun classify(
        places: List<Place>,
        lastLabel: String,
        fix: Fix,
        area: String?,
        at: String,
    ): Outcome {
        val current = places.find { it.label == lastLabel }
        val stillInside = current != null &&
            distanceM(fix.lat, fix.lng, current.lat, current.lng) <=
            current.radiusM * EXIT_FACTOR

        // Currently at a place and the fix can't reliably say otherwise → stay put.
        if (current != null && (stillInside || fix.accuracyM > MAX_EXIT_ACCURACY_M)) {
            return Outcome(lastLabel, emptyList())
        }

        // Which place (if any) is the fix inside now? Nearest wins on overlap.
        val entered = places
            .map { it to distanceM(fix.lat, fix.lng, it.lat, it.lng) }
            .filter { (p, d) -> d <= p.radiusM }
            .minByOrNull { it.second }?.first

        return when {
            entered != null && entered.label == lastLabel -> Outcome(lastLabel, emptyList())
            entered != null && lastLabel.isNotEmpty() -> Outcome(
                entered.label,
                listOf(
                    PresenceEvent("left", lastLabel, null, at),
                    PresenceEvent("arrived", entered.label, null, at),
                ),
            )
            entered != null -> Outcome(entered.label, listOf(PresenceEvent("arrived", entered.label, null, at)))
            lastLabel.isNotEmpty() -> Outcome("", listOf(PresenceEvent("left", lastLabel, area, at)))
            else -> Outcome("", emptyList()) // away → away: no trail, by design
        }
    }

    /** Haversine distance in metres. */
    fun distanceM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2) * sin(dLng / 2)
        return 2 * r * atan2(sqrt(a), sqrt(1 - a))
    }
}
