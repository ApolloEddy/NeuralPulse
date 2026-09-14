package com.eddy.neuralpulse.audio

import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DSP 核心的纯 JVM 回归测试：
 * - FFT 频率定位；
 * - 已知 BPM 的合成 PCM → 节拍检出 → BPM 估计（守护 estimateBpm 间隔→BPM 换算回归）。
 */
class AudioProcessorTest {

    private val sr = 48000

    @Test
    fun fft_locates_sine_peak_at_expected_bin() {
        val n = 2048
        val fft = Fft(n)
        val re = FloatArray(n) { sin(2.0 * PI * 40 * it / n).toFloat() } // 第 40 个 bin
        val im = FloatArray(n)
        fft.transform(re, im)
        var best = 0
        var bestMag = -1f
        for (i in 1 until n / 2) {
            val m = re[i] * re[i] + im[i] * im[i]
            if (m > bestMag) { bestMag = m; best = i }
        }
        assertEquals(40, best)
    }

    /** 120 BPM：每 0.5s 一个短促低频冲击（模拟底鼓）。 */
    private fun feedBeats(processor: AudioProcessor, bpm: Float, seconds: Int) {
        val hop = AudioProcessor.HOP
        val beatSamples = (60f / bpm * sr).toInt()
        var sinceBeat = beatSamples // 让开头立即有一拍
        val buf = FloatArray(hop)
        var pos = 0
        val total = seconds * sr
        while (pos < total) {
            val inHop = minOf(hop, total - pos)
            for (i in 0 until inHop) {
                if (sinceBeat >= beatSamples) {
                    sinceBeat = 0
                }
                // 冲击：拍点后 60ms 的 55Hz 衰减正弦
                val p = sinceBeat.toFloat() / sr
                buf[i] = if (p < 0.06f) (sin(2.0 * PI * 55 * p) * kotlin.math.exp(-p * 45.0)).toFloat() * 0.9f else 0f
                sinceBeat++
            }
            // 尾帧不足一个 hop 时补零再处理（对齐真实读帧语义）
            for (i in inHop until hop) buf[i] = 0f
            processor.processFrame(buf)
            pos += hop
        }
    }

    @Test
    fun bpm_detected_from_120bpm_kicks() {
        val processor = AudioProcessor(sr)
        feedBeats(processor, bpm = 120f, seconds = 14)
        val f = AudioBus.features
        assertTrue("beats 应该已触发（实际 ${f.beatId}）", f.beatId > 10)
        assertTrue("BPM 应检出（实际 ${f.bpm}）", f.bpm in 110f..130f)
    }

    @Test
    fun silence_gate_reports_no_beats() {
        val processor = AudioProcessor(sr)
        val silence = FloatArray(AudioProcessor.HOP)
        repeat(200) { processor.processFrame(silence) }
        val f = AudioBus.features
        assertEquals(0f, f.level, 1e-6f)
        assertEquals(0f, f.bass, 1e-6f)
        assertEquals(0L, f.beatId)
    }
}
