package io.github.danielayer.stttts.terminal

import io.zenoh.Config
import io.zenoh.Session
import io.zenoh.Zenoh
import io.zenoh.bytes.Encoding
import io.zenoh.bytes.ZBytes
import io.zenoh.handlers.Callback
import io.zenoh.keyexpr.KeyExpr
import io.zenoh.liveliness.LivelinessToken
import io.zenoh.pubsub.Subscriber
import io.zenoh.query.intoSelector
import io.zenoh.sample.Sample
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/** A thin wrapper over a zenoh-kotlin 1.10 client session: just what a terminal needs. */
class ZenohLink private constructor(private val session: Session) : AutoCloseable {

    companion object {
        /** Opens a client session to one router endpoint, e.g. "tcp/192.168.1.20:7447". */
        fun open(endpoint: String): ZenohLink {
            val conf = JSONObject()
                .put("mode", "client")
                .put("connect", JSONObject().put("endpoints", JSONArray().put(endpoint.trim())))
                .put("scouting", JSONObject().put("multicast", JSONObject().put("enabled", false)))
            val config = Config.fromJson5(conf.toString()).getOrThrow()
            val session = Zenoh.open(config).getOrElse {
                throw RuntimeException("Could not connect to Zenoh at $endpoint: ${it.message}", it)
            }
            return ZenohLink(session)
        }

        private val NO_REPLY = Any()
    }

    /** JSON query -> first OK JSON reply (like _get_json in client.py). */
    fun query(key: String, payload: JSONObject = JSONObject(), timeoutMs: Long = 5000): JSONObject {
        val box = ArrayBlockingQueue<Any>(2)
        session.get(
            key.intoSelector().getOrThrow(),
            callback = Callback { reply ->
                reply.result.onSuccess { sample -> box.offer(sample.payload.toString()) }
            },
            payload = ZBytes.from(payload.toString()),
            encoding = Encoding.APPLICATION_JSON,
            timeout = Duration.ofMillis(timeoutMs),
            onClose = { box.offer(NO_REPLY) },
        ).getOrThrow()
        val first = box.poll(timeoutMs + 1000, TimeUnit.MILLISECONDS)
        if (first !is String) {
            throw RuntimeException("No answer on $key. Is `sah serve` running and reachable?")
        }
        return Protocol.parse(first)
    }

    fun subscribe(key: String, onSample: (Sample) -> Unit): Subscriber<Unit> =
        session.declareSubscriber(KeyExpr.tryFrom(key).getOrThrow(), callback = Callback { onSample(it) })
            .getOrThrow()

    fun keyExpr(key: String): KeyExpr = session.declareKeyExpr(key).getOrThrow()

    fun put(key: KeyExpr, payload: ByteArray, encoding: Encoding, attachment: String) {
        session.put(key, ZBytes.from(payload), encoding = encoding, attachment = ZBytes.from(attachment))
            .getOrThrow()
    }

    fun liveliness(key: String): LivelinessToken =
        session.liveliness().declareToken(KeyExpr.tryFrom(key).getOrThrow()).getOrThrow()

    override fun close() {
        session.close()
    }
}
