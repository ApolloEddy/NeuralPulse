package com.eddy.neuralpulse

import org.junit.Test

class SignalReproTest {
    @Test
    fun signals_survive_capture_like_spawning() {
        val scene = NeuralLivingScene()
        scene.setLayout(400f, 800f)
        val dt = 1f / 60f
        var t = 0f
        while (t < 180f) {
            val beat = if ((t * 2.4).toInt() % 2 == 0) 0.8f else 0.3f
            scene.advanceSignals(dt, spawn = 0.1f + beat * 2.2f, speedMul = 1f + 0.5f * beat + 0.3f * beat, energy = beat)
            try {
                for (i in 0 until scene.signalMax()) {
                    if (!scene.signalAlive(i)) continue
                    for (k in 5 downTo 0) scene.signalProj(i, k * 0.09f)
                }
            } catch (e: ArrayIndexOutOfBoundsException) {
                throw IllegalStateException("t=$t dump: " + scene.dumpSignals(), e)
            }
            scene.advance(dt)
            scene.computeWorlds()
            scene.projectAllNodes()
            t += dt
        }
    }
}
