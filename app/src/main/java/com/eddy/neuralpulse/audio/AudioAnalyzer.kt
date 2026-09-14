package com.eddy.neuralpulse.audio

import android.media.AudioRecord
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 迭代基-2 FFT（Nayuki 风格，预生成旋转因子表），n 为 2 的幂。
 */
class Fft(val n: Int) {
    private val levels = Integer.numberOfTrailingZeros(n)
    private val cosTable = FloatArray(n / 2) { cos(2.0 * Math.PI * it / n).toFloat() }
    private val sinTable = FloatArray(n / 2) { sin(2.0 * Math.PI * it / n).toFloat() }

    fun transform(re: FloatArray, im: FloatArray) {
        // 位反转重排
        for (i in 0 until n) {
            val j = Integer.reverse(i) ushr (32 - levels)
            if (j > i) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        // 蝶形
        var size = 2
        while (size <= n) {
            val half = size / 2
            val step = n / size
            var i = 0
            while (i < n) {
                var k = 0
                for (j in i until i + half) {
                    val l = j + half
                    val tre = re[l] * cosTable[k] + im[l] * sinTable[k]
                    val tim = -re[l] * sinTable[k] + im[l] * cosTable[k]
                    re[l] = re[j] - tre
                    im[l] = im[j] - tim
                    re[j] += tre
                    im[j] += tim
                    k += step
                }
                i += size
            }
            size *= 2
        }
    }
}

/**
 * 系统音频流分析器：AudioRecord(播放捕获) → 分帧加窗 → FFT →
 * 响度/三频段/谱通量节拍/BPM 估计/色相偏移，写入 [AudioBus]。
 *
 * 所有能量先转 dB 再做带慢峰的 AGC 归一化，保证不同音量源下视觉动态一致。
 */
class AudioAnalyzer(private val record: AudioRecord) : Thread("neuralpulse-audio") {

    companion object {
        private const val FFT_SIZE = 2048
        private const val HOP = 1024
        private const val EPS = 1e-9f

        // dB → 0..1 的映射窗（低频天然更响，给更高地板）
        private const val LEVEL_DB_LOW = -55f
        private const val LEVEL_DB_HIGH = -14f
        private const val BASS_DB_LOW = -46f
        private const val BASS_DB_HIGH = -10f
        private const val MID_DB_LOW = -60f
        private const val MID_DB_HIGH = -22f
        private const val TREBLE_DB_LOW = -72f
        private const val TREBLE_DB_HIGH = -30f

        private const val BEAT_REFRACTORY = 0.22f     // 节拍不应期（秒），对应 ~272BPM 上限
        private const val BPM_SILENCE_RESET = 3.0f    // 连续无拍判定静止（秒）
    }

    @Volatile private var running = true

    private val sampleRate = record.sampleRate
    private val binHz = sampleRate.toFloat() / FFT_SIZE
    private val fft = Fft(FFT_SIZE)

    // 帧缓冲（50% 重叠）
    private val windowBuf = FloatArray(FFT_SIZE)
    private val hann = FloatArray(FFT_SIZE) { i ->
        (0.5 - 0.5 * cos(2.0 * Math.PI * i / (FFT_SIZE - 1))).toFloat()
    }
    private val fftRe = FloatArray(FFT_SIZE)
    private val fftIm = FloatArray(FFT_SIZE)
    private val mags = FloatArray(FFT_SIZE / 2)
    private val prevMags = FloatArray(FFT_SIZE / 2)
    private var hasPrev = false

    // 频段 bin 范围（闭区间，防 0 bin 直流泄漏从 bin1 起）
    private val bassBins = max(1, (20f / binHz).toInt())..min(FFT_SIZE / 2 - 1, (250f / binHz).toInt())
    private val midBins = bassBins.last + 1..min(FFT_SIZE / 2 - 1, (2000f / binHz).toInt())
    private val trebleBins = midBins.last + 1..min(FFT_SIZE / 2 - 1, (8000f / binHz).toInt())
    private val fluxBins = 1..trebleBins.last

