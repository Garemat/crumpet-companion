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
import io.github.garemat.crumpet.data.Prefs
import io.github.garemat.crumpet.gym.HistoryOps
import io.github.garemat.crumpet.gym.HistorySession
import io.github.garemat.crumpet.net.Net
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
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

    // --- history: queued sessions + the cached newest page + any older pages loaded ---
    private val older = MutableStateFlow<List<HistorySession>>(emptyList())
    private val _olderMore = MutableStateFlow<Boolean?>(null)  // null = not paged yet
    private val _historyNote = MutableStateFlow("")
    val historyNote: StateFlow<String> = _historyNote.asStateFlow()

    val history: StateFlow<List<HistorySession>> =
        combine(store.history, store.outbox, older) { page, box, old ->
            HistoryOps.merge(HistoryOps.pending(box), page.sessions + old)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val historyMore: StateFlow<Boolean> =
        combine(store.history, _olderMore) { page, o -> o ?: page.more }
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun loadOlder() = viewModelScope.launch {
        val last = history.value.lastOrNull { !it.pending } ?: return@launch
        val (base, token, _) = Prefs(getApplication()).config()
        runCatching { Net.gymHistory(base, token, before = last.at) }
            .onSuccess { p -> older.value = older.value + p.sessions; _olderMore.value = p.more; _historyNote.value = "" }
            .onFailure { _historyNote.value = "Couldn't reach Crumpet — older sessions need signal." }
    }

    private val _exerciseHistory = MutableStateFlow<Pair<String, List<HistorySession>>>("" to emptyList())
    val exerciseHistory: StateFlow<Pair<String, List<HistorySession>>> = _exerciseHistory.asStateFlow()

    /** One lift's sessions: from the brain (the whole history), else what's cached here. */
    fun loadExercise(name: String) = viewModelScope.launch {
        _exerciseHistory.value = name to HistoryOps.forExercise(history.value, name)
        val (base, token, _) = Prefs(getApplication()).config()
        runCatching { Net.gymHistory(base, token, limit = 50, exercise = name) }.onSuccess { p ->
            val queued = HistoryOps.forExercise(history.value.filter { it.pending }, name)
            _exerciseHistory.value = name to HistoryOps.merge(queued, p.sessions)
        }
    }

    fun replaceExercise(exKey: Long, rawName: String) {
        val name = Canon.lift(rawName)
        if (name.isNotBlank()) edit { SessionOps.replaceExercise(it, exKey, name, info(name)) }
    }

    fun loadLibrary() {
        if (_library.value.isNotEmpty()) return
        viewModelScope.launch { _library.value = store.library().exercises }
    }

    fun info(name: String): ExerciseInfo? = state.value.exercises[name]

    /** The weight step for drop sets: the user's own setting, else what the brain inferred
     *  shows up as their usual jumps — fall back to 2.5 kg. */
    fun increment(name: String): Double = info(name)?.increment ?: info(name)?.pref?.increment ?: 2.5

    val rest = io.github.garemat.crumpet.gym.RestTimer.rest
    fun restAdd(s: Int) = io.github.garemat.crumpet.gym.RestTimer.add(s)
    fun restSkip() = io.github.garemat.crumpet.gym.RestTimer.skip()

    fun dropOk(name: String): Boolean = info(name)?.pref?.dropOk ?: false

    fun start(routine: Routine, then: () -> Unit) = viewModelScope.launch { actions.start(routine); then() }
    fun startEmpty(then: () -> Unit) = viewModelScope.launch { actions.startEmpty(); then() }

    fun edit(f: (ActiveSession) -> ActiveSession) = viewModelScope.launch { actions.update(f) }

    fun addExercise(rawName: String) {
        val name = Canon.lift(rawName)
        if (name.isBlank()) return
        edit { SessionOps.addExercise(it, name, info(name)) }
    }

    /** Tick → PRs + the in-session adjustment (SessionOps), then rest if the set says so. */
    fun toggle(exKey: Long, setKey: Long, exercise: String) = viewModelScope.launch {
        actions.update { SessionOps.toggleDone(it, exKey, setKey, info(exercise)) }
        val s = active.value ?: return@launch
        val ex = s.exercises.firstOrNull { it.key == exKey } ?: return@launch
        val set = ex.sets.firstOrNull { it.key == setKey } ?: return@launch
        if (!set.done) return@launch
        val secs = SessionOps.restAfter(s, exKey, setKey) ?: run { restSkip(); return@launch }
        io.github.garemat.crumpet.gym.RestTimer.start(secs, nextLabel(s, exKey, setKey))
    }

    /** "Chest Press (Machine) · set 3" — what the rest is before, for the notification. */
    private fun nextLabel(s: ActiveSession, exKey: Long, setKey: Long): String {
        val ex = s.exercises.first { it.key == exKey }
        val i = ex.sets.indexOfFirst { it.key == setKey }
        val nextHere = ex.sets.drop(i + 1).firstOrNull { !it.done }
        if (nextHere != null) return "${Canon.pretty(ex.exercise)} · set ${ex.sets.indexOf(nextHere) + 1}"
        val group = ex.superset?.let { tag -> s.exercises.firstOrNull { it.superset == tag && it.sets.any { x -> !x.done } } }
        val next = group ?: s.exercises.dropWhile { it.key != exKey }.drop(1).firstOrNull { it.sets.any { x -> !x.done } }
        return next?.let { Canon.pretty(it.exercise) } ?: "you're done"
    }

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
