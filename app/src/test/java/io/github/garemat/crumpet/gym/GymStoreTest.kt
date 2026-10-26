package io.github.garemat.crumpet.gym

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Persistence: everything survives a process restart (a new store over the same dir),
 *  the outbox keeps order, and a finished session clears the active file. */
class GymStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `state, active session and outbox survive a restart`() = runBlocking {
        val dir = tmp.newFolder("gym")
        val a = GymStore(dir)
        a.saveState(GymState(serverTime = 42, routines = listOf(Routine("r1", "Push"))))
        a.setActive(ActiveSession("u1", null, "Push", "2026-10-26 07:30", 1))
        a.enqueue("POST", "/gym/sessions", "{}", "first")
        a.enqueue("PUT", "/gym/routines/r1", "{}", "second")
        a.setMeta { it.copy(note = "hello") }

        val b = GymStore(dir)  // the process died; a new one starts over the same files
        assertEquals(42L, b.state.value.serverTime)
        assertEquals("u1", b.active.value!!.uid)
        assertEquals(listOf("first", "second"), b.outbox.value.map { it.label })
        assertEquals("hello", b.meta.value.note)

        b.removeFromOutbox(b.outbox.value.first().id)
        b.setActive(null)
        val c = GymStore(dir)
        assertEquals(listOf("second"), c.outbox.value.map { it.label })
        assertNull(c.active.value)
        assertFalse(File(dir, "active.json").exists())
        assertFalse(dir.listFiles()!!.any { it.name.endsWith(".tmp") })
    }

    @Test
    fun `a corrupt file degrades to empty instead of crashing the gym tab`() = runBlocking {
        val dir = tmp.newFolder("gym2")
        File(dir, "state.json").writeText("{not json")
        assertEquals(GymState(), GymStore(dir).state.value)
    }

    @Test
    fun `refused sessions are kept, capped`() = runBlocking {
        val dir = tmp.newFolder("gym3")
        val s = GymStore(dir)
        repeat(25) { s.keepRejected(GymOutboxItem(it.toLong(), "POST", "/gym/sessions", "{}")) }
        assertEquals(20, File(dir, "rejected.json").readText().split("\"id\"").size - 1)
    }
}
