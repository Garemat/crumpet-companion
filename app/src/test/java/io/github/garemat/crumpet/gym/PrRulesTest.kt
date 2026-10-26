package io.github.garemat.crumpet.gym

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The same vectors as crumpet tests/test_progression.py's PR tests — the phone's live
 *  badge must agree with the brain's ingest re-check. */
class PrRulesTest {
    private fun s(w: Double?, r: Int, t: String = "working") = LastSet(w, r, t)

    @Test
    fun `weight keys match python's g formatting`() {
        assertEquals("60", PrRules.weightKey(60.0))
        assertEquals("62.5", PrRules.weightKey(62.5))
        assertEquals("20.4", PrRules.weightKey(20.4))
        assertEquals("17.01", PrRules.weightKey(17.01))
        assertEquals("bw", PrRules.weightKey(null))
        assertEquals("bw", PrRules.weightKey(0.0))
    }

    @Test
    fun `bests skip warmups and drops and track reps per weight`() {
        val b = PrRules.bests(listOf(s(100.0, 20, "warmup"), s(60.0, 10), s(62.5, 8),
            s(30.0, 15, "drop"), s(null, 12)))
        assertEquals(62.5, b.maxWeight!!, 0.0)
        assertEquals(80.0, b.bestE1rm!!, 0.0)
        assertEquals(600.0, b.bestVolume!!, 0.0)
        assertEquals(mapOf("60" to 10, "62.5" to 8, "bw" to 12), b.repsAt)
    }

    @Test
    fun `new prs name each kind, equalling is not beating, first ever is not one`() {
        val b = PrRules.bests(listOf(s(60.0, 10)))
        assertEquals(listOf("reps", "e1rm", "volume"), PrRules.newPrs(b, 60.0, 11, "working", false))
        assertEquals(listOf("weight"), PrRules.newPrs(b, 65.0, 5, "working", false))
        assertEquals(emptyList<String>(), PrRules.newPrs(b, 60.0, 10, "working", false))
        assertEquals(emptyList<String>(), PrRules.newPrs(b, 90.0, 10, "warmup", false))
        assertEquals(emptyList<String>(), PrRules.newPrs(Bests(), 60.0, 10, "working", false))
    }

    @Test
    fun `assisted prs only count reps at a weight`() {
        val b = PrRules.bests(listOf(s(54.0, 6)), assisted = true)
        assertNull(b.maxWeight)
        assertEquals(mapOf("54" to 6), b.repsAt)
        assertEquals(listOf("reps"), PrRules.newPrs(b, 54.0, 7, "working", true))
        assertEquals(emptyList<String>(), PrRules.newPrs(b, 70.0, 6, "working", true))
    }

    @Test
    fun `epley rounds like the brain`() {
        assertEquals(76.0, PrRules.epley(60.0, 8), 0.0)
        assertEquals(100.0, PrRules.epley(100.0, 1), 0.0)
        assertTrue(PrRules.isEmpty(Bests()))
    }
}
