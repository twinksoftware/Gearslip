package app.seb3thehacker.gearslip.speech

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import app.seb3thehacker.gearslip.GearslipLog
import app.seb3thehacker.gearslip.audio.CarMic
import app.seb3thehacker.gearslip.car.CarSettings
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineCanaryModelConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.max

/**
 * Gearslip's own speech-to-text, so voice works on phones with no speech service (GrapheneOS ships
 * none). sherpa-onnx runs a streaming model ([SpeechModels]) on the phone: words come out while
 * the driver is still talking, and the model itself decides when they've finished. A larger model
 * then reads the whole phrase once more for the final words, since a model that waits for the end
 * hears names and places far better than one that has to keep up. The phrase is held in memory
 * for that and nowhere else, and dropped as soon as it's read.
 *
 * Audio comes from the car's microphone, or the phone's when the car has none or sends nothing.
 * Everything runs on one worker thread; callbacks arrive on the main thread.
 */
object SpeechEngine {

    /** The error for an engine that can't run at all, which callers may answer with a fallback. */
    const val NOT_LOADED = "The speech model didn't load."

    /** Said when the chosen model hasn't been downloaded yet. */
    const val NOT_DOWNLOADED = "Download a voice model in Gearslip's settings on the phone first."

    private const val RATE = 16_000
    private const val CHUNK = 1_600 // 100 ms
    private const val MAX_S = 15f
    /** Quiet before the first word that counts as "nobody spoke". */
    private const val NO_SPEECH_S = 6f
    /** Quiet after the last word that ends the phrase. */
    private const val END_SILENCE_S = 1.0f
    private const val CUE_VOLUME = 80
    private const val IDLE_UNLOAD_MS = 5 * 60_000L
    /** How long the car gets to start sending once asked, before the phone's microphone takes over. */
    private const val CAR_FIRST_AUDIO_MS = 1_500L
    private const val CAR_GAP_MS = 1_000L
    private const val CUE_MS = 150
    /** The car plays what the phone sends a moment late; waiting this out keeps the beep off the recording. */
    private const val CUE_TAIL_MS = 250L

    private val worker = Executors.newSingleThreadExecutor { Thread(it, "gearslip-speech") }
    /**
     * The final model takes seconds to load. On [worker], the first press after connecting
     * waited behind it before listening at all; here it loads while the driver talks.
     */
    private val finalWorker = Executors.newSingleThreadExecutor { Thread(it, "gearslip-speech-final") }
    private val finalLock = Object()
    private val main = Handler(Looper.getMainLooper())

    private var recognizer: OnlineRecognizer? = null
    private var loadedModel: SpeechModels.Model? = null
    @Volatile private var finalRecognizer: OfflineRecognizer? = null
    @Volatile private var loadedFinal: SpeechModels.Model? = null

    /**
     * The final model holds most of the memory the two models take, so it goes after a few
     * minutes without a command. Kroko stays: it's small, and keeps listening instant.
     */
    private val idleUnload = Runnable { unloadFinal("after ${IDLE_UNLOAD_MS / 60_000} minutes without use") }
    @Volatile private var run = 0

    /**
     * Loads the live model ahead of time, so the first command doesn't wait for it, and the final
     * one too when [final] is set.
     */
    fun warm(context: Context, final: Boolean = true) {
        val app = context.applicationContext
        SpeechModels.init(app)
        // Kroko first, alone: loading both at once slowed it from a fraction of a second to six.
        worker.execute {
            ensureLoaded(app)
            if (final) finalWorker.execute { ensureFinalLoaded(app) }
        }
    }

    private fun unloadFinal(why: String) {
        finalWorker.execute {
            synchronized(finalLock) {
                val model = loadedFinal ?: return@execute
                finalRecognizer?.release()
                finalRecognizer = null
                loadedFinal = null
                GearslipLog.i("speech: unloaded ${model.label} $why")
            }
        }
    }

    /** Drops the loaded models: the driver picked another or deleted one, or the car went away. */
    fun unload() {
        main.removeCallbacks(idleUnload)
        run++
        worker.execute {
            recognizer?.release()
            recognizer = null
            loadedModel = null
        }
        finalWorker.execute {
            synchronized(finalLock) {
                finalRecognizer?.release()
                finalRecognizer = null
                loadedFinal = null
            }
        }
    }

