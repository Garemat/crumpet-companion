package io.github.garemat.crumpet.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The transition logic that replaces GeofencingClient — enter/exit synthesis, boundary
 *  hysteresis, and the bad-fix guard. All distances are real haversine metres around a
 *  Manchester-ish origin (1e-3 deg lat ≈ 111 m). */
class PresenceCheckTest {

    private val home = Place("Home", 53.4800, -2.2400, 150)
    private val gym = Place("Gym", 53.4900, -2.2400, 150)   // ~1.1 km north of Home
    private val places = listOf(home, gym)
    private val at = "2026-07-16T10:00"

    private fun fix(lat: Double, lng: Double, acc: Double = 20.0) = PresenceCheck.Fix(lat, lng, acc)

    @Test
    fun `arriving somewhere from away emits one arrived`() {
        val out = PresenceCheck.classify(places, "", fix(home.lat, home.lng), null, at)
        assertEquals("Home", out.label)
        assertEquals(listOf(PresenceEvent("arrived", "Home", null, at)), out.events)
    }

    @Test
    fun `still inside emits nothing`() {
        val out = PresenceCheck.classify(places, "Home", fix(home.lat + 0.0005, home.lng), null, at)
        assertEquals("Home", out.label)
        assertTrue(out.events.isEmpty())
    }

    @Test
    fun `leaving to nowhere emits left with the town`() {
        // ~550 m away — beyond 150 m × 1.6.
        val out = PresenceCheck.classify(places, "Home", fix(home.lat + 0.005, home.lng), "Manchester", at)
        assertEquals("", out.label)
        assertEquals(listOf(PresenceEvent("left", "Home", "Manchester", at)), out.events)
    }

    @Test
    fun `moving place to place emits left then arrived`() {
        val out = PresenceCheck.classify(places, "Home", fix(gym.lat, gym.lng), null, at)
        assertEquals("Gym", out.label)
        assertEquals(
            listOf(
                PresenceEvent("left", "Home", null, at),
                PresenceEvent("arrived", "Gym", null, at),
            ),
            out.events,
        )
    }

    @Test
    fun `boundary wobble does not flap`() {
        // ~190 m out: outside the 150 m enter radius but inside the 240 m exit radius →
        // still Home. The same fix from away would NOT count as arriving.
        val wobble = fix(home.lat + 0.0017, home.lng)
        assertEquals("Home", PresenceCheck.classify(places, "Home", wobble, null, at).label)
        assertTrue(PresenceCheck.classify(places, "Home", wobble, null, at).events.isEmpty())
        assertEquals("", PresenceCheck.classify(places, "", wobble, null, at).label)
    }

    @Test
    fun `a hopeless fix cannot prove an exit`() {
        // 2 km away but ±500 m accuracy — saying "left Home" on this would tell the brain
        // the house is empty on garbage data. Stay put.
        val bad = fix(home.lat + 0.02, home.lng, acc = 500.0)
        val out = PresenceCheck.classify(places, "Home", bad, "Manchester", at)
        assertEquals("Home", out.label)
        assertTrue(out.events.isEmpty())
    }

    @Test
    fun `away to away emits no trail`() {
        val out = PresenceCheck.classify(places, "", fix(53.3, -2.1), "Stockport", at)
        assertEquals("", out.label)
        assertTrue(out.events.isEmpty())
    }

    @Test
    fun `overlapping places pick the nearest`() {
        val office = Place("Office", 53.4801, -2.2400, 300) // overlaps Home's fence
        val out = PresenceCheck.classify(listOf(home, office), "", fix(home.lat, home.lng), null, at)
        assertEquals("Home", out.label)
    }
}
