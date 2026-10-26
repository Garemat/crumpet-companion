package io.github.garemat.crumpet.health

import org.junit.Assert.assertEquals
import org.junit.Test

/** The catch-up window: never smaller than 30 days, grows with brain downtime, capped. */
class SyncWindowTest {
    private val day = 24 * 60 * 60 * 1000L
    private val now = 1_800_000_000_000L

    @Test
    fun `a recent sync keeps the 30-day floor`() {
        assertEquals(30, SyncWindow.days(now - 3 * 60 * 60 * 1000L, now))
        assertEquals(30, SyncWindow.days(now - 20 * day, now))
    }

    @Test
    fun `brain downtime widens the window to cover it`() {
        // the real gap: last success 26 Jul, back in late Oct (~92 days)
        assertEquals(94, SyncWindow.days(now - 92 * day, now))
        assertEquals(43, SyncWindow.days(now - 40 * day - 1, now))  // a partial day rounds up
    }

    @Test
    fun `never synced or nonsense watermarks fall back to the floor, and it is capped`() {
        assertEquals(30, SyncWindow.days(0L, now))
        assertEquals(30, SyncWindow.days(now + day, now))  // clock went backwards
        assertEquals(365, SyncWindow.days(now - 900 * day, now))
    }
}