    // AGC 慢峰
    private var levelPeak = 0.8f
    private var bassPeak = 0.8f
    private var midPeak = 0.6f
    private var treblePeak = 0.6f

    // 节拍状态
    private val fluxHistory = FloatArray(43)   // ≈2 秒 @ hop 时长
    private var fluxHead = 0
    private var fluxFill = 0
    private var analysisClock = 0f
    private var lastBeatAt = -10f
    private var beatId = 0L
    private var lastBeatStrength = 0f
    private val beatIntervals = FloatArray(10)
    private var intervalsFill = 0
    private var bpm = 0f

    override fun run() {
        val hopSamples = FloatArray(HOP)
        try {
            record.startRecording()
            while (running) {
                var got = 0
                while (got < HOP && running) {
                    val n = record.read(hopSamples, got, HOP - got, AudioRecord.READ_BLOCKING)
                    if (n < 0) return
                    if (n == 0) continue
                    got += n
                }
                if (!running) break
                process(hopSamples)
            }
        } catch (_: Exception) {
            // 录音流被外部终止：安静退出，服务层负责状态回写
        } finally {
            try { record.stop() } catch (_: Exception) {}
        }
    }

    fun stopAnalyzer(joinMs: Long = 1000) {
        running = false
        try { interrupt(); join(joinMs) } catch (_: Exception) {}
    }

    private fun process(hop: FloatArray) {
        // 帧前移，尾部放新样本
        System.arraycopy(windowBuf, HOP, windowBuf, 0, FFT_SIZE - HOP)
        System.arraycopy(hop, 0, windowBuf, FFT_SIZE - HOP, HOP)

        // ---- 时域响度（用最新 hop）----
        var sum = 0f
        for (v in hop) sum += v * v
        val rms = sqrt(sum / HOP)
        val levelDb = 20f * log10(rms + EPS)
        val level = norm01(levelDb, LEVEL_DB_LOW, LEVEL_DB_HIGH, ::levelPeak)

        // ---- 加窗 FFT ----
        for (i in 0 until FFT_SIZE) fftRe[i] = windowBuf[i] * hann[i]
        fftIm.fill(0f)
        fft.transform(fftRe, fftIm)
        for (i in mags.indices) mags[i] = sqrt(fftRe[i] * fftRe[i] + fftIm[i] * fftIm[i])

        // ---- 频段能量 → dB → AGC ----
        val bass = bandNorm(bassBins, BASS_DB_LOW, BASS_DB_HIGH, ::bassPeak)
        val mid = bandNorm(midBins, MID_DB_LOW, MID_DB_HIGH, ::midPeak)
        val treble = bandNorm(trebleBins, TREBLE_DB_LOW, TREBLE_DB_HIGH, ::treblePeak)

        // ---- 谱通量（低频加权）→ 自适应阈值节拍 ----
        var flux = 0f
        if (hasPrev) {
            for (i in fluxBins) {
                val d = mags[i] - prevMags[i]
                if (d > 0f) flux += d * (if (i <= bassBins.last) 2f else 1f)
            }
            flux /= fluxBins.count()
        }
        System.arraycopy(mags, 0, prevMags, 0, mags.size)
        hasPrev = true

        analysisClock += HOP.toFloat() / sampleRate
        detectBeat(flux)

        // ---- BPM：连续无拍回零，近 10 拍间隔中位数折算 ----
        if (beatId > 0 && analysisClock - lastBeatAt > BPM_SILENCE_RESET) {
            intervalsFill = 0
            bpm = 0f
        }

        // ---- 色相偏移：低频暖、高频亮的色轴流动 + 谱重心微调 ----
        var num = 0f; var den = 0f
        for (i in fluxBins) { num += mags[i] * i; den += mags[i] }
        val centroid = if (den > EPS) (num / den) / trebleBins.last else 0f
        val hue = ((bass - treble) * 0.85f + (centroid - 0.25f) * 0.55f).coerceIn(-1f, 1f)

        AudioBus.publish(
            AudioFeatures(
                active = true,
                level = level,
                bass = bass,
                mid = mid,
                treble = treble,
                beatId = beatId,
                beatStrength = lastBeatStrength,
                bpm = bpm,
                hue = hue,
                centroid = centroid.coerceIn(0f, 1f),
            )
        )
    }

