package io.github.garemat.crumpet.gym

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Wire shapes for the brain's /gym API (crumpet core/gym.py — don't drift; design in
// crumpet docs/backlog/gym-logger.md). Weights are always kg; times are local
// "YYYY-MM-DD HH:MM"; updated_at is epoch MILLIS from whoever wrote (last-write-wins).

@Serializable
data class GymTarget(
    val weight: Double? = null,   // null = bodyweight
    val reps: Int,
    val reason: String = "",      // start|progress|jump|reps|deload|crumpet
    val stalled: Boolean = false,
    @SerialName("carried_by") val carriedBy: String? = null,
)

@Serializable
data class RoutineSet(val type: String = "working", val reps: Int? = null, val weight: Double? = null)

@Serializable
data class RoutineExercise(
    val exercise: String,
    val superset: String? = null,
    @SerialName("rest_s") val restS: Int = 90,
    @SerialName("rep_min") val repMin: Int = 8,
    @SerialName("rep_max") val repMax: Int = 12,
    val sets: List<RoutineSet> = listOf(RoutineSet(), RoutineSet(), RoutineSet()),
    val note: String = "",
    val target: GymTarget? = null,  // brain-computed; never sent back
)

@Serializable
data class Routine(
    val id: String,
    val name: String,
    val position: Int = 0,
    val body: List<RoutineExercise> = emptyList(),
    @SerialName("updated_at") val updatedAt: Long = 0,
    val deleted: Boolean = false,
    val day: String? = null,      // Crumpet's day card only
    val status: String? = null,   // card: planned|done|skipped
)

@Serializable
data class LastSet(val weight: Double? = null, val reps: Int, val type: String = "working")

@Serializable
data class LastSession(val day: String, val sets: List<LastSet>)

@Serializable
data class Bests(
    @SerialName("max_weight") val maxWeight: Double? = null,
    @SerialName("best_e1rm") val bestE1rm: Double? = null,
    @SerialName("best_volume") val bestVolume: Double? = null,
    @SerialName("reps_at") val repsAt: Map<String, Int> = emptyMap(),
)

@Serializable
data class ExercisePref(
    val note: String = "",
    val increment: Double? = null,
    @SerialName("drop_ok") val dropOk: Boolean? = null,
    @SerialName("rest_s") val restS: Int? = null,
    @SerialName("updated_at") val updatedAt: Long = 0,
)

@Serializable
data class ExerciseInfo(
    val family: String = "",
    val assisted: Boolean = false,
    val last: LastSession? = null,
    val bests: Bests = Bests(),
    val target: GymTarget? = null,
    val pref: ExercisePref = ExercisePref(),
)

@Serializable
data class GymState(
    @SerialName("server_time") val serverTime: Long = 0,
    val routines: List<Routine> = emptyList(),
    val card: Routine? = null,
    val exercises: Map<String, ExerciseInfo> = emptyMap(),
)

@Serializable
data class LibraryExercise(
    val id: String,
    val name: String,
    val category: String = "",
    val force: String = "",
    val level: String = "",
    val mechanic: String = "",
    val equipment: String = "",
    @SerialName("primary_muscles") val primaryMuscles: String = "",
    @SerialName("secondary_muscles") val secondaryMuscles: String = "",
    val instructions: String = "",
)

@Serializable
data class Library(val exercises: List<LibraryExercise> = emptyList())

// --- the in-progress session (phone-only until finished) ---------------------------

@Serializable
data class ActiveSet(
    val key: Long,                 // stable identity for the UI list
    val type: String = "working",  // warmup|working|drop|failure
    val weight: Double? = null,
    val reps: Int? = null,
    val done: Boolean = false,
    val dropOf: Int? = null,       // 1-based index of the set this drop chains from
    val prs: List<String> = emptyList(),  // PR kinds this set set (when ticked)
)

@Serializable
data class ActiveExercise(
    val key: Long,
    val exercise: String,
    val superset: String? = null,
    val restS: Int = 90,
    val repMin: Int = 8,
    val repMax: Int = 12,
    val note: String = "",
    val sets: List<ActiveSet> = emptyList(),
    val target: GymTarget? = null,  // what the rows were pre-filled from (shown in the header)
)

@Serializable
data class ActiveSession(
    val uid: String,
    val routineId: String? = null,
    val title: String,
    val startedAt: String,         // local "YYYY-MM-DD HH:MM"
    val startedMillis: Long,
    val note: String = "",
    val exercises: List<ActiveExercise> = emptyList(),
)

// --- what goes over the wire on finish --------------------------------------------

@Serializable
data class SessionSetBody(
    val type: String,
    val reps: Int,
    val weight: Double? = null,
    @SerialName("drop_of") val dropOf: Int? = null,
)

@Serializable
data class SessionExerciseBody(
    val exercise: String,
    val superset: String? = null,
    val note: String = "",
    val sets: List<SessionSetBody>,
)

@Serializable
data class SessionBody(
    val uid: String,
    @SerialName("routine_id") val routineId: String? = null,
    val title: String,
    @SerialName("started_at") val startedAt: String,
    @SerialName("ended_at") val endedAt: String,
    val note: String = "",
    val exercises: List<SessionExerciseBody>,
)

@Serializable
data class SessionResult(
    val ok: Boolean = false,
    val replay: Boolean = false,
    val feedback: String = "",
)

/** One queued write to the brain, drained in order. method+path+body is all a drain needs,
 *  so new write kinds never need new outbox code. [label] is for the UI ("Push day"). */
@Serializable
data class GymOutboxItem(
    val id: Long,
    val method: String,   // POST | PUT | DELETE
    val path: String,     // e.g. /gym/sessions
    val body: String = "",
    val label: String = "",
)
