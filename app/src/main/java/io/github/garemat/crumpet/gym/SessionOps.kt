package io.github.garemat.crumpet.gym

import kotlin.math.floor

/** Pure transforms on the in-progress session — no Android, no I/O, so every rule here is
 *  unit-tested. The screen calls these and persists the result on EVERY change (a killed
 *  process or a reboot mid-workout resumes exactly where it was). */
object SessionOps {

    // --- starting ------------------------------------------------------------------

    fun fromRoutine(
        routine: Routine, info: Map<String, ExerciseInfo>, uid: String, startedAt: String,
        nowMillis: Long,
    ): ActiveSession {
        var key = 0L
        val exercises = routine.body.map { re ->
            val sets = prefill(re, info[re.exercise]).map { it.copy(key = ++key) }
            ActiveExercise(
                key = ++key, exercise = re.exercise, superset = re.superset,
                restS = info[re.exercise]?.pref?.restS ?: re.restS,
                repMin = re.repMin, repMax = re.repMax, note = re.note, sets = sets,
            )
        }
        return ActiveSession(uid, routine.id.takeUnless { it.startsWith("card-") }, routine.name,
            startedAt, nowMillis, exercises = exercises)
    }

    fun empty(uid: String, title: String, startedAt: String, nowMillis: Long) =
        ActiveSession(uid, null, title, startedAt, nowMillis)

    /** Pre-filled set rows for one routine exercise. Working sets: the brain's target (the
     *  engine's numbers, or a weight Crumpet wrote on the day card) → else the routine's own
     *  numbers → else last session's matching set (the offline fallback: the store clears a
     *  target once a newer local session makes it stale). Warm-ups: the routine's numbers
     *  → else last session's warm-ups. Everything stays editable. */
    fun prefill(re: RoutineExercise, info: ExerciseInfo?): List<ActiveSet> {
        val target = re.target ?: info?.target
        val last = info?.last?.sets.orEmpty()
        val lastWarm = last.filter { it.type == "warmup" }
        val lastWork = last.filter { it.type != "warmup" && it.type != "drop" }
        var w = 0
        var k = 0
        return re.sets.map { s ->
            if (s.type == "warmup") {
                val prev = lastWarm.getOrNull(w++)
                ActiveSet(0, "warmup", s.weight ?: prev?.weight, s.reps ?: prev?.reps)
            } else {
                val prev = lastWork.getOrNull(k) ?: lastWork.lastOrNull()
                k++
                when {
                    target != null && s.weight == null ->
                        ActiveSet(0, s.type, target.weight, target.reps)
                    s.weight != null || s.reps != null -> ActiveSet(0, s.type, s.weight, s.reps)
                    else -> ActiveSet(0, s.type, prev?.weight, prev?.reps)
                }
            }
        }
    }

    // --- editing ---------------------------------------------------------------------

    private fun nextKey(s: ActiveSession): Long =
        1 + maxOf(s.exercises.maxOfOrNull { it.key } ?: 0,
                  s.exercises.flatMap { it.sets }.maxOfOrNull { it.key } ?: 0)

    private fun ActiveSession.mapEx(exKey: Long, f: (ActiveExercise) -> ActiveExercise) =
        copy(exercises = exercises.map { if (it.key == exKey) f(it) else it })

    fun addExercise(s: ActiveSession, name: String, info: ExerciseInfo?): ActiveSession {
        val base = nextKey(s)
        val re = RoutineExercise(name, restS = info?.pref?.restS ?: 90, target = info?.target)
        val sets = prefill(re, info).mapIndexed { i, set -> set.copy(key = base + 1 + i) }
        return s.copy(exercises = s.exercises + ActiveExercise(base, name, restS = re.restS, sets = sets))
    }

    fun removeExercise(s: ActiveSession, exKey: Long) =
        s.copy(exercises = s.exercises.filterNot { it.key == exKey })

    fun moveExercise(s: ActiveSession, exKey: Long, delta: Int): ActiveSession {
        val i = s.exercises.indexOfFirst { it.key == exKey }
        val j = i + delta
        if (i < 0 || j !in s.exercises.indices) return s
        val list = s.exercises.toMutableList().apply { add(j, removeAt(i)) }
        return s.copy(exercises = list)
    }

    /** A new set copying the last one's numbers (how you actually add a set mid-workout). */
    fun addSet(s: ActiveSession, exKey: Long): ActiveSession {
        val k = nextKey(s)
        return s.mapEx(exKey) { ex ->
            val last = ex.sets.lastOrNull { it.type != "drop" } ?: ex.sets.lastOrNull()
            ex.copy(sets = ex.sets + ActiveSet(k, "working", last?.weight, last?.reps))
        }
    }

    /** A drop set chained after the exercise's last set: ~25% lighter, rounded DOWN to the
     *  exercise's weight step (a lighter pin you can actually select). */
    fun addDrop(s: ActiveSession, exKey: Long, increment: Double): ActiveSession {
        val k = nextKey(s)
        return s.mapEx(exKey) { ex ->
            if (ex.sets.isEmpty()) return@mapEx ex
            val from = ex.sets.last()
            val w = from.weight?.let { roundDown(it * 0.75, increment) }?.takeIf { it > 0 }
            ex.copy(sets = ex.sets + ActiveSet(k, "drop", w, null, dropOf = ex.sets.size))
        }
    }

    fun roundDown(weight: Double, increment: Double): Double {
        if (increment <= 0) return weight
        return Math.round(floor(weight / increment + 1e-9) * increment * 100) / 100.0
    }

