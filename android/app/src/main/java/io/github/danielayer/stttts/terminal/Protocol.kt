package io.github.danielayer.stttts.terminal

import org.json.JSONObject

/**
 * The switchboard-at-home doublet protocol (VERSION 2), terminal side.
 * Mirrors switchboard_at_home/protocol.py. Key names always come from the server; only the
 * two service-level keys (list) are built here, from the prefix.
 */
object Protocol {
    const val VERSION = 2
    const val DEFAULT_PREFIX = "sah"
    const val AUDIO_IN_ENCODING = "audio/L16;rate=16000"
    const val AUDIO_IN_RATE = 16000
    const val FRAME_BYTES = 640                 // 20 ms at 16 kHz, mono, 16-bit
    const val DEFAULT_OUT_RATE = 24000

    fun listKey(prefix: String) = "$prefix/doublets"

    /** "audio/L16;rate=24000" (or "unknown(0);audio/L16;rate=24000") -> 24000. */
    fun rateFromEncoding(encoding: String, default: Int = DEFAULT_OUT_RATE): Int =
        Regex("""rate=(\d+)""").find(encoding)?.groupValues?.get(1)?.toIntOrNull() ?: default

    fun parse(text: String): JSONObject =
        try { if (text.isBlank()) JSONObject() else JSONObject(text) } catch (e: Exception) { JSONObject() }
}

/** One entry from `<p>/doublets`. */
data class Doublet(
    val id: String,
    val name: String,
    val terminal: String?,          // null when free
    val state: String,
    val keys: Map<String, String>,
) {
    fun key(name: String): String =
        keys[name] ?: throw IllegalStateException("Server gave no '$name' key for doublet $id")

    companion object {
        fun fromJson(o: JSONObject): Doublet {
            val k = o.optJSONObject("keys") ?: JSONObject()
            return Doublet(
                id = o.optString("id"),
                name = o.optString("name"),
                terminal = if (o.isNull("terminal")) null else o.optString("terminal"),
                state = o.optString("state", "idle"),
                keys = k.keys().asSequence().associateWith { k.getString(it) },
            )
        }
    }
}