    /**
     * Listens for one phrase. [cue] plays a short tone first, so the driver knows when to talk.
     * [onPartial] gets the words so far as they're recognized, [onSpeaking] fires at the first
     * word, [onHeard] when the driver stops; [onResult] gets the text, [onError] a sentence fit to
     * show on the car screen.
     */
    fun listen(
        context: Context,
        cue: Boolean = true,
        onSpeaking: () -> Unit = {},
        onPartial: (String) -> Unit = {},
        onListening: () -> Unit = {},
        onHeard: () -> Unit,
        onResult: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        val app = context.applicationContext
        if (app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            onError("Needs the microphone permission.")
            return
        }
        val id = ++run
        fun post(block: () -> Unit) { main.post { if (id == run) block() } }
        val server = CarSettings.speechServer.value
        if (server.on) {
            worker.execute {
                if (id != run) return@execute
                VoiceFocus.acquire(app)
                try {
                    if (cue) cue()
                    if (id != run) return@execute
                    post(onListening)
                    val wav = record(id, onSpeaking = { post(onSpeaking) }) ?: return@execute post { onError("Didn't catch that.") }
                    if (id != run) return@execute
                    post(onHeard)
                    val text = ServerSpeech.transcribe(server, wav).let(::clean)
                    post { if (text.isEmpty()) onError("Didn't catch that.") else onResult(text) }
                } catch (e: ServerSpeech.Failed) {
                    post { onError(e.message ?: ServerSpeech.UNREACHABLE) }
                } catch (t: Throwable) {
                    GearslipLog.e("speech: listening failed", t)
                    post { onError("The microphone isn't available right now.") }
                } finally {
                    VoiceFocus.release()
                }
            }
            return
        }
        // Brings Parakeet back if it was unloaded, while the driver talks.
        main.removeCallbacks(idleUnload)
        finalWorker.execute { ensureFinalLoaded(app) }
        worker.execute {
            if (id != run) return@execute
            if (!SpeechModels.isReady(app, SpeechModels.selected.value)) return@execute post { onError(NOT_DOWNLOADED) }
            val r = ensureLoaded(app) ?: return@execute post { onError(NOT_LOADED) }
            VoiceFocus.acquire(app)
            val text = try {
                if (cue) cue()
                if (id != run) return@execute
                post(onListening)
                stream(app, r, id, onSpeaking = { post(onSpeaking) }, onPartial = { post { onPartial(it) } }, onStopped = { post(onHeard) })
            } catch (t: Throwable) {
                GearslipLog.e("speech: listening failed", t)
                return@execute post { onError("The microphone isn't available right now.") }
            } finally {
                VoiceFocus.release()
            }
            main.removeCallbacks(idleUnload)
            main.postDelayed(idleUnload, IDLE_UNLOAD_MS)
            if (id != run) return@execute
            post(onHeard)
            post { if (text.isEmpty()) onError("Didn't catch that.") else onResult(text) }
        }
    }

    /** Stops listening; a phrase already being worked out is dropped. */
    fun cancel() { run++ }

    /**
     * Feeds the microphone to the recognizer in 100 ms pieces, reporting the words as they firm
     * up, until the model hears the end of the phrase (or nobody speaks, or it runs too long).
     */
    private fun stream(
        context: Context,
        r: OnlineRecognizer,
        id: Int,
        onSpeaking: () -> Unit,
        onPartial: (String) -> Unit,
        onStopped: () -> Unit,
    ): String {
        val mic = carMic() ?: phoneMic()
        val s = r.createStream()
        val frame = ShortArray(CHUNK)
        val samples = FloatArray(CHUNK)
        // The whole phrase, for the final read; [MAX_S] and a little over is the most it can hold.
        val phrase = FloatArray((RATE * (MAX_S + 1)).toInt())
        var held = 0
        var last = ""
        var spoke = false
        var audioMs = 0L
        var decodeMs = 0L
        try {
            while (id == run) {
                val n = mic.read(frame)
                if (n < 0) break
                if (n == 0) continue
                for (i in 0 until n) samples[i] = frame[i] / 32768f
                val keep = minOf(n, phrase.size - held)
                System.arraycopy(samples, 0, phrase, held, keep)
                held += keep
                s.acceptWaveform(if (n == CHUNK) samples else samples.copyOf(n), RATE)
                audioMs += n * 1000L / RATE
                val started = SystemClock.uptimeMillis()
                while (r.isReady(s)) r.decode(s)
                decodeMs += SystemClock.uptimeMillis() - started
                val text = clean(r.getResult(s).text)
                if (text != last) {
                    last = text
                    if (text.isNotEmpty() && !spoke) {
                        spoke = true
                        onSpeaking()
                    }
                    onPartial(text)
                }
                if (r.isEndpoint(s)) break
            }
            s.inputFinished()
            while (r.isReady(s)) r.decode(s)
            last = clean(r.getResult(s).text)
        } finally {
            mic.close()
            s.release()
        }
        // Under 1.0 keeps up with the driver; over it, the words fall behind the voice.
        GearslipLog.i(
            "speech: $audioMs ms of audio, decoding took $decodeMs ms " +
                "(${"%.2f".format(decodeMs.toFloat() / max(1L, audioMs))} of real time)",
        )
        if (last.isEmpty() || id != run) { phrase.fill(0f); return last }
        // Still loading (just connected, or back after going idle): the live words now beat
        // Parakeet's in several seconds.
        val wanted = SpeechModels.final.value
        if (wanted == null || finalRecognizer == null || loadedFinal != wanted) {
            if (wanted != null) GearslipLog.i("speech: ${wanted.label} isn't loaded yet, keeping the live words")
            phrase.fill(0f)
            return last
        }
        onStopped()
        val started = SystemClock.uptimeMillis()
        // Held for the whole read, so a model swap or the idle unload can't free it mid-decode.
        val reread = synchronized(finalLock) {
            val final = finalRecognizer?.takeIf { loadedFinal == wanted } ?: run { phrase.fill(0f); return last }
            final.createStream().run {
                try {
                    acceptWaveform(phrase.copyOf(held), RATE)
                    final.decode(this)
                    clean(final.getResult(this).text)
                } finally {
                    release()
                }
            }
        }
        phrase.fill(0f)
        GearslipLog.i("speech: the final read took ${SystemClock.uptimeMillis() - started} ms")
        if (reread.isEmpty()) return last
        if (reread != last) onPartial(reread)
        return reread
    }