    fun removeSet(s: ActiveSession, exKey: Long, setKey: Long) = s.mapEx(exKey) { ex ->
        val i = ex.sets.indexOfFirst { it.key == setKey }
        if (i < 0) return@mapEx ex
        val kept = ex.sets.filterIndexed { j, _ -> j != i }
        // re-point drop chains: indices after the removed set shift down one; a drop whose
        // parent was removed chains to the set before it (or stops being a drop)
        ex.copy(sets = kept.map { set ->
            val d = set.dropOf ?: return@map set
            val parent = when {
                d - 1 == i -> i            // parent removed → the one before it (1-based i)
                d - 1 > i -> d - 1
                else -> d
            }
            if (parent < 1) set.copy(type = "working", dropOf = null) else set.copy(dropOf = parent)
        })
    }

    fun editSet(
        s: ActiveSession, exKey: Long, setKey: Long,
        weight: Double? = null, reps: Int? = null, type: String? = null,
        clearWeight: Boolean = false, clearReps: Boolean = false,
    ) = s.mapEx(exKey) { ex ->
        ex.copy(sets = ex.sets.map { set ->
            if (set.key != setKey) set else set.copy(
                weight = if (clearWeight) null else weight ?: set.weight,
                reps = if (clearReps) null else reps ?: set.reps,
                type = type ?: set.type,
                dropOf = if (type != null && type != "drop") null else set.dropOf,
            )
        })
    }

    fun editExercise(s: ActiveSession, exKey: Long, note: String? = null, restS: Int? = null) =
        s.mapEx(exKey) { it.copy(note = note ?: it.note, restS = restS ?: it.restS) }

    /** Tick/untick a set, then re-judge that exercise's PRs. A set can only be ticked with
     *  reps in it (weight may be empty — bodyweight). */
    fun toggleDone(s: ActiveSession, exKey: Long, setKey: Long, info: ExerciseInfo?): ActiveSession {
        val ticked = s.mapEx(exKey) { ex ->
            ex.copy(sets = ex.sets.map {
                if (it.key == setKey && (it.done || (it.reps ?: 0) > 0)) it.copy(done = !it.done) else it
            })
        }
        return rejudge(ticked, exKey, info)
    }

    /** PR kinds for every ticked set of one exercise, in list order, compounding — exactly
     *  the brain's ingest loop (first set of a never-done exercise has nothing to beat). */
    fun rejudge(s: ActiveSession, exKey: Long, info: ExerciseInfo?): ActiveSession = s.mapEx(exKey) { ex ->
        val assisted = info?.assisted ?: false
        var running: Bests? = info?.bests?.takeUnless { PrRules.isEmpty(it) }
        ex.copy(sets = ex.sets.map { set ->
            val reps = set.reps ?: 0
            if (!set.done || reps <= 0) return@map set.copy(prs = emptyList())
            val kinds = running?.let { PrRules.newPrs(it, set.weight, reps, set.type, assisted) }.orEmpty()
            running = PrRules.absorb(running ?: Bests(), set.weight, reps, set.type, assisted)
            set.copy(prs = kinds)
        })
    }

    // --- finishing ---------------------------------------------------------------------

    /** The POST body: ticked sets only, exercises with none dropped. Drop chains are
     *  re-pointed to the SENT numbering (unticked sets vanish, shifting indices); a drop
     *  whose parent wasn't ticked chains to the nearest earlier ticked set, else becomes a
     *  plain working set — never a drop_of the brain would reject. */
    fun toBody(s: ActiveSession, endedAt: String): SessionBody? {
        val exercises = s.exercises.mapNotNull { ex ->
            val newIndex = HashMap<Int, Int>()  // original 1-based → sent 1-based
            val sent = mutableListOf<SessionSetBody>()
            ex.sets.forEachIndexed { i, set ->
                val reps = set.reps ?: 0
                if (!set.done || reps <= 0) return@forEachIndexed
                var type = set.type
                var dropOf: Int? = null
                if (type == "drop") {
                    val parent = (set.dropOf ?: i).let { p -> (p downTo 1).firstOrNull { it in newIndex } }
                    if (parent == null) type = "working" else dropOf = newIndex.getValue(parent)
                }
                sent += SessionSetBody(type, reps, set.weight, dropOf)
                newIndex[i + 1] = sent.size
            }
            if (sent.isEmpty()) null
            else SessionExerciseBody(ex.exercise, ex.superset, ex.note, sent)
        }
        if (exercises.isEmpty()) return null
        return SessionBody(s.uid, s.routineId, s.title, s.startedAt, endedAt, s.note, exercises)
    }

    /** The finished session folded into the cached state: each exercise's "last" becomes
     *  these sets, bests absorb them, and its target is cleared — it was computed from
     *  history this session just superseded, so the next prefill falls back to these sets
     *  until the brain recomputes on the next sync. */
    fun foldIntoState(state: GymState, body: SessionBody): GymState {
        val exercises = state.exercises.toMutableMap()
        for (ex in body.exercises) {
            val cur = exercises[ex.exercise] ?: ExerciseInfo()
            var b = cur.bests
            for (set in ex.sets) b = PrRules.absorb(b, set.weight, set.reps, set.type, cur.assisted)
            exercises[ex.exercise] = cur.copy(
                last = LastSession(body.startedAt.take(10), ex.sets.map { LastSet(it.weight, it.reps, it.type) }),
                bests = b, target = null,
            )
        }
        val stale = body.exercises.map { it.exercise }.toSet()
        fun clear(r: Routine) = r.copy(body = r.body.map { if (it.exercise in stale) it.copy(target = null) else it })
        return state.copy(
            exercises = exercises,
            routines = state.routines.map(::clear),
            card = state.card?.let(::clear),
        )
    }
}
