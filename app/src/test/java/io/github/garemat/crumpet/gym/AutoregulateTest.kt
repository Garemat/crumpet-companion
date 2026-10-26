package io.github.garemat.crumpet.gym

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutoregulateTest {
    private val info = ExerciseInfo(increment = 5.0, target = GymTarget(60.0, 9, "reps"))

    private fun session(superset: String? = null): ActiveSession {
        val re = RoutineExercise("chest press (machine)", superset, restS = 120,
            sets = listOf(RoutineSet("warmup", 15, 40.0), RoutineSet(), RoutineSet(), RoutineSet()))
        return SessionOps.fromRoutine(Routine("r", "Push", body = listOf(re)),
            mapOf("chest press (machine)" to info), "u", "x", 0)
    }

    private fun ActiveSession.sets() = exercises.first().sets
    private fun ActiveSession.ex() = exercises.first().key

    private fun tick(s: ActiveSession, i: Int, reps: Int): ActiveSession {
        val set = s.sets()[i]
        val typed = SessionOps.editSet(s, s.ex(), set.key, reps = reps)
        return SessionOps.toggleDone(typed, s.ex(), set.key, info)
    }

    @Test
    fun `beating the target by 2 steps the untouched sets up once`() {
        var s = tick(session(), 1, 11)  // 60×11 vs target 60×9
        assertEquals(listOf(40.0, 60.0, 65.0, 65.0), s.sets().map { it.weight })
        assertEquals(listOf(0, 0, 1, 1), s.sets().map { it.auto })
        s = tick(s, 2, 12)              // already adjusted once: no second bump
        assertEquals(65.0, s.sets()[3].weight)
    }

    @Test
    fun `missing by 3 steps down, small misses change nothing`() {
        assertEquals(listOf(55.0, 55.0), tick(session(), 1, 6).sets().drop(2).map { it.weight })
        assertEquals(listOf(60.0, 60.0), tick(session(), 1, 7).sets().drop(2).map { it.weight })
        assertEquals(listOf(60.0, 60.0), tick(session(), 1, 10).sets().drop(2).map { it.weight })
    }

    @Test
    fun `rows the user typed into are left alone, and unticking reverts`() {
        var s = session()
        s = SessionOps.editSet(s, s.ex(), s.sets()[3].key, weight = 62.5)  // they chose this
        s = tick(s, 1, 12)
        assertEquals(listOf(65.0, 62.5), s.sets().drop(2).map { it.weight })
        s = SessionOps.toggleDone(s, s.ex(), s.sets()[1].key, info)  // untick the trigger
        assertEquals(listOf(60.0, 62.5), s.sets().drop(2).map { it.weight })
        assertEquals(0, s.sets()[2].auto)
    }

    @Test
    fun `off-target weights, warm-ups and no-target lifts never adjust`() {
        var s = session()
        s = SessionOps.editSet(s, s.ex(), s.sets()[1].key, weight = 50.0)
        assertEquals(listOf(60.0, 60.0), tick(s, 1, 14).sets().drop(2).map { it.weight })
        assertEquals(listOf(60.0, 60.0, 60.0), tick(session(), 0, 30).sets().drop(1).map { it.weight })
    }

    @Test
    fun `assisted lifts step the assistance down when beaten`() {
        val a = ExerciseInfo(assisted = true, increment = 7.0, target = GymTarget(54.0, 6, "reps"))
        val re = RoutineExercise("pull up (assisted)", sets = listOf(RoutineSet(), RoutineSet()))
        var s = SessionOps.fromRoutine(Routine("r", "Pull", body = listOf(re)), mapOf("pull up (assisted)" to a), "u", "x", 0)
        val first = s.exercises.first().sets.first()
        s = SessionOps.toggleDone(SessionOps.editSet(s, s.exercises.first().key, first.key, reps = 9),
            s.exercises.first().key, first.key, a)
        assertEquals(47.0, s.exercises.first().sets[1].weight)
    }

    @Test
    fun `rest follows drops and supersets`() {
        var s = session()
        assertEquals(120, SessionOps.restAfter(s, s.ex(), s.sets()[1].key))
        s = SessionOps.addDrop(s, s.ex(), 5.0)
        assertNull(SessionOps.restAfter(s, s.ex(), s.sets()[3].key))  // straight into the drop
        val sup = SessionOps.addExercise(session("A"), "face pull", null)
        val withB = sup.copy(exercises = sup.exercises.mapIndexed { i, e -> if (i == 1) e.copy(superset = "A", restS = 60) else e })
        assertNull(SessionOps.restAfter(withB, withB.exercises[0].key, withB.exercises[0].sets[1].key))
        assertEquals(60, SessionOps.restAfter(withB, withB.exercises[1].key, withB.exercises[1].sets[0].key))
    }
}
