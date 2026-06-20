package io.github.garemat.crumpet.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.garemat.crumpet.data.AgendaItem
import io.github.garemat.crumpet.data.ChatLine
import io.github.garemat.crumpet.data.HealthSnapshot
import io.github.garemat.crumpet.data.InFrame
import io.github.garemat.crumpet.data.Prefs
import io.github.garemat.crumpet.health.CalendarRepo
import io.github.garemat.crumpet.health.HealthRepo
import io.github.garemat.crumpet.net.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = Prefs(app)
    val health = HealthRepo(app)
    val calendar = CalendarRepo(app)

    val serverUrl: StateFlow<String> =
        prefs.serverUrl.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val token: StateFlow<String> =
        prefs.token.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val connected: StateFlow<Boolean> = Net.connected

    private val _snapshot = MutableStateFlow(HealthSnapshot())
    val snapshot = _snapshot.asStateFlow()
    private val _agenda = MutableStateFlow<List<AgendaItem>>(emptyList())
    val agenda = _agenda.asStateFlow()
    private val _chat = MutableStateFlow<List<ChatLine>>(emptyList())
    val chat = _chat.asStateFlow()
    private val _thinking = MutableStateFlow(false)
    val thinking = _thinking.asStateFlow()

    val paired: Boolean get() = serverUrl.value.isNotBlank() && token.value.isNotBlank()

    init {
        viewModelScope.launch { Net.frames.collect(::handleFrame) }
        refresh()
    }

    fun refresh() = viewModelScope.launch {
        _snapshot.value = withContext(Dispatchers.IO) { runCatching { health.todaySnapshot() }.getOrDefault(HealthSnapshot()) }
        _agenda.value = withContext(Dispatchers.IO) { runCatching { calendar.upcoming(7) }.getOrDefault(emptyList()) }
    }

    fun syncNow() = viewModelScope.launch {
        withContext(Dispatchers.IO) {
            val (base, tok, wm) = prefs.config()
            if (base.isNotBlank() && tok.isNotBlank()) {
                runCatching {
                    val started = System.currentTimeMillis()
                    Net.ingest(base, tok, health.recordsSince(wm))
                    prefs.setWatermark(started)
                }
            }
        }
        refresh()
    }

    fun sendChat(text: String) {
        if (text.isBlank()) return
        _chat.update { it + ChatLine(fromCrumpet = false, text = text) }
        viewModelScope.launch { Net.send(text) }
    }

    fun savePairing(url: String, tokenValue: String) = viewModelScope.launch {
        prefs.setPairing(url, tokenValue)
    }

    private suspend fun handleFrame(f: InFrame) {
        when (f.type) {
            "reply", "push" -> f.text?.let { t -> _chat.update { it + ChatLine(true, t) } }
            "state" -> _thinking.value = f.value == "thinking"
            "history" -> f.messages?.let { hs ->
                _chat.value = hs.map { ChatLine(it.role == "crumpet", it.text, it.source) }
            }
        }
    }
}
