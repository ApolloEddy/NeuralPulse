package com.eddy.neuralpulse.audio

import android.media.AudioRecord
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * AudioRecord 读取线程：只负责把浮点 PCM 按 hop 喂给 [AudioProcessor]，
 * 全部 DSP 在 Processor 里（播放捕获与演示合成共用同一条链）。
 */
class AudioAnalyzer(private val record: AudioRecord) : Thread("neuralpulse-audio") {

    private val processor = AudioProcessor(record.sampleRate)

    @Volatile private var running = true

    override fun run() {
        val hopSamples = FloatArray(AudioProcessor.HOP)
        try {
            record.startRecording()
            var debugFrames = 0
            var debugSilence = 0
            while (running) {
                var got = 0
                while (got < AudioProcessor.HOP && running) {
                    val n = record.read(hopSamples, got, AudioProcessor.HOP - got, AudioRecord.READ_BLOCKING)
                    if (n < 0) { android.util.Log.e(TAG, "AudioRecord.read 失败: $n"); return }
                    got += n
                }
                if (!running) break
                debugFrames++
                if (debugFrames % 90 == 0) {
                    var s = 0f
                    for (v in hopSamples) s += v * v
                    val rms = sqrt(s / AudioProcessor.HOP)
                    if (rms == 0f) debugSilence++
                    android.util.Log.i(
                        TAG,
                        "帧=$debugFrames rms=$rms 静音帧=$debugSilence state=${record.recordingState} sr=${record.sampleRate}"
                    )
                }
                processor.processFrame(hopSamples)
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "分析线程异常退出", e)
        } finally {
            try { record.stop() } catch (_: Exception) {}
        }
    }

    fun stopAnalyzer(joinMs: Long = 1000) {
        running = false
        try { interrupt(); join(joinMs) } catch (_: Exception) {}
    }

    companion object {
        private const val TAG = "NeuralPulse"
    }
}

/**
 * FFT（迭代基-2，预生成旋转因子表），n 为 2 的幂。
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
