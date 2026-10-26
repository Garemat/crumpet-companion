package io.github.garemat.crumpet.gym

import org.junit.Assert.assertEquals
import org.junit.Test

/** The brain's tests/test_fitness_lifts.py canon vectors, plus Hevy/library-style names. */
class CanonTest {
    @Test
    fun `matches the brain's canonical names`() {
        assertEquals("bench press", Canon.lift("Bench"))
        assertEquals("overhead press", Canon.lift("OHP"))
        assertEquals("romanian deadlift", Canon.lift("RDL"))
        assertEquals("squat", Canon.lift("squats"))
        assertEquals("lat pulldown", Canon.lift("  Lat  Pulldowns "))
        assertEquals("cable crunch", Canon.lift("cable crunches"))
        assertEquals("dip", Canon.lift("dips"))
        assertEquals("press", Canon.lift("press"))
        assertEquals("abs", Canon.lift("abs"))
        assertEquals("zercher squat", Canon.lift("Zercher  Squat"))
        // what the Hevy import produced, and a library name
        assertEquals("iso lateral chest press (machine)", Canon.lift("Iso-Lateral Chest Press (Machine)"))
        assertEquals("lat pulldown close grip (cable)", Canon.lift("Lat Pulldown - Close Grip (Cable)"))
        assertEquals("barbell bench press medium grip", Canon.lift("Barbell Bench Press - Medium Grip"))
    }

    @Test
    fun `pretty names for display`() {
        assertEquals("Chest Press (Machine)", Canon.pretty("chest press (machine)"))
        assertEquals("Pull Up (Assisted)", Canon.pretty("pull up (assisted)"))
    }
}
