package io.github.garemat.crumpet.gym

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryOpsTest {
    private val json = Json { encodeDefaults = true }
    private fun queued(uid: String, at: String) = GymOutboxItem(1, "POST", "/gym/sessions", json.encodeToString(
        SessionBody.serializer(), SessionBody(uid, null, "Push", at, at.dropLast(2) + "50", "",
            listOf(SessionExerciseBody("chest press (machine)", sets = listOf(SessionSetBody("working", 10, 60.0)))))))

    @Test
    fun `queued sessions show at the top until the brain has them`() {
        val outbox = listOf(queued("a", "2026-10-26 07:00"), queued("b", "2026-10-27 07:00"),
            GymOutboxItem(9, "PUT", "/gym/routines/r1", "{}"))
        val pending = HistoryOps.pending(outbox)
        assertEquals(listOf("b", "a"), pending.map { it.uid })
        assertTrue(pending.all { it.pending } && pending.first().durationMin == 50)
        val page = listOf(HistorySession(id = 7, uid = "a", title = "Push", at = "2026-10-26 07:00"),
            HistorySession(id = 6, uid = null, title = "Leg", at = "2026-10-20 07:00"))
        val merged = HistoryOps.merge(pending, page)
        assertEquals(listOf("p-b", "7", "6"), merged.map { it.key })  // 'a' landed: shown once
    }

    @Test
    fun `per-exercise history keeps only that lift`() {
        val s = HistorySession(id = 1, title = "Push", at = "x", exercises = listOf(
            HistoryExercise("a", sets = listOf(HistorySet(reps = 5, prs = listOf("reps")))),
            HistoryExercise("b", sets = listOf(HistorySet(reps = 5)))))
        val only = HistoryOps.forExercise(listOf(s, s.copy(id = 2, exercises = s.exercises.drop(1))), "a")
        assertEquals(1, only.size)
        assertEquals(listOf("a"), only.single().exercises.map { it.exercise })
        assertEquals(1, HistoryOps.prCount(s))
        assertEquals(2, HistoryOps.setCount(s))
    }
}

class ReplaceExerciseTest {
    private val stateJson = Json { ignoreUnknownKeys = true }
    private val state: GymState = stateJson.decodeFromString(
        javaClass.classLoader!!.getResource("gym/state.json")!!.readText())

    @Test
    fun `replace keeps the set shape, drops the drops, refills from the new lift`() {
        val r = state.routines.first { it.id == "r1" }
        var s = SessionOps.fromRoutine(r, state.exercises, "u", "2026-10-26 07:00", 0)
        val chest = s.exercises.first { it.exercise == "chest press (machine)" }
        s = SessionOps.addDrop(s, chest.key, 5.0)
        s = SessionOps.toggleDone(s, chest.key, chest.sets[1].key, state.exercises["chest press (machine)"])
        val plates = "shoulder press (machine plates)"
        s = SessionOps.replaceExercise(s, chest.key, plates, state.exercises[plates])
        val ex = s.exercises.first { it.key == chest.key }
        assertEquals(plates, ex.exercise)
        assertEquals(listOf("warmup", "working", "working"), ex.sets.map { it.type })
        assertTrue(ex.sets.none { it.done || it.prs.isNotEmpty() })
        assertEquals(state.exercises.getValue(plates).target!!.weight, ex.sets[1].weight)
        val keys = s.exercises.flatMap { e -> e.sets.map { it.key } } + s.exercises.map { it.key }
        assertEquals(keys.size, keys.toSet().size)  // fresh keys, no collisions
    }
}
