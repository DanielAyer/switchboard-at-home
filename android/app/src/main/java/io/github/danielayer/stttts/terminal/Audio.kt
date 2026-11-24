package io.github.danielayer.stttts.terminal

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Mic: 16 kHz mono 16-bit, delivered as 640-byte (20 ms) little-endian frames. */
class Mic(private val onFrame: (ByteArray) -> Unit) {
    @Volatile private var running = false
    private var thread: Thread? = null

    /** Caller must hold RECORD_AUDIO. */
    @SuppressLint("MissingPermission")
    @Synchronized
    fun start() {
        if (running) return
        val minBuf = AudioRecord.getMinBufferSize(
            Protocol.AUDIO_IN_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        // VOICE_COMMUNICATION turns on the phone's own echo canceller / noise suppressor.
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION, Protocol.AUDIO_IN_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, Protocol.FRAME_BYTES * 8))
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            throw IllegalStateException("Microphone could not be opened")
        }
        running = true
        rec.startRecording()
        thread = Thread({
            val samples = ShortArray(Protocol.FRAME_BYTES / 2)
            try {
                while (running) {
                    var got = 0
                    while (running && got < samples.size) {
                        val n = rec.read(samples, got, samples.size - got)
                        if (n < 0) { running = false; break }
                        got += n
                    }
                    if (got < samples.size) break
                    val frame = ByteArray(Protocol.FRAME_BYTES)
                    for (i in samples.indices) {
                        val v = samples[i].toInt()
                        frame[2 * i] = (v and 0xFF).toByte()
                        frame[2 * i + 1] = ((v shr 8) and 0xFF).toByte()
                    }
                    onFrame(frame)
                }
            } finally {
                rec.stop()
                rec.release()
            }
        }, "mic").apply { start() }
    }

    @Synchronized
    fun stop() {
        running = false
        thread?.join(500)
        thread = null
    }
}

/**
 * Speaker: plays the server's PCM as it arrives. The server paces audio in real time with
 * ~300 ms lead, so a small queue is enough. flush() drops everything (on `interrupted`).
 */
class Speaker {
    private val queue = LinkedBlockingQueue<Pair<ByteArray, Int>>()
    @Volatile private var running = true
    @Volatile private var generation = 0
    private var track: AudioTrack? = null
    private var trackRate = 0
    private val lock = Object()

    private val worker = Thread({
        while (running) {
            val (pcm, rate) = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
            val gen = generation
            val t = synchronized(lock) { trackFor(rate) }
            var off = 0
            while (running && off < pcm.size && gen == generation) {
                val n = t.write(pcm, off, minOf(pcm.size - off, 4096))
                if (n <= 0) break
                off += n
            }
        }
    }, "speaker").apply { start() }

    fun enqueue(pcm: ByteArray, rate: Int) {
        if (pcm.isNotEmpty()) queue.offer(pcm to rate)
    }

    fun flush() {
        generation++
        queue.clear()
        synchronized(lock) {
            track?.let { it.pause(); it.flush(); it.play() }
        }
    }

    private fun trackFor(rate: Int): AudioTrack {
        track?.let { if (trackRate == rate) return it }
        track?.release()
        val minBuf = AudioTrack.getMinBufferSize(
            rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build())
            .setAudioFormat(AudioFormat.Builder()
                .setSampleRate(rate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build())
            .setBufferSizeInBytes(maxOf(minBuf, rate * 2 / 5))   // ~200 ms
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        t.play()
        track = t
        trackRate = rate
        return t
    }

    fun release() {
        running = false
        queue.clear()
        worker.join(500)
        synchronized(lock) { track?.release(); track = null }
    }
}
