package io.github.garemat.crumpet.gym

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URLEncoder
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/** What the gym screens do. Every write is local-first: the cached state changes now (the
 *  UI never waits on the network), the matching brain write joins the outbox, and a sync
 *  is kicked. LWW stamps are this phone's clock in epoch millis. */
class GymActions(private val context: Context) {
    private val store = GymStore.get(context)
    private val json = Json { encodeDefaults = true }

    private fun now() = LocalDateTime.now().format(FMT)
    private fun kick() = GymSyncWorker.kick(context)

    suspend fun start(routine: Routine) {
        store.setActive(SessionOps.fromRoutine(routine, store.state.value.exercises,
            UUID.randomUUID().toString(), now(), System.currentTimeMillis()))
    }

    suspend fun startEmpty(title: String = "Workout") {
        store.setActive(SessionOps.empty(UUID.randomUUID().toString(), title, now(),
            System.currentTimeMillis()))
    }

    /** Persist an edit to the in-progress session (the screens call this on every change). */
    suspend fun update(f: (ActiveSession) -> ActiveSession) {
        store.active.value?.let { store.setActive(f(it)) }
    }

    suspend fun discard() = store.setActive(null)

    /** Finish: the ticked sets go to the outbox and are folded into the cache (so the next
     *  session's "previous" column and pre-fill are right even before the brain hears).
     *  Returns false when nothing was ticked — the caller asks whether to discard. */
    suspend fun finish(): Boolean {
        val session = store.active.value ?: return false
        val body = SessionOps.toBody(session, now()) ?: return false
        store.enqueue("POST", "/gym/sessions", json.encodeToString(body), session.title)
        store.updateState { SessionOps.foldIntoState(it, body) }
        store.setActive(null)
        kick()
        return true
    }

    suspend fun saveRoutine(routine: Routine) {
        val stamped = routine.copy(updatedAt = System.currentTimeMillis(), deleted = false)
        store.updateState { s ->
            val others = s.routines.filterNot { it.id == stamped.id }
            s.copy(routines = (others + stamped).sortedBy { it.position })
        }
        val body = RoutinePut(stamped.name, stamped.position, stamped.body.map { it.copy(target = null) },
            stamped.updatedAt)
        store.enqueue("PUT", "/gym/routines/${stamped.id}", json.encodeToString(body), stamped.name)
        kick()
    }

    suspend fun deleteRoutine(id: String) {
        val at = System.currentTimeMillis()
        val name = store.state.value.routines.firstOrNull { it.id == id }?.name ?: "routine"
        store.updateState { s ->
            s.copy(routines = s.routines.map { if (it.id == id) it.copy(deleted = true, updatedAt = at) else it })
        }
        store.enqueue("DELETE", "/gym/routines/$id?updated_at=$at", label = name)
        kick()
    }

    suspend fun savePref(exercise: String, pref: ExercisePref) {
        val stamped = pref.copy(updatedAt = System.currentTimeMillis())
        store.updateState { s ->
            val cur = s.exercises[exercise] ?: ExerciseInfo()
            s.copy(exercises = s.exercises + (exercise to cur.copy(pref = stamped)))
        }
        val path = "/gym/exercise-prefs/" + URLEncoder.encode(exercise, "UTF-8").replace("+", "%20")
        store.enqueue("PUT", path, json.encodeToString(stamped), exercise)
        kick()
    }

    fun newRoutineId(): String = "r-" + UUID.randomUUID().toString().replace("-", "").take(16)

    @Serializable
    private data class RoutinePut(
        val name: String,
        val position: Int,
        val body: List<RoutineExercise>,
        @kotlinx.serialization.SerialName("updated_at") val updatedAt: Long,
    )

    companion object {
        private val FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    }
}
