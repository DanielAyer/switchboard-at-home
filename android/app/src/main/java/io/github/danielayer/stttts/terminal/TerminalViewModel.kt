package io.github.danielayer.stttts.terminal

import android.app.Application
import android.content.Context
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class UiState(
    val endpoint: String,
    val prefix: String,
    val connected: Boolean = false,
    val busy: Boolean = false,
    val doublets: List<Doublet> = emptyList(),
    val attached: Doublet? = null,
    val state: String = "idle",          // idle | listening | thinking | speaking
    val partial: String = "",
    val transcript: String = "",
    val reply: String = "",
    val log: List<String> = emptyList(),
    val error: String? = null,
)

class TerminalViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("terminal", Context.MODE_PRIVATE)
    private val _ui = MutableStateFlow(UiState(
        endpoint = prefs.getString("endpoint", "tcp/nidavellir:7447")!!,
        prefix = prefs.getString("prefix", Protocol.DEFAULT_PREFIX)!!,
    ))
    val ui: StateFlow<UiState> = _ui

    private var link: ZenohLink? = null
    @Volatile private var terminal: Terminal? = null
    private val speaker = Speaker()
    private val mic = Mic { frame -> sendFrame(frame) }
    private val terminalName = "phone (${Build.MODEL})"

    // --- actions from the UI ---------------------------------------------------------------

    fun setEndpoint(v: String) = _ui.update { it.copy(endpoint = v) }
    fun setPrefix(v: String) = _ui.update { it.copy(prefix = v) }
    fun clearError() = _ui.update { it.copy(error = null) }

    fun connect() = io("connect") {
        val s = _ui.value
        prefs.edit().putString("endpoint", s.endpoint.trim()).putString("prefix", s.prefix.trim()).apply()
        link = ZenohLink.open(s.endpoint)
        _ui.update { it.copy(connected = true) }
        log("connected to ${s.endpoint}")
        loadDoublets()
    }

    fun disconnect() = io("disconnect") {
        dropTerminal(tellServer = true)
        link?.close()
        link = null
        _ui.update { it.copy(connected = false, doublets = emptyList()) }
        log("disconnected")
    }

    fun refresh() = io("refresh") { loadDoublets() }

    /** Caller must already hold RECORD_AUDIO. */
    fun attach(d: Doublet) = io("attach") {
        val l = link ?: error("Not connected")
        val t = try {
            Terminal(l, d, terminalName,
                onEvent = ::onEvent,
                onAudio = { pcm, rate -> speaker.enqueue(pcm, rate) },
                onTranscript = { text -> _ui.update { it.copy(transcript = text, partial = "") } },
                onReplyText = { text -> _ui.update { it.copy(reply = text) } })
        } catch (e: LockedException) {
            loadDoublets()
            throw RuntimeException("${d.id} is in use by ${e.heldBy}")
        }
        terminal = t
        _ui.update { it.copy(attached = d, state = d.state, partial = "", transcript = "", reply = "") }
        log("attached to ${d.id} (audio out ${t.audioOutRate} Hz)")
        applyState(d.state)
    }

    fun detach() = io("detach") {
        dropTerminal(tellServer = true)
        loadDoublets()
    }

    /** The one context-sensitive button. */
    fun press() = io("press") {
        val t = terminal ?: return@io
        if (_ui.value.state == "speaking") speaker.flush()   // don't wait for the event to go quiet
        t.press()
    }

    // --- internals ------------------------------------------------------------------------

    private fun loadDoublets() {
        val l = link ?: return
        val reply = l.query(Protocol.listKey(_ui.value.prefix))
        val arr = reply.optJSONArray("doublets")
        val list = (0 until (arr?.length() ?: 0)).map { Doublet.fromJson(arr!!.getJSONObject(it)) }
        _ui.update { it.copy(doublets = list) }
    }

    private fun onEvent(ev: JSONObject) {
        val name = ev.optString("event")
        val state = ev.optString("state", _ui.value.state)
        when (name) {
            "interrupted", "reply_cancelled" -> speaker.flush()
            "partial" -> _ui.update { it.copy(partial = ev.optString("text")) }
            "listening" -> _ui.update { it.copy(partial = "") }
            "closed", "released" -> {
                log("$name: ${ev.optString("reason")}")
                io("cleanup") { dropTerminal(tellServer = false); loadDoublets() }
                return
            }
        }
        if (name != "partial") log(name + extraOf(ev))
        _ui.update { it.copy(state = state) }
        applyState(state)
    }

    /** Half-duplex: the mic runs only while Listening. */
    private fun applyState(state: String) {
        if (state == "listening" && terminal != null) {
            try { mic.start() } catch (e: Exception) { fail("mic", e) }
        } else {
            mic.stop()
        }
    }

    private fun sendFrame(frame: ByteArray) {
        val t = terminal ?: return
        if (_ui.value.state != "listening") return
        try { t.sendAudio(frame) } catch (e: Exception) { /* drop the frame; events will tell */ }
    }

    private fun dropTerminal(tellServer: Boolean) {
        val t = terminal ?: return
        terminal = null
        mic.stop()
        speaker.flush()
        if (tellServer) runCatching { t.detach() } else t.abandon()
        _ui.update { it.copy(attached = null, state = "idle", partial = "") }
        log("detached from ${t.doublet.id}")
    }

    private fun extraOf(ev: JSONObject): String = when {
        ev.has("spoken_text") -> ": \"${ev.optString("spoken_text")}\""
        ev.has("reason") -> ": ${ev.optString("reason")}"
        else -> ""
    }

    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)
    private fun log(line: String) =
        _ui.update { it.copy(log = (listOf("${clock.format(Date())}  $line") + it.log).take(60)) }

    private fun fail(what: String, e: Throwable) {
        val msg = e.message ?: e.javaClass.simpleName
        log("$what failed: $msg")
        _ui.update { it.copy(error = msg) }
    }

    private fun io(what: String, block: suspend () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            _ui.update { it.copy(busy = true) }
            try { block() } catch (e: Exception) { fail(what, e) }
            finally { _ui.update { it.copy(busy = false) } }
        }
    }

    override fun onCleared() {
        terminal?.let { t -> runCatching { t.detach() } }
        terminal = null
        mic.stop()
        speaker.release()
        link?.close()
    }
}
