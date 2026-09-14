package com.eddy.neuralpulse

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.eddy.neuralpulse.audio.AudioBus
import com.eddy.neuralpulse.audio.AudioCaptureService
import com.eddy.neuralpulse.audio.AudioFeatures
import com.eddy.neuralpulse.audio.AudioProcessor
import com.eddy.neuralpulse.audio.DemoSignalSource
import kotlinx.coroutines.delay

/**
 * NeuralPulse：Loyea「Neural Living」神经网模型的独立音频律动壳。
 * 三种音源：系统音频捕获（MediaProjection，真机通路）/
 *          麦克风输入（回退通路）/ 演示脉冲（合成 PCM 走真分析管线）。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { NeuralPulseApp() }
    }
}

private enum class Mode { IDLE, CAPTURE, MIC, DEMO }

@Composable
private fun NeuralPulseApp() {
    val context = LocalContext.current
    var mode by remember { mutableStateOf(Mode.IDLE) }
    var hud by remember { mutableStateOf(AudioBus.features) }

    // HUD 状态轮询（分析线程写 volatile 快照，这里低频取回驱动重组）
    LaunchedEffect(Unit) {
        while (true) {
            hud = AudioBus.features
            delay(80)
        }
    }

    // 演示脉冲：合成 120BPM PCM 喂进真正的 AudioProcessor 分析链
    LaunchedEffect(mode) {
        if (mode != Mode.DEMO) return@LaunchedEffect
        val source = DemoSignalSource(48000) { hop ->
            demoProcessor.processFrame(hop, active = false)
        }
        source.start()
        try {
            while (true) delay(1000)
        } finally {
            source.stopSource()
        }
    }

    // ---- 捕获授权流（MediaProjection）----
    val projectionIntent = remember {
        (context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager)
            ?.createScreenCaptureIntent()
    }
    val projection = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            AudioCaptureService.startProjection(context, result.resultCode, result.data!!)
            mode = Mode.CAPTURE
        } else {
            mode = Mode.IDLE
        }
    }

    // 麦克风权限 → 启动麦克风采集
    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            AudioCaptureService.startMic(context)
            mode = Mode.MIC
        } else {
            mode = Mode.IDLE
        }
    }

    // 通知权限（33+，保证前台服务通知可见）；授权后自动继续投影流程
    val notifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted || Build.VERSION.SDK_INT < 33) projectionIntent?.let { projection.launch(it) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF050608))
    ) {
        NeuralLivingStage(modifier = Modifier.fillMaxSize())

        HudPanel(
            features = hud,
            mode = mode,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(12.dp)
        )

        Controls(
            mode = mode,
            onStartCapture = {
                if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                        context, Manifest.permission.POST_NOTIFICATIONS
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    projectionIntent?.let { projection.launch(it) }
                }
            },
            onStartMic = {
                if (ContextCompat.checkSelfPermission(
                        context, Manifest.permission.RECORD_AUDIO
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    micPermission.launch(Manifest.permission.RECORD_AUDIO)
                } else {
                    AudioCaptureService.startMic(context)
                    mode = Mode.MIC
                }
            },
            onStartDemo = { mode = Mode.DEMO },
            onStop = {
                AudioCaptureService.stop(context)
                AudioBus.inactive()
                mode = Mode.IDLE
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 34.dp)
        )
    }
}

// 演示模式用的分析器（与捕获分析同一条 DSP 链）
private val demoProcessor = AudioProcessor(48000)

@Composable
private fun HudPanel(
    features: AudioFeatures,
    mode: Mode,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(Color(0xCC0B0D10), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        val status = when {
            features.active && mode == Mode.CAPTURE -> "● 系统音频捕获中"
            features.active && mode == Mode.MIC -> "● 麦克风输入中"
            mode == Mode.DEMO -> "◐ 演示脉冲 · 真分析管线"
            else -> "○ 待捕获系统音频"
        }
        val statusColor = when {
            features.active -> Color(0xFFFFAF37)
            mode == Mode.DEMO -> Color(0xFF8AB4FF)
            else -> Color(0xFF6E6E6E)
        }
        Text(status, color = statusColor, fontSize = 12.sp, fontWeight = FontWeight.Medium)

        Text(
            text = if (features.bpm > 0f) "BPM %5.1f".format(features.bpm) else "BPM  --",
            color = Color(0xFFFFE4AF),
            fontSize = 22.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
        )

        MeterRow("响度", features.level, Color(0xFFFFAF37))
        MeterRow("低频", features.bass, Color(0xFFFF7A2F))
        MeterRow("中频", features.mid, Color(0xFFFFC94B))
        MeterRow("高频", features.treble, Color(0xFFB8E0FF))
    }
}

@Composable
private fun MeterRow(label: String, value: Float, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            color = Color(0xFF998771),
            fontSize = 10.sp,
            modifier = Modifier.width(30.dp)
        )
        Box(
            Modifier
                .width(110.dp)
                .height(5.dp)
                .background(Color(0x22FFFFFF), RoundedCornerShape(3.dp))
        ) {
            Box(
                Modifier
                    .fillMaxWidth(value.coerceIn(0.01f, 1f))
                    .height(5.dp)
                    .background(color, RoundedCornerShape(3.dp))
            )
        }
        Spacer(Modifier.width(6.dp))
        Text(
            text = "%3.0f".format(value * 100f),
            color = Color(0xFF998771),
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun Controls(
    mode: Mode,
    onStartCapture: () -> Unit,
    onStartMic: () -> Unit,
    onStartDemo: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when (mode) {
            Mode.IDLE -> {
                ActionButton("● 捕获系统音频", Color(0xFF32200D), Color(0xFFFFAF37), onStartCapture)
                ActionButton("🎙 麦克风", Color(0xFF1A1206), Color(0xFFFFC94B), onStartMic)
                ActionButton("▷ 演示", Color(0xFF101820), Color(0xFF8AB4FF), onStartDemo)
            }
            else -> ActionButton(
                "■ 停止",
                when (mode) {
                    Mode.DEMO -> Color(0xFF101820)
                    else -> Color(0xFF32200D)
                },
                when (mode) {
                    Mode.DEMO -> Color(0xFF8AB4FF)
                    else -> Color(0xFFFFAF37)
                },
                onStop
            )
        }
    }
}

@Composable
private fun ActionButton(text: String, bg: Color, content: Color, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = bg, contentColor = content),
        shape = RoundedCornerShape(18.dp)
    ) {
        Text(text, fontSize = 13.sp, letterSpacing = 1.sp)
    }
}
