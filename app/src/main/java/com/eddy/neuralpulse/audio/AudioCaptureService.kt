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
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.eddy.neuralpulse.R

/**
 * 系统音频捕获前台服务：
 * 持有 MediaProjection 授权结果 → AudioPlaybackCaptureConfiguration(USAGE_MEDIA/GAME/UNKNOWN)
 * → AudioRecord 浮点 PCM → [AudioAnalyzer] 线程 → [AudioBus] → 渲染层。
 *
 * Android 14+ 要求投影授权结果必须由 mediaProjection 类型前台服务消费，
 * 因此 Activity 只转发 resultCode+data，真正建流在这里完成。
 */
class AudioCaptureService : Service() {

    companion object {
        const val TAG = "NeuralPulse"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        private const val CHANNEL_ID = "neuralpulse_capture"
        private const val NOTIFICATION_ID = 11

        fun start(context: Context, resultCode: Int, resultData: Intent) {
            val intent = Intent(context, AudioCaptureService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, resultData)
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
        startForegroundWithType()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, -1) ?: -1
        @Suppress("DEPRECATION")
        val resultData = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        if (resultCode != android.app.Activity.RESULT_OK || resultData == null) {
            Log.w(TAG, "捕获授权结果缺失，服务退出")
            AudioBus.inactive()
            stopSelf()
            return START_NOT_STICKY
        }
        if (analyzer != null) {
            // 已在捕获中：忽略重复启动
            return START_STICKY
        }
        try {
            beginCapture(resultCode, resultData)
        } catch (e: Exception) {
            Log.e(TAG, "建立音频捕获失败", e)
            AudioBus.inactive()
            stopSelf()
        }
        return START_STICKY
    }

    private fun startForegroundWithType() {
        val nm = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.capture_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        nm.createNotificationChannel(channel)
        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.capture_notification_title))
            .setContentText(getString(R.string.capture_notification_text))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(
                NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun beginCapture(resultCode: Int, resultData: Intent) {
        val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val p = pm.getMediaProjection(resultCode, resultData)
        projection = p
        p.registerCallback(projectionCallback, null)

        val playbackConfig = AudioPlaybackCaptureConfiguration.Builder(p)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(48000)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()

        val minBuf = AudioRecord.getMinBufferSize(
            48000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT
        ).coerceAtLeast(48000 / 10) // ≥100ms

        val record = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(minBuf * 2)
            .setAudioPlaybackCaptureConfig(playbackConfig)
            .build()

        analyzer = AudioAnalyzer(record).also { it.start() }
        Log.i(TAG, "系统音频捕获已启动")
    }

    private fun teardown() {
        analyzer?.stopAnalyzer()
        analyzer = null
        projection?.unregisterCallback(projectionCallback)
        projection?.stop()
        projection = null
        Log.i(TAG, "系统音频捕获已停止")
    }

    override fun onDestroy() {
        teardown()
        AudioBus.inactive()
        super.onDestroy()
    }
}
