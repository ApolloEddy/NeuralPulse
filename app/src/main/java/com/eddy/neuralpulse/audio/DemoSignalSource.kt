package com.eddy.neuralpulse.audio

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * 演示信号源：与 tools/make_test_beat.py 同一套合成配方（底鼓 + 踩镲 + 贝斯），
 * 实时生成 hop 帧喂给 [AudioProcessor] —— 演示模式因此走的是与系统捕获
 * 完全相同的 FFT/节拍/BPM 分析链，HUD 读数即分析器的端到端验证。
 */
class DemoSignalSource(
    private val sampleRate: Int,
    private val bpm: Float = 120f,
    private val onHop: (FloatArray) -> Unit,
) : Thread("neuralpulse-demo") {

    @Volatile private var running = true

    private val rng = Random(120)
    private val hop = FloatArray(AudioProcessor.HOP)
    private val hopSec = AudioProcessor.HOP.toFloat() / sampleRate

    // 事件表游标（秒）
    private var clock = 0f
    private var nextKick = 0f
    private var nextHat = 0f
    private var bassUntil = 0f
    private var bassIdx = 0

    private var kickStart = -1f
    private var hatStart = -1f
    private var bassFreq = 55f
    private var bassStart = -1f

    private val beatSec = 60f / bpm

    override fun run() {
        // 实时步进：每 hopNanos 出一帧，让分析时钟与真实时间对齐
        val hopNanos = AudioProcessor.HOP * 1_000_000_000L / sampleRate
        var next = System.nanoTime()
        while (running) {
            renderHop()
            onHop(hop.copyOf())
            clock += hopSec
            next += hopNanos
            val now = System.nanoTime()
            if (next > now) {
                try { sleep((next - now) / 1_000_000) } catch (_: InterruptedException) { return }
                if (next > System.nanoTime()) {
                    java.util.concurrent.locks.LockSupport.parkNanos(next - System.nanoTime())
                }
            } else {
                next = now // 落后了就重置节拍
            }
        }
    }

    fun stopSource() {
        running = false
        try { interrupt() } catch (_: Exception) {}
    }

    private fun renderHop() {
        // 在这一帧内触发新事件
        while (clock + hopSec >= nextKick) {
            kickStart = nextKick; nextKick += beatSec
        }
        while (clock + hopSec >= nextHat) {
            hatStart = nextHat; nextHat += beatSec / 2
        }
        if (clock >= bassUntil) {
            val notes = floatArrayOf(55f, 55f, 82.5f, 110f)
            bassFreq = notes[bassIdx % notes.size]
            bassIdx++
            bassStart = clock
            bassUntil = clock + beatSec * 1.8f
        }

        for (i in 0 until AudioProcessor.HOP) {
            val t = clock + i.toFloat() / sampleRate
            var v = 0f
            if (kickStart >= 0f) {
                val p = t - kickStart
                if (p < 0.22f) v += sin(2.0 * PI * 55 * p).toFloat() * exp(-p * 18) * 0.85f
            }
            if (hatStart >= 0f) {
                val p = t - hatStart
                if (p < 0.06f) {
                    val noise = rng.nextFloat() * 2f - 1f
                    v += noise * exp(-p * 70) * (0.6f + 0.4f * sin(2.0 * PI * 6000 * p).toFloat()) * 0.30f
                }
            }
            if (bassStart >= 0f) {
                val p = t - bassStart
                v += (sin(2.0 * PI * bassFreq * p).toFloat() * 0.6f +
                    sin(2.0 * PI * bassFreq * 2 * p).toFloat() * 0.25f) * 0.32f
            }
            hop[i] = v.coerceIn(-1f, 1f)
        }

        // 事件出窗后失效
        if (kickStart >= 0f && clock > kickStart + 0.22f) kickStart = -1f
        if (hatStart >= 0f && clock > hatStart + 0.06f) hatStart = -1f
    }
}
