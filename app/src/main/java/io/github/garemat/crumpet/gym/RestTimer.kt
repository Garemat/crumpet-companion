package io.github.garemat.crumpet.gym

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The rest countdown — one per process, shared by the session screen (its bar) and
 *  [GymSessionService] (the lock-screen notification + the alert at zero). Wall-clock
 *  based (endsAt), so a frozen UI or a dozing CPU can't make it drift. */
object RestTimer {
    data class Rest(val endsAt: Long, val totalS: Int, val label: String)

    private val _rest = MutableStateFlow<Rest?>(null)
    val rest: StateFlow<Rest?> = _rest.asStateFlow()

    fun start(seconds: Int, label: String, now: Long = System.currentTimeMillis()) {
        _rest.value = Rest(now + seconds * 1000L, seconds, label)
    }

    /** ±15s from the screen or the notification; never below "ends now". */
    fun add(seconds: Int, now: Long = System.currentTimeMillis()) {
        val r = _rest.value ?: return
        val ends = maxOf(now, r.endsAt + seconds * 1000L)
        _rest.value = r.copy(endsAt = ends, totalS = maxOf(1, r.totalS + seconds))
    }

    fun skip() { _rest.value = null }

    fun remainingS(now: Long = System.currentTimeMillis()): Int? =
        _rest.value?.let { ((it.endsAt - now + 999) / 1000).toInt().coerceAtLeast(0) }
}
