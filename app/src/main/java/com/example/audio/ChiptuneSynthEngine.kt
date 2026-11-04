package com.example.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh
import kotlin.random.Random

interface SoundPlayer {
    var mute: Boolean
    var musicOn: Boolean
    var sfxOn: Boolean
    var wantTrack: Int
    fun ensureStarted()
    fun sfx(name: String, prio: Int = 0, vol: Float = 1f)
    fun setRumble(v: Float)
    fun release()
}

class ChiptuneSynthEngine(
    private val enableAudioHardware: Boolean = true
) : SoundPlayer {

    companion object {
        private const val SAMPLE_RATE = 22050
        private const val CHUNK_SAMPLES = 441 // ~20ms low-latency buffer chunks

        private val TRACK_0 = arrayOf(
            intArrayOf(220, 1), intArrayOf(330, 1), intArrayOf(440, 1), intArrayOf(330, 1),
            intArrayOf(523, 1), intArrayOf(330, 1), intArrayOf(440, 1), intArrayOf(330, 1),
            intArrayOf(175, 1), intArrayOf(262, 1), intArrayOf(349, 1), intArrayOf(262, 1),
            intArrayOf(440, 1), intArrayOf(262, 1), intArrayOf(349, 1), intArrayOf(262, 1),
            intArrayOf(196, 1), intArrayOf(294, 1), intArrayOf(392, 1), intArrayOf(294, 1),
            intArrayOf(494, 1), intArrayOf(294, 1), intArrayOf(392, 1), intArrayOf(294, 1),
            intArrayOf(165, 1), intArrayOf(247, 1), intArrayOf(330, 1), intArrayOf(415, 1),
            intArrayOf(494, 1), intArrayOf(415, 1), intArrayOf(330, 1), intArrayOf(247, 1),
            intArrayOf(659, 3), intArrayOf(587, 1), intArrayOf(523, 2), intArrayOf(494, 2),
            intArrayOf(440, 4), intArrayOf(523, 2), intArrayOf(659, 2), intArrayOf(587, 3),
            intArrayOf(523, 1), intArrayOf(494, 2), intArrayOf(440, 2), intArrayOf(415, 4),
            intArrayOf(494, 2), intArrayOf(0, 2)
        )

        private val TRACK_1 = arrayOf(
            intArrayOf(440, 1), intArrayOf(523, 1), intArrayOf(659, 1), intArrayOf(523, 1),
            intArrayOf(440, 1), intArrayOf(523, 1), intArrayOf(659, 1), intArrayOf(523, 1),
            intArrayOf(392, 1), intArrayOf(494, 1), intArrayOf(587, 1), intArrayOf(494, 1),
            intArrayOf(392, 1), intArrayOf(494, 1), intArrayOf(587, 1), intArrayOf(494, 1),
            intArrayOf(349, 1), intArrayOf(440, 1), intArrayOf(523, 1), intArrayOf(440, 1),
            intArrayOf(349, 1), intArrayOf(440, 1), intArrayOf(523, 1), intArrayOf(440, 1),
            intArrayOf(330, 1), intArrayOf(415, 1), intArrayOf(494, 1), intArrayOf(415, 1),
            intArrayOf(330, 1), intArrayOf(415, 1), intArrayOf(494, 1), intArrayOf(659, 1),
            intArrayOf(659, 1), intArrayOf(659, 1), intArrayOf(587, 1), intArrayOf(523, 1),
            intArrayOf(587, 2), intArrayOf(523, 1), intArrayOf(494, 1), intArrayOf(440, 2),
            intArrayOf(523, 1), intArrayOf(659, 1), intArrayOf(880, 2), intArrayOf(784, 1),
            intArrayOf(659, 1), intArrayOf(698, 1), intArrayOf(698, 1), intArrayOf(659, 1),
            intArrayOf(587, 1), intArrayOf(659, 2), intArrayOf(587, 1), intArrayOf(523, 1),
            intArrayOf(494, 2), intArrayOf(415, 1), intArrayOf(494, 1), intArrayOf(659, 2),
            intArrayOf(0, 2)
        )

        private val TRACK_2 = arrayOf(
            intArrayOf(220, 1), intArrayOf(440, 1), intArrayOf(220, 1), intArrayOf(440, 1),
            intArrayOf(220, 1), intArrayOf(440, 1), intArrayOf(220, 1), intArrayOf(440, 1),
            intArrayOf(220, 1), intArrayOf(440, 1), intArrayOf(220, 1), intArrayOf(440, 1),
            intArrayOf(659, 1), intArrayOf(622, 1), intArrayOf(587, 1), intArrayOf(440, 1),
            intArrayOf(175, 1), intArrayOf(349, 1), intArrayOf(175, 1), intArrayOf(349, 1),
            intArrayOf(175, 1), intArrayOf(349, 1), intArrayOf(175, 1), intArrayOf(349, 1),
            intArrayOf(165, 1), intArrayOf(330, 1), intArrayOf(165, 1), intArrayOf(330, 1),
            intArrayOf(415, 1), intArrayOf(494, 1), intArrayOf(659, 1), intArrayOf(494, 1),
            intArrayOf(659, 2), intArrayOf(659, 1), intArrayOf(0, 1), intArrayOf(587, 2),
            intArrayOf(523, 2), intArrayOf(523, 2), intArrayOf(494, 1), intArrayOf(0, 1),
            intArrayOf(440, 2), intArrayOf(0, 2), intArrayOf(698, 2), intArrayOf(698, 1),
            intArrayOf(0, 1), intArrayOf(659, 2), intArrayOf(587, 2), intArrayOf(415, 1),
            intArrayOf(494, 1), intArrayOf(587, 1), intArrayOf(698, 1), intArrayOf(659, 2),
            intArrayOf(0, 2)
        )

        private val TRACKS = arrayOf(TRACK_0, TRACK_1, TRACK_2)
        private val TRACK_STEPS_MS = intArrayOf(170, 125, 100)

        private val RATE_LIMIT_MS = mapOf(
            "DOT" to 18L,
            "DASH" to 60L,
            "LAND" to 60L,
            "PEW" to 70L,
            "MOVE" to 40L,
            "TICK" to 25L,
            "SPIKE" to 40L,
            "SMALLBOOM" to 60L,
            "ALARM" to 100L
        )

        fun bgmFor(st: Int, inLevelFlag: Boolean): Int {
            return when (st) {
                10, 0, 5, 8, 9, 11 -> 0
                12 -> 2
                13, 14 -> 1
                1, 2 -> if (inLevelFlag) 1 else 2
                3 -> if (inLevelFlag) 1 else -1
                else -> -1
            }
        }
    }

    private enum class WaveType { PULSE25, TRIANGLE, SAWTOOTH, SINE }
    private enum class FilterMode { LOWPASS, HIGHPASS, BANDPASS }

    private class ToneVoice(
        val wave: WaveType,
        val f0: Double,
        val f1: Double,
        var delaySamples: Int,
        val durSamples: Int,
        val peakVol: Double,
        val isMusic: Boolean
    ) {
        var pos: Int = 0
        var phase: Double = 0.0
        val logRatio: Double = if (f1 > 0.0 && f0 > 0.0 && f1 != f0) ln(f1 / f0) else 0.0
    }

    private class NoiseVoice(
        val filterMode: FilterMode,
        val f0: Double,
        val f1: Double,
        var delaySamples: Int,
        val durSamples: Int,
        val peakVol: Double,
        val isMusic: Boolean
    ) {
        var pos: Int = 0
        var lpState: Double = 0.0
        var bpState: Double = 0.0
        val logRatio: Double = if (f1 > 0.0 && f0 > 0.0 && f1 != f0) ln(f1 / f0) else 0.0
    }

    @Volatile override var mute: Boolean = false
    @Volatile override var musicOn: Boolean = true
    @Volatile override var sfxOn: Boolean = true
    @Volatile override var wantTrack: Int = -1

    @Volatile private var running = false
    @Volatile private var rumbleValue = 0f
    @Volatile private var rumbleUpdatedAtMs = 0L
    @Volatile private var duckUntilSample = 0L

    private var totalRenderedSamples = 0L
    private var synthThread: Thread? = null
    private val pendingTones = ConcurrentLinkedQueue<ToneVoice>()
    private val pendingNoises = ConcurrentLinkedQueue<NoiseVoice>()
    private val lastPlayedMs = HashMap<String, Long>()
    private var dotCombo = 0
    private var dotComboAtMs = 0L

    override fun ensureStarted() {
        if (!enableAudioHardware || running) return
        synchronized(this) {
            if (running) return
            running = true
            synthThread = Thread({ runAudioLoop() }, "MaskMazeChiptuneSynth").apply {
                isDaemon = true
                priority = Thread.MAX_PRIORITY
                start()
            }
        }
    }

    override fun setRumble(v: Float) {
        rumbleValue = v.coerceIn(0f, 1f)
        rumbleUpdatedAtMs = System.currentTimeMillis()
    }

    override fun sfx(name: String, prio: Int, vol: Float) {
        if (!sfxOn || mute) return
        ensureStarted()
        val now = System.currentTimeMillis()
        val limit = RATE_LIMIT_MS[name]
        if (limit != null) {
            synchronized(lastPlayedMs) {
                val prev = lastPlayedMs[name] ?: 0L
                if (now - prev < limit) return
                lastPlayedMs[name] = now
            }
        }

        val v = max(0.05f, vol).toDouble()
        fun tone(type: WaveType, f0: Double, f1: Double, startSec: Double, durSec: Double, gain: Double) {
            pendingTones.offer(
                ToneVoice(
                    wave = type,
                    f0 = f0,
                    f1 = f1,
                    delaySamples = (startSec * SAMPLE_RATE).toInt().coerceAtLeast(0),
                    durSamples = (durSec * SAMPLE_RATE).toInt().coerceAtLeast(16),
                    peakVol = gain * v,
                    isMusic = false
                )
            )
        }
        fun hiss(startSec: Double, durSec: Double, gain: Double, mode: FilterMode, f0: Double, f1: Double = 0.0) {
            pendingNoises.offer(
                NoiseVoice(
                    filterMode = mode,
                    f0 = f0,
                    f1 = f1,
                    delaySamples = (startSec * SAMPLE_RATE).toInt().coerceAtLeast(0),
                    durSamples = (durSec * SAMPLE_RATE).toInt().coerceAtLeast(16),
                    peakVol = gain * v,
                    isMusic = false
                )
            )
        }
        fun arp(notes: IntArray, gap: Double, dur: Double, gain: Double, wave: WaveType = WaveType.PULSE25) {
            notes.forEachIndexed { idx, f ->
                tone(wave, f.toDouble(), f.toDouble(), idx * gap, dur, gain)
            }
        }

        when (name) {
            "DOT" -> {
                if (now - dotComboAtMs < 320L) {
                    dotCombo = min(dotCombo + 1, 12)
                } else {
                    dotCombo = 0
                }
                dotComboAtMs = now
                val f = 620.0 * 2.0.pow(dotCombo / 12.0)
                tone(WaveType.PULSE25, f, f * 1.06, 0.0, 0.05, 0.14)
            }
            "COIN" -> {
                tone(WaveType.PULSE25, 1319.0, 1319.0, 0.0, 0.07, 0.18)
                tone(WaveType.PULSE25, 1760.0, 1760.0, 0.07, 0.20, 0.18)
                tone(WaveType.TRIANGLE, 2637.0, 2637.0, 0.07, 0.25, 0.10)
            }
            "STAR" -> {
                arp(intArrayOf(1047, 1319, 1568, 2093), 0.07, 0.12, 0.16)
                tone(WaveType.TRIANGLE, 3136.0, 3136.0, 0.28, 0.30, 0.10)
            }
            "CHARGE" -> {
                tone(WaveType.SAWTOOTH, 300.0, 1800.0, 0.0, 0.28, 0.14)
                hiss(0.10, 0.20, 0.10, FilterMode.HIGHPASS, 4000.0, 8000.0)
            }
            "SHIELD" -> {
                tone(WaveType.TRIANGLE, 400.0, 1600.0, 0.0, 0.32, 0.22)
                tone(WaveType.SINE, 800.0, 3200.0, 0.0, 0.32, 0.12)
                arp(intArrayOf(1568, 2093), 0.12, 0.15, 0.12, WaveType.SINE)
            }
            "START" -> arp(intArrayOf(523, 659, 784, 1047), 0.08, 0.14, 0.16)
            "WIN" -> {
                arp(intArrayOf(523, 659, 784, 1047, 1319), 0.09, 0.16, 0.16)
                tone(WaveType.PULSE25, 1047.0, 1047.0, 0.45, 0.50, 0.14)
                tone(WaveType.TRIANGLE, 523.0, 523.0, 0.45, 0.50, 0.22)
            }
            "DEAD" -> {
                tone(WaveType.SAWTOOTH, 440.0, 60.0, 0.0, 0.60, 0.20)
                hiss(0.0, 0.45, 0.26, FilterMode.LOWPASS, 1400.0, 90.0)
                tone(WaveType.SINE, 120.0, 40.0, 0.0, 0.50, 0.36)
            }
            "BOOM" -> {
                hiss(0.0, 0.50, 0.48, FilterMode.LOWPASS, 2200.0, 70.0)
                tone(WaveType.SINE, 95.0, 35.0, 0.0, 0.45, 0.45)
            }
            "SMALLBOOM" -> hiss(0.0, 0.20, 0.24, FilterMode.LOWPASS, 1600.0, 200.0)
            "PAUSE" -> {
                tone(WaveType.PULSE25, 620.0, 420.0, 0.0, 0.08, 0.14)
                tone(WaveType.PULSE25, 420.0, 300.0, 0.08, 0.10, 0.14)
            }
            "NOPE" -> tone(WaveType.PULSE25, 120.0, 105.0, 0.0, 0.14, 0.16)
            "TICK" -> tone(WaveType.PULSE25, 2200.0, 2000.0, 0.0, 0.025, 0.08)
            "MOVE" -> tone(WaveType.PULSE25, 300.0, 280.0, 0.0, 0.025, 0.07)
            "DASH" -> {
                hiss(0.0, 0.16, 0.22, FilterMode.BANDPASS, 400.0, 2600.0)
                tone(WaveType.SINE, 250.0, 650.0, 0.0, 0.12, 0.10)
            }
            "LAND" -> {
                tone(WaveType.SINE, 150.0, 45.0, 0.0, 0.12, 0.36)
                hiss(0.0, 0.06, 0.14, FilterMode.LOWPASS, 800.0, 200.0)
            }
            "SPIKE" -> {
                hiss(0.0, 0.06, 0.16, FilterMode.HIGHPASS, 3000.0)
                tone(WaveType.PULSE25, 1800.0, 900.0, 0.0, 0.05, 0.06)
            }
            "PEW" -> tone(WaveType.PULSE25, 1400.0, 500.0, 0.0, 0.07, 0.07)
            "WAKA1" -> tone(WaveType.TRIANGLE, 420.0, 700.0, 0.0, 0.07, 0.18)
            "WAKA2" -> tone(WaveType.TRIANGLE, 700.0, 420.0, 0.0, 0.07, 0.18)
            "ALARM" -> tone(WaveType.PULSE25, 880.0, 880.0, 0.0, 0.07, 0.09)
        }

        if (prio >= 2) {
            duckUntilSample = totalRenderedSamples + (SAMPLE_RATE * 0.55).toLong()
        }
    }

    override fun release() {
        running = false
        synthThread?.interrupt()
        synthThread = null
    }

    private fun runAudioLoop() {
        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) return

        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(max(minBuf, CHUNK_SAMPLES * 4))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (_: Throwable) {
            return
        }

        try {
            track.play()
        } catch (_: Throwable) {
            track.release()
            return
        }

        val activeTones = ArrayList<ToneVoice>(32)
        val activeNoises = ArrayList<NoiseVoice>(16)
        val pcm = ShortArray(CHUNK_SAMPLES)
        val rng = Random(0x5EED1234)

        var curTrack = -1
        var noteIdx = 0
        var stepCounter = 0
        var samplesUntilNextNote = 0
        var musicGainSmooth = 0.28
        var rumbleLpState = 0.0

        try {
            while (running && !Thread.currentThread().isInterrupted) {
                while (true) {
                    val tv = pendingTones.poll() ?: break
                    if (activeTones.size < 40) activeTones.add(tv)
                }
                while (true) {
                    val nv = pendingNoises.poll() ?: break
                    if (activeNoises.size < 24) activeNoises.add(nv)
                }

                val desiredTrack = wantTrack
                if (desiredTrack != curTrack) {
                    curTrack = desiredTrack
                    noteIdx = 0
                    stepCounter = 0
                    samplesUntilNextNote = (SAMPLE_RATE * 0.04).toInt()
                    activeTones.removeAll { it.isMusic }
                    activeNoises.removeAll { it.isMusic }
                }

                val nowMs = System.currentTimeMillis()
                val rumbleActive = !mute && sfxOn && (nowMs - rumbleUpdatedAtMs < 320L) && rumbleValue > 0.01f
                val rumbleGain = if (rumbleActive) (rumbleValue * 0.28) else 0.0

                for (i in 0 until CHUNK_SAMPLES) {
                    totalRenderedSamples++

                    // Music sequencer tick
                    if (curTrack in TRACKS.indices && musicOn && !mute) {
                        if (samplesUntilNextNote <= 0) {
                            val notes = TRACKS[curTrack]
                            val stepMs = TRACK_STEPS_MS[curTrack]
                            val pair = notes[noteIdx]
                            val freq = pair[0]
                            val lenSteps = pair[1]
                            val noteSamples = max(1, (lenSteps * stepMs * SAMPLE_RATE) / 1000)
                            if (freq > 0) {
                                val soft = curTrack == 0
                                activeTones.add(
                                    ToneVoice(
                                        wave = if (soft) WaveType.TRIANGLE else WaveType.PULSE25,
                                        f0 = freq.toDouble(),
                                        f1 = freq.toDouble(),
                                        delaySamples = 0,
                                        durSamples = (noteSamples * 0.9).toInt().coerceAtLeast(32),
                                        peakVol = if (soft) 0.42 else 0.30,
                                        isMusic = true
                                    )
                                )
                                if (stepCounter % 8 == 0) {
                                    val bassSamples = ((stepMs * 7.5 * SAMPLE_RATE) / 1000.0).toInt()
                                    activeTones.add(
                                        ToneVoice(
                                            wave = WaveType.TRIANGLE,
                                            f0 = freq / 2.0,
                                            f1 = freq / 2.0,
                                            delaySamples = 0,
                                            durSamples = bassSamples.coerceAtLeast(64),
                                            peakVol = 0.38,
                                            isMusic = true
                                        )
                                    )
                                }
                            }
                            if (curTrack >= 1 && (stepCounter % 2 == 1)) {
                                activeNoises.add(
                                    NoiseVoice(
                                        filterMode = FilterMode.HIGHPASS,
                                        f0 = 7000.0,
                                        f1 = 7000.0,
                                        delaySamples = 0,
                                        durSamples = (0.03 * SAMPLE_RATE).toInt(),
                                        peakVol = 0.06,
                                        isMusic = true
                                    )
                                )
                            }
                            samplesUntilNextNote = noteSamples
                            stepCounter += lenSteps
                            noteIdx = (noteIdx + 1) % notes.size
                        }
                        samplesUntilNextNote--
                    }

                    val targetMusicBus = if (totalRenderedSamples < duckUntilSample) 0.09 else 0.28
                    musicGainSmooth += (targetMusicBus - musicGainSmooth) * 0.002

                    var musicSample = 0.0
                    var sfxSample = 0.0

                    // Synthesize tone voices
                    var tIdx = activeTones.size - 1
                    while (tIdx >= 0) {
                        val v = activeTones[tIdx]
                        if (v.delaySamples > 0) {
                            v.delaySamples--
                        } else if (v.pos >= v.durSamples) {
                            activeTones.removeAt(tIdx)
                        } else {
                            val prog = v.pos.toDouble() / v.durSamples.toDouble()
                            val freq = if (v.logRatio != 0.0) v.f0 * exp(v.logRatio * prog) else v.f0
                            v.phase += freq / SAMPLE_RATE
                            if (v.phase >= 1.0) v.phase -= v.phase.toInt().toDouble()

                            val raw = when (v.wave) {
                                WaveType.PULSE25 -> if (v.phase < 0.25) 1.0 else -0.33
                                WaveType.TRIANGLE -> {
                                    val p = v.phase
                                    if (p < 0.5) (4.0 * p - 1.0) else (3.0 - 4.0 * p)
                                }
                                WaveType.SAWTOOTH -> 2.0 * v.phase - 1.0
                                WaveType.SINE -> sin(2.0 * PI * v.phase)
                            }

                            val attackSamples = 88.0 // ~4ms
                            val env = if (v.pos < attackSamples) {
                                v.pos / attackSamples
                            } else {
                                exp(-3.6 * prog)
                            }
                            val out = raw * v.peakVol * env
                            if (v.isMusic) musicSample += out else sfxSample += out
                            v.pos++
                        }
                        tIdx--
                    }

                    // Synthesize noise voices
                    var nIdx = activeNoises.size - 1
                    while (nIdx >= 0) {
                        val nv = activeNoises[nIdx]
                        if (nv.delaySamples > 0) {
                            nv.delaySamples--
                        } else if (nv.pos >= nv.durSamples) {
                            activeNoises.removeAt(nIdx)
                        } else {
                            val prog = nv.pos.toDouble() / nv.durSamples.toDouble()
                            val white = rng.nextDouble(-1.0, 1.0)
                            val cutoff = (if (nv.logRatio != 0.0) nv.f0 * exp(nv.logRatio * prog) else nv.f0)
                                .coerceIn(60.0, SAMPLE_RATE * 0.42)
                            val alpha = (2.0 * PI * cutoff / SAMPLE_RATE).coerceIn(0.01, 0.92)
                            nv.lpState += alpha * (white - nv.lpState)
                            val filtered = when (nv.filterMode) {
                                FilterMode.LOWPASS -> nv.lpState
                                FilterMode.HIGHPASS -> white - nv.lpState
                                FilterMode.BANDPASS -> {
                                    val hp = white - nv.lpState
                                    nv.bpState += alpha * (hp - nv.bpState)
                                    nv.bpState * 1.8
                                }
                            }
                            val env = exp(-4.0 * prog)
                            val out = filtered * nv.peakVol * env
                            if (nv.isMusic) musicSample += out else sfxSample += out
                            nv.pos++
                        }
                        nIdx--
                    }

                    // Endless rising tide rumble
                    if (rumbleGain > 0.0) {
                        val w = rng.nextDouble(-1.0, 1.0)
                        rumbleLpState += 0.038 * (w - rumbleLpState)
                        sfxSample += rumbleLpState * rumbleGain
                    }

                    val mixed = if (mute) {
                        0.0
                    } else {
                        val m = if (musicOn) musicSample * musicGainSmooth else 0.0
                        val s = if (sfxOn) sfxSample * 0.85 else 0.0
                        tanh(m + s)
                    }
                    pcm[i] = (mixed * 30000.0).toInt().coerceIn(-32767, 32767).toShort()
                }

                track.write(pcm, 0, CHUNK_SAMPLES)
            }
        } catch (_: InterruptedException) {
            // Normal shutdown
        } catch (_: Throwable) {
            // Audio device closed
        } finally {
            try {
                track.stop()
            } catch (_: Throwable) {}
            track.release()
        }
    }
}
