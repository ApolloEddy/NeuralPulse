import io

p = r"app\src\main\java\com\eddy\neuralpulse\audio\AudioCaptureService.kt"
s = io.open(p, encoding="utf-8").read()

# 1) 常量
old = '        const val MODE_MIC = "mic"'
new = ('        const val MODE_MIC = "mic"\n'
       '        const val MODE_VISUALIZER = "visualizer"')
assert old in s
s = s.replace(old, new)

# 2) startVisualizer 入口
old = '''        fun startMic(context: Context) {
            val intent = Intent(context, AudioCaptureService::class.java)
                .putExtra(EXTRA_MODE, MODE_MIC)
            context.startForegroundService(intent)
        }'''
new = old + '''

        fun startVisualizer(context: Context) {
            val intent = Intent(context, AudioCaptureService::class.java)
                .putExtra(EXTRA_MODE, MODE_VISUALIZER)
            context.startForegroundService(intent)
        }'''
assert old in s
s = s.replace(old, new)

# 3) 字段 + Visualizer 回调
old = '''    private var projection: MediaProjection? = null
    private var analyzer: AudioAnalyzer? = null'''
new = '''    private var projection: MediaProjection? = null
    private var analyzer: AudioAnalyzer? = null
    private var visualizer: Visualizer? = null
    private var lastVizNanos = 0L
    private var vizProcessor: AudioProcessor? = null
    private val vizCallback = Visualizer.OnDataCaptureListener { v, wave, samplingRate ->
        if (wave == null || wave.isEmpty()) return@OnDataCaptureListener
        val now = System.nanoTime()
        val frameDt = if (lastVizNanos == 0L) 0f
        else ((now - lastVizNanos) / 1_000_000_000f).coerceIn(0.001f, 0.1f)
        lastVizNanos = now
        val proc = vizProcessor ?: return@OnDataCaptureListener
        val hop = FloatArray(wave.size) { ((wave[it].toInt() and 0xFF) - 128) / 128f }
        for (i in hop.indices) hop[i] *= 1.5f
        proc.processFrame(hop, active = true, frameDt = frameDt)
    }'''
assert old in s
s = s.replace(old, new)

# 4) 双重启动保护
old = '''        if (analyzer != null) {
            return START_STICKY // 已在采集：忽略重复启动
        }'''
new = '''        if (analyzer != null || visualizer != null) {
            return START_STICKY // 已在采集：忽略重复启动
        }'''
assert old in s
s = s.replace(old, new)

# 5) 模式分发
old = '''            if (mode == MODE_MIC) {
                startForegroundWithType(ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                beginMicCapture()
            } else {'''
new = '''            if (mode == MODE_MIC) {
                startForegroundWithType(ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                beginMicCapture()
            } else if (mode == MODE_VISUALIZER) {
                startForegroundWithType(ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                beginVisualizerCapture()
            } else {'''
assert old in s
s = s.replace(old, new)

# 6) beginVisualizerCapture + teardown 释放
old = '''    private fun teardown() {
        analyzer?.stopAnalyzer()
        analyzer = null'''
new = '''    private fun beginVisualizerCapture() {
        startForegroundWithType(ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        val viz = Visualizer(0) // 0 = 输出混音
        viz.captureSize = 1024
        vizProcessor = AudioProcessor(48000)
        viz.setDataCaptureListener(vizCallback, Visualizer.getMaxCaptureRate(), true, false)
        viz.enabled = true
        visualizer = viz
        Log.i(TAG, "系统混音（Visualizer）采集已启动")
    }

    private fun teardown() {
        analyzer?.stopAnalyzer()
        analyzer = null
        try {
            visualizer?.enabled = false
            visualizer?.release()
        } catch (_: Exception) {}
        visualizer = null
        lastVizNanos = 0L'''
assert old in s
s = s.replace(old, new)

# 7) 导入
old = "import android.media.AudioRecord"
new = "import android.media.AudioRecord\nimport android.media.audiofx.Visualizer"
assert old in s
s = s.replace(old, new)

io.open(p, "w", encoding="utf-8").write(s)
print("service patched")
