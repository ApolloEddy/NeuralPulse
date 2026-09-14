package com.eddy.neuralpulse.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.eddy.neuralpulse.R

/**
 * 音频采集前台服务，两种来源：
 * 1. MODE_PROJECTION —— MediaProjection 授权 → AudioPlaybackCaptureConfiguration
 *    (USAGE_MEDIA/GAME/UNKNOWN) → AudioRecord。真机上的标准系统音频通路。
 *    （注意：goldfish 模拟器 HAL 不向 REMOTE_SUBMIX 写入混音数据，该路径在
 *    模拟器上读到静音 —— 这是模拟器限制，真机不受影响。）
 * 2. MODE_MIC —— VOICE_RECOGNITION 源直采麦克风。回退通路：真机上部分
 *    场景（免提/外放拾音）可用；模拟器上映射到宿主输入设备。
 */
class AudioCaptureService : Service() {

    companion object {
        const val TAG = "NeuralPulse"
        const val EXTRA_MODE = "mode"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        const val MODE_PROJECTION = "projection"
        const val MODE_MIC = "mic"
        private const val CHANNEL_ID = "neuralpulse_capture"
        private const val NOTIFICATION_ID = 11

        fun startProjection(context: Context, resultCode: Int, resultData: Intent) {
            val intent = Intent(context, AudioCaptureService::class.java)
                .putExtra(EXTRA_MODE, MODE_PROJECTION)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, resultData)
            context.startForegroundService(intent)
        }

        fun startMic(context: Context) {
            val intent = Intent(context, AudioCaptureService::class.java)
                .putExtra(EXTRA_MODE, MODE_MIC)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AudioCaptureService::class.java))
        }
    }

    private var projection: MediaProjection? = null
    private var analyzer: AudioAnalyzer? = null

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            // 用户从系统面板停止投屏/捕获：收尾并回写非活跃状态
            teardown()
            AudioBus.inactive()
            stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // 类型在 onStartCommand 依据模式确定；此处先按投影类型起前台
        // （Android 14+ 要求投影授权结果必须由 mediaProjection 型前台服务消费），
        // 麦克风模式随后升级前台类型即可（startForeground 可再次调用）。
        startForegroundWithType(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val i = intent ?: run {
            stopSelf()
            return START_NOT_STICKY
        }
        val mode = i.getStringExtra(EXTRA_MODE) ?: MODE_PROJECTION
        if (analyzer != null) {
            return START_STICKY // 已在采集：忽略重复启动
        }
        try {
            if (mode == MODE_MIC) {
                startForegroundWithType(ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                beginMicCapture()
            } else {
                val resultCode = i.getIntExtra(EXTRA_RESULT_CODE, -1)
                @Suppress("DEPRECATION")
                val resultData: Intent? = i.getParcelableExtra(EXTRA_RESULT_DATA)
                if (resultCode != android.app.Activity.RESULT_OK || resultData == null) {
                    Log.w(TAG, "捕获授权结果缺失，服务退出")
                    AudioBus.inactive()
                    stopSelf()
                    return START_NOT_STICKY
                }
                beginProjectionCapture(resultCode, resultData)
            }
        } catch (e: Exception) {
            Log.e(TAG, "建立音频采集失败", e)
            AudioBus.inactive()
            stopSelf()
        }
        return START_STICKY
    }

    private fun startForegroundWithType(type: Int) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.capture_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
        )
        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.capture_notification_title))
            .setContentText(getString(R.string.capture_notification_text))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(NOTIFICATION_ID, notification, type)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun beginProjectionCapture(resultCode: Int, resultData: Intent) {
        val p = (getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager)
            .getMediaProjection(resultCode, resultData)
        projection = p
        p.registerCallback(projectionCallback, null)

        val playbackConfig = AudioPlaybackCaptureConfiguration.Builder(p)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val record = AudioRecord.Builder()
            .setAudioFormat(audioFormat())
            .setBufferSizeInBytes(bufferBytes())
            .setAudioPlaybackCaptureConfig(playbackConfig)
            .build()

        analyzer = AudioAnalyzer(record).also { it.start() }
        Log.i(TAG, "系统音频捕获已启动")
    }

    private fun beginMicCapture() {
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            48000,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
            bufferBytes()
        )
        analyzer = AudioAnalyzer(record).also { it.start() }
        Log.i(TAG, "麦克风采集已启动")
    }

    private fun audioFormat() = AudioFormat.Builder()
        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
        .setSampleRate(48000)
        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
        .build()

    private fun bufferBytes(): Int {
        val minBuf = AudioRecord.getMinBufferSize(
            48000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT
        )
        return (if (minBuf < 0) 4800 else minBuf).coerceAtLeast(4800) * 2 // ≥100ms
    }

    private fun teardown() {
        analyzer?.stopAnalyzer()
        analyzer = null
        projection?.unregisterCallback(projectionCallback)
        projection?.stop()
        projection = null
        Log.i(TAG, "音频采集已停止")
    }

    override fun onDestroy() {
        teardown()
        AudioBus.inactive()
        super.onDestroy()
    }
}
