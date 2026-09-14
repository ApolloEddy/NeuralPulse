package com.eddy.neuralpulse.audio

/**
 * 分析线程产出的音频特征快照（不可变对象整体替换，渲染帧无锁读取）。
 */
data class AudioFeatures(
    /** 是否有真实系统音频流（捕获中）。 */
    val active: Boolean = false,
    /** 平滑响度 0..1（AGC 归一化后）。 */
    val level: Float = 0f,
    /** 低频带能量 0..1（约 20-250Hz，鼓点所在）。 */
    val bass: Float = 0f,
    /** 中频带能量 0..1（约 250-2000Hz）。 */
    val mid: Float = 0f,
    /** 高频带能量 0..1（约 2-8kHz，踩镲/气声）。 */
    val treble: Float = 0f,
    /** 节拍事件计数（单调递增，渲染侧据此重置冲击动画）。 */
    val beatId: Long = 0L,
    /** 最近一次节拍强度 0..1。 */
    val beatStrength: Float = 0f,
    /** 估计 BPM，0 = 未知/静止。 */
    val bpm: Float = 0f,
    /** 色相偏移 -1..1：低频偏暖红、高频偏亮金，随音色重心流动。 */
    val hue: Float = 0f,
    /** 谱重心 0..1（亮暗质感的第二输入）。 */
    val centroid: Float = 0f,
)

/**
 * 全局单点总线：分析线程写入，Compose 渲染帧读取。
 */
object AudioBus {
    @Volatile
    var features: AudioFeatures = AudioFeatures()
        private set

    fun publish(value: AudioFeatures) {
        features = value
    }

    fun inactive() {
        features = AudioFeatures()
    }
}
