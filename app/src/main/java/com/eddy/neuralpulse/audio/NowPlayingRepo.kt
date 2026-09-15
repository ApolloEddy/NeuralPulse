package com.eddy.neuralpulse.audio

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationManagerCompat

/**
 * 正在播放的歌曲信息仓库：由 [NowPlayingService] 写入，HUD 轮询读取。
 */
object NowPlayingRepo {
    @Volatile var title: String? = null
    @Volatile var artist: String? = null
    @Volatile var album: String? = null
    @Volatile var playing: Boolean = false

    fun clear() {
        title = null; artist = null; album = null; playing = false
    }
}

/**
 * 正在播放歌曲信息源：通知监听服务 → MediaSessionManager.getActiveSessions
 * → 取处于播放态的会话的 MediaMetadata（歌名/歌手/专辑）。
 *
 * 需要用户在系统设置里给本 App 授予「通知使用权」（一次性授权）。
 */
class NowPlayingService : NotificationListenerService() {

    private val componentName by lazy { ComponentName(this, NowPlayingService::class.java) }
    private val handler = Handler(Looper.getMainLooper())
    private var sessionManager: MediaSessionManager? = null
    private val controllers = ArrayList<MediaController>()

    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { list -> bind(list) }

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = update()
        override fun onPlaybackStateChanged(state: PlaybackState?) = update()
    }

    override fun onListenerConnected() {
        sessionManager = getSystemService(MediaSessionManager::class.java)
        try {
            sessionManager?.addOnActiveSessionsChangedListener(
                sessionsListener, componentName, handler
            )
            bind(sessionManager?.getActiveSessions(componentName))
        } catch (e: SecurityException) {
            // 通知使用权尚未授予：静默等待，用户授权后服务会被重启
        }
    }

    private fun bind(list: List<MediaController>?) {
        for (c in controllers) try { c.unregisterCallback(controllerCallback) } catch (_: Exception) {}
        controllers.clear()
        list?.let { controllers.addAll(it) }
        for (c in controllers) c.registerCallback(controllerCallback, handler)
        update()
    }

    private fun update() {
        val playing = controllers.firstOrNull {
            it.playbackState?.state == PlaybackState.STATE_PLAYING && it.metadata != null
        } ?: controllers.firstOrNull { it.metadata != null }
        if (playing == null) {
            NowPlayingRepo.clear()
            return
        }
        val m = playing.metadata
        NowPlayingRepo.title = m?.getString(MediaMetadata.METADATA_KEY_TITLE)
        NowPlayingRepo.artist = m?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: m?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
        NowPlayingRepo.album = m?.getString(MediaMetadata.METADATA_KEY_ALBUM)
        NowPlayingRepo.playing = playing.playbackState?.state == PlaybackState.STATE_PLAYING
    }

    override fun onDestroy() {
        try { sessionManager?.removeOnActiveSessionsChangedListener(sessionsListener) } catch (_: Exception) {}
        for (c in controllers) try { c.unregisterCallback(controllerCallback) } catch (_: Exception) {}
        controllers.clear()
        NowPlayingRepo.clear()
        super.onDestroy()
    }
}

/** 通知使用权是否已授予本 App。 */
fun isNotificationListenerEnabled(context: Context): Boolean =
    NotificationManagerCompat.getEnabledListenerPackages(context)
        .contains(context.packageName)