    // ---- 节拍检测：谱通量 vs 滑窗均值自适应阈值 ----
    private fun detectBeat(flux: Float) {
        if (flux > 0f) {
            fluxHistory[fluxHead] = flux
            fluxHead = (fluxHead + 1) % fluxHistory.size
            if (fluxFill < fluxHistory.size) fluxFill++
        }
        if (fluxFill < 10) { decayBeat(); return }

        var mean = 0f
        for (i in 0 until fluxFill) mean += fluxHistory[i]
        mean /= fluxFill

        val sinceLast = analysisClock - lastBeatAt
        val threshold = mean * 1.45f + 0.002f
        if (flux > threshold && sinceLast > BEAT_REFRACTORY) {
            if (beatId > 0 && sinceLast in 0.25f..1.25f) pushInterval(sinceLast)
            lastBeatAt = analysisClock
            beatId++
            lastBeatStrength = ((flux / (mean + EPS) - 1.2f) / 1.8f).coerceIn(0.25f, 1f)
            if (intervalsFill >= 3) estimateBpm()
        } else {
            decayBeat()
        }
    }

    private fun decayBeat() {
        // hop 时长的指数衰减，渲染帧率高于分析率，视觉上再线性插值
        lastBeatStrength *= exp(-(HOP.toFloat() / sampleRate) * 5f)
        if (lastBeatStrength < 0.01f) lastBeatStrength = 0f
    }

    private fun pushInterval(interval: Float) {
        if (intervalsFill < beatIntervals.size) {
            beatIntervals[intervalsFill++] = interval
        } else {
            System.arraycopy(beatIntervals, 1, beatIntervals, 0, beatIntervals.size - 1)
            beatIntervals[beatIntervals.size - 1] = interval
        }
    }

    private fun estimateBpm() {
        if (intervalsFill < 3) return
        val sorted = beatIntervals.copyOf(intervalsFill).also { it.sort() }
        var m = sorted[sorted.size / 2]
        // 半倍速/倍速折叠到常见音乐区间
        var guard = 4
        while (m < 70f && m > 0f && guard-- > 0) m *= 2f
        guard = 4
        while (m > 180f && guard-- > 0) m /= 2f
        if (m !in 60f..200f) return
        bpm = if (bpm <= 0f) m else bpm * 0.65f + m * 0.35f
    }

    // ---- 工具：频段 RMS → dB → AGC 归一 ----
    private fun bandRms(bins: IntRange): Float {
        var sum = 0f
        for (i in bins) sum += mags[i] * mags[i]
        return sqrt(sum / bins.count())
    }

    private fun bandNorm(bins: IntRange, dbLow: Float, dbHigh: Float, peakRef: kotlin.reflect.KMutableProperty0<Float>): Float {
        val db = 20f * log10(bandRms(bins) + EPS)
        return norm01(db, dbLow, dbHigh, peakRef)
    }

    private fun norm01(db: Float, low: Float, high: Float, peakRef: kotlin.reflect.KMutableProperty0<Float>): Float {
        var v = ((db - low) / (high - low)).coerceIn(0f, 1.5f)
        // 慢峰 AGC：视觉动态不随源音量漂移
        val peak = peakRef.get()
        if (v > peak) peakRef.set(v) else peakRef.set(peak * 0.9995f + v * 0.0005f)
        v = (v / max(peakRef.get(), 0.25f)).coerceIn(0f, 1f)
        return v
    }
}
