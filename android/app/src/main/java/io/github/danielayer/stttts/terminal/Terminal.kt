package io.github.danielayer.stttts.terminal

import io.zenoh.bytes.Encoding
import io.zenoh.keyexpr.KeyExpr
import io.zenoh.liveliness.LivelinessToken
import io.zenoh.pubsub.Subscriber
import org.json.JSONObject

class LockedException(val heldBy: String) : RuntimeException("Doublet is locked by $heldBy")

/**
 * The audio side of one doublet: attaches, sends presses and mic frames, receives
 * events, speech and text. Same steps, in the same order, as Terminal in switchboard_at_home/client.py.
 * Callbacks arrive on Zenoh threads.
 */
class Terminal(
    private val link: ZenohLink,
    val doublet: Doublet,
    name: String,
    onEvent: (JSONObject) -> Unit,
    onAudio: (pcm: ByteArray, rate: Int) -> Unit,
    onTranscript: (String) -> Unit,
    onReplyText: (String) -> Unit,
) {
    private val subs: List<Subscriber<Unit>>
    private val lockToken: String
    private val live: LivelinessToken
    private val controlKey: KeyExpr
    private val audioInKey: KeyExpr
    private val audioInEncoding = Encoding.ZENOH_BYTES.withSchema(Protocol.AUDIO_IN_ENCODING)

    /** Output rate announced at attach; each audio sample's encoding can override it. */
    @Volatile var audioOutRate: Int = Protocol.DEFAULT_OUT_RATE
        private set

    init {
        val k = doublet.keys
        val s = mutableListOf(
            link.subscribe(doublet.key("events")) { onEvent(Protocol.parse(it.payload.toString())) },
            link.subscribe(doublet.key("audio_out")) {
                onAudio(it.payload.toBytes(), Protocol.rateFromEncoding(it.encoding.toString(), audioOutRate))
            },
            // open to listen: the terminal can read its own transcript...
            link.subscribe(doublet.key("text_out")) {
                onTranscript(Protocol.parse(it.payload.toString()).optString("text"))
            },
        )
        // ...and the app's reply text (plain text on text_in), if the server listed that key
        k["text_in"]?.let { key -> s += link.subscribe(key) { onReplyText(it.payload.toString()) } }
        subs = s

        val reply = try {
            link.query(doublet.key("attach"), JSONObject().put("terminal", name))
        } catch (e: Exception) {
            subs.forEach { it.undeclare() }
            throw e
        }
        if (!reply.optBoolean("ok")) {
            subs.forEach { it.undeclare() }
            if (reply.optString("reason") == "locked") throw LockedException(reply.optString("held_by", "?"))
            throw RuntimeException("Attach refused: ${reply.optString("reason", reply.toString())}")
        }
        lockToken = reply.getString("terminal_token")
        audioOutRate = Protocol.rateFromEncoding(reply.optString("audio_out"))
        live = link.liveliness(doublet.key("live_term"))
        controlKey = link.keyExpr(doublet.key("control"))
        audioInKey = link.keyExpr(doublet.key("audio_in"))
    }

    fun press() {
        link.put(controlKey, """{"op":"press"}""".toByteArray(), Encoding.APPLICATION_JSON, lockToken)
    }

    /** One 20 ms frame (640 bytes) of 16 kHz mono 16-bit LE PCM. */
    fun sendAudio(frame: ByteArray) {
        link.put(audioInKey, frame, audioInEncoding, lockToken)
    }

    fun detach() {
        try {
            link.query(doublet.key("detach"), JSONObject().put("terminal_token", lockToken))
        } finally {
            live.undeclare()
            subs.forEach { it.undeclare() }
        }
    }

    /** Drop local resources without talking to the server (doublet already closed). */
    fun abandon() {
        runCatching { live.undeclare() }
        subs.forEach { runCatching { it.undeclare() } }
    }
}
