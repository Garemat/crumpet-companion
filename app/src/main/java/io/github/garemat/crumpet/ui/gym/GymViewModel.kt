package io.github.garemat.crumpet.ui.gym

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.garemat.crumpet.gym.ActiveSession
import io.github.garemat.crumpet.gym.Canon
import io.github.garemat.crumpet.gym.ExerciseInfo
import io.github.garemat.crumpet.gym.ExercisePref
import io.github.garemat.crumpet.gym.GymActions
import io.github.garemat.crumpet.gym.GymStore
import io.github.garemat.crumpet.gym.GymSyncWorker
import io.github.garemat.crumpet.gym.LibraryExercise
import io.github.garemat.crumpet.gym.Routine
import io.github.garemat.crumpet.gym.SessionOps
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** The gym screens' state: the store's flows re-exposed, plus the screen actions. Every
 *  session edit goes straight to [GymActions.update] → persisted, so nothing is ever held
 *  only in Compose state. */
class GymViewModel(app: Application) : AndroidViewModel(app) {
    private val store = GymStore.get(app)
    private val actions = GymActions(app)

    val state = store.state
    val active = store.active
    val outbox = store.outbox
    val meta = store.meta

    private val _library = MutableStateFlow<List<LibraryExercise>>(emptyList())
    val library: StateFlow<List<LibraryExercise>> = _library.asStateFlow()

    /** Ticks once a second while a session runs — drives the elapsed clock. */
    private val _now = MutableStateFlow(System.currentTimeMillis())
    val now: StateFlow<Long> = _now.asStateFlow()

    init {
        viewModelScope.launch {
            while (isActive) { _now.value = System.currentTimeMillis(); delay(1000) }
        }
    }

    fun refresh() = GymSyncWorker.kick(getApplication())

    fun loadLibrary() {
        if (_library.value.isNotEmpty()) return
        viewModelScope.launch { _library.value = store.library().exercises }
    }

    fun info(name: String): ExerciseInfo? = state.value.exercises[name]

    /** The weight step for drop sets: the user's own setting, else what the brain inferred
     *  shows up as their usual jumps — fall back to 2.5 kg. */
    fun increment(name: String): Double = info(name)?.pref?.increment ?: 2.5

    fun dropOk(name: String): Boolean = info(name)?.pref?.dropOk ?: false

    fun start(routine: Routine, then: () -> Unit) = viewModelScope.launch { actions.start(routine); then() }
    fun startEmpty(then: () -> Unit) = viewModelScope.launch { actions.startEmpty(); then() }

    fun edit(f: (ActiveSession) -> ActiveSession) = viewModelScope.launch { actions.update(f) }

    fun addExercise(rawName: String) {
        val name = Canon.lift(rawName)
        if (name.isBlank()) return
        edit { SessionOps.addExercise(it, name, info(name)) }
    }

    fun toggle(exKey: Long, setKey: Long, exercise: String) =
        edit { SessionOps.toggleDone(it, exKey, setKey, info(exercise)) }

    /** Number edits re-judge PRs too — changing a ticked set's reps can make or unmake one. */
    fun setNumbers(exKey: Long, setKey: Long, exercise: String, weight: Double?, reps: Int?,
                   clearWeight: Boolean, clearReps: Boolean) = edit {
        SessionOps.rejudge(
            SessionOps.editSet(it, exKey, setKey, weight, reps, clearWeight = clearWeight, clearReps = clearReps),
            exKey, info(exercise))
    }

    fun setType(exKey: Long, setKey: Long, exercise: String, type: String) =
        edit { SessionOps.rejudge(SessionOps.editSet(it, exKey, setKey, type = type), exKey, info(exercise)) }

    fun finish(onDone: (Boolean) -> Unit) = viewModelScope.launch { onDone(actions.finish()) }
    fun discard(then: () -> Unit) = viewModelScope.launch { actions.discard(); then() }

    fun saveRoutine(r: Routine, then: () -> Unit = {}) = viewModelScope.launch { actions.saveRoutine(r); then() }
    fun deleteRoutine(id: String) = viewModelScope.launch { actions.deleteRoutine(id) }
    fun newRoutineId() = actions.newRoutineId()

    fun savePref(exercise: String, pref: ExercisePref) =
        viewModelScope.launch { actions.savePref(exercise, pref) }

    fun clearNote() = viewModelScope.launch { store.setMeta { it.copy(note = "") } }
}