    /**
     * One phrase for the driver's own server, as 16 kHz mono WAV. With no model on the phone to
     * say when the driver has finished, loudness does: speech is anything well above the first
     * moments' background noise, and a second under it ends the phrase. Null when nobody spoke.
     */
    private fun record(id: Int, onSpeaking: () -> Unit): ByteArray? {
        val mic = carMic() ?: phoneMic()
        val frame = ShortArray(CHUNK)
        val pcm = java.io.ByteArrayOutputStream()
        var floor = -1.0
        var spoke = false
        var quietMs = 0L
        var heardMs = 0L
        try {
            while (id == run) {
                val n = mic.read(frame)
                if (n < 0) break
                if (n == 0) continue
                var sum = 0.0
                for (i in 0 until n) {
                    sum += frame[i].toDouble() * frame[i]
                    pcm.write(frame[i].toInt() and 0xFF)
                    pcm.write(frame[i].toInt() shr 8 and 0xFF)
                }
                val rms = Math.sqrt(sum / n)
                val ms = n * 1000L / RATE
                heardMs += ms
                // The quietest moment so far is the car's own hum.
                floor = if (floor < 0) rms else minOf(floor, rms)
                val loud = rms > maxOf(floor * SPEECH_OVER_NOISE, MIN_SPEECH_RMS)
                if (loud) {
                    quietMs = 0
                    if (!spoke) { spoke = true; onSpeaking() }
                } else {
                    quietMs += ms
                }
                if (spoke && quietMs >= (END_SILENCE_S * 1000).toLong()) break
                if (!spoke && heardMs >= (NO_SPEECH_S * 1000).toLong()) break
                if (heardMs >= (MAX_S * 1000).toLong()) break
            }
        } finally {
            mic.close()
        }
        if (!spoke || id != run) return null
        return ServerSpeech.wav(pcm.toByteArray(), RATE)
    }

    /** Loudness, as RMS of 16-bit samples, that counts as speech: this many times the background... */
    private const val SPEECH_OVER_NOISE = 2.5
    /** ...and never less than this, so a silent car doesn't make every breath a word. */
    private const val MIN_SPEECH_RMS = 400.0

    /** The model that rereads the phrase, when one is chosen and downloaded; null otherwise. */
    private fun ensureFinalLoaded(context: Context): OfflineRecognizer? = synchronized(finalLock) {
        val model = SpeechModels.final.value ?: return null
        if (finalRecognizer != null && loadedFinal == model) return finalRecognizer
        finalRecognizer?.release()
        finalRecognizer = null
        if (!SpeechModels.isReady(context, model)) return null
        val dir = SpeechModels.dir(context, model)
        fun path(name: String) = File(dir, name).absolutePath
        val started = SystemClock.uptimeMillis()
        return runCatching {
            OfflineRecognizer(
                config = OfflineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = RATE, featureDim = 80),
                    modelConfig = if (model.modelType == "canary") {
                        // English in, English out, and no punctuation: clean() strips it anyway.
                        OfflineModelConfig(
                            canary = OfflineCanaryModelConfig(path(model.encoder), path(model.decoder), srcLang = "en", tgtLang = "en", usePnc = false),
                            tokens = path("tokens.txt"),
                            numThreads = threads(),
                        )
                    } else {
                        OfflineModelConfig(
                            transducer = OfflineTransducerModelConfig(path(model.encoder), path(model.decoder), path(model.joiner)),
                            tokens = path("tokens.txt"),
                            numThreads = threads(),
                            modelType = model.modelType,
                        )
                    },
                    decodingMethod = "greedy_search",
                ),
            )
        }.onSuccess {
            finalRecognizer = it
            loadedFinal = model
            GearslipLog.i("speech: loaded ${model.label} in ${SystemClock.uptimeMillis() - started} ms")
        }.onFailure {
            GearslipLog.e("speech: couldn't load ${model.label}", it)
        }.getOrNull()
    }

    private fun ensureLoaded(context: Context): OnlineRecognizer? {
        val model = SpeechModels.selected.value
        if (recognizer != null && loadedModel == model) return recognizer
        recognizer?.release()
        recognizer = null
        if (!SpeechModels.isReady(context, model)) return null
        val dir = SpeechModels.dir(context, model)
        fun path(name: String) = File(dir, name).absolutePath
        val started = SystemClock.uptimeMillis()
        return runCatching {
            OnlineRecognizer(
                config = OnlineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = RATE, featureDim = 80),
                    modelConfig = OnlineModelConfig(
                        transducer = OnlineTransducerModelConfig(path(model.encoder), path(model.decoder), path(model.joiner)),
                        tokens = path("tokens.txt"),
                        numThreads = threads(),
                        modelType = model.modelType,
                    ),
                    endpointConfig = EndpointConfig(
                        rule1 = EndpointRule(false, NO_SPEECH_S, 0f),
                        rule2 = EndpointRule(true, END_SILENCE_S, 0f),
                        rule3 = EndpointRule(false, 0f, MAX_S),
                    ),
                    enableEndpoint = true,
                    decodingMethod = "greedy_search",
                ),
            )
        }.onSuccess {
            recognizer = it
            loadedModel = model
            GearslipLog.i("speech: loaded ${model.label} in ${SystemClock.uptimeMillis() - started} ms")
        }.onFailure {
            GearslipLog.e("speech: couldn't load ${model.label}", it)
        }.getOrNull()
    }

    /** The big cores only: more threads than that slow the model down on a phone. */
    private fun threads() = Runtime.getRuntime().availableProcessors().let { if (it >= 8) 4 else max(1, it / 2) }

    /**
     * Models write in upper case, or with punctuation and capitals of their own; commands are
     * matched in plain lower case, so only words and apostrophes are kept.
     */
    private fun clean(text: String): String =
        text.lowercase().replace(Regex("[^\\p{L}\\p{N}' ]"), " ").replace(Regex("\\s+"), " ").trim()

    /**
     * A short beep on the media stream, so it plays through the car like everything else, then a
     * pause long enough for it to finish there before the microphone opens and hears it.
     */
    private fun cue() {
        val tone = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, CUE_VOLUME) }.getOrNull() ?: return
        try {
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, CUE_MS)
            Thread.sleep(CUE_MS + CUE_TAIL_MS)
        } finally {
            tone.release()
        }
    }

    // --- microphones -----------------------------------------------------------------------

    /** Where a phrase is heard from: [read] fills [frame] and returns how many samples it got, -1 at the end. */
    private interface Mic {
        fun read(frame: ShortArray): Int
        fun close()
    }

    @SuppressLint("MissingPermission") // checked in listen()
    private fun phoneMic(): Mic {
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(min, CHUNK * 4),
        )
        check(recorder.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord didn't initialize" }
        recorder.startRecording()
        GearslipLog.i("speech: listening through the phone's microphone")
        return object : Mic {
            override fun read(frame: ShortArray) = recorder.read(frame, 0, frame.size)
            override fun close() {
                runCatching { recorder.stop() }
                recorder.release()
            }
        }
    }

    /**
     * The car's microphone, which hears the driver better than a phone in a cup holder. The car
     * sends audio in its own sized pieces; they're cut to [CHUNK]s here. Null when there's no car
     * microphone, or it sends nothing at first, so the phone's is used instead.
     */
    private fun carMic(): Mic? {
        val car = CarMic.current ?: return null
        if (!car.start()) return null
        val first = car.read(CAR_FIRST_AUDIO_MS)
        if (first == null) {
            car.stop()
            GearslipLog.w("speech: the car's microphone sent nothing - using the phone's")
            return null
        }
        GearslipLog.i("speech: listening through the car's microphone")
        return object : Mic {
            var pending = first
            var at = 0
            override fun read(frame: ShortArray): Int {
                var filled = 0
                while (filled < frame.size) {
                    val chunk = pending ?: car.read(CAR_GAP_MS) ?: break
                    pending = chunk
                    val n = minOf(frame.size - filled, chunk.size - at)
                    System.arraycopy(chunk, at, frame, filled, n)
                    filled += n
                    at += n
                    if (at == chunk.size) { pending = null; at = 0 }
                }
                // The car stopped sending mid-phrase: end the phrase rather than wait forever.
                return if (filled == 0) -1 else filled
            }
            override fun close() = car.stop()
        }
    }
}
