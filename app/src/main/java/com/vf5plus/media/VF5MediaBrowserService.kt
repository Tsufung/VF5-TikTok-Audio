package com.vf5plus.media

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.media.MediaBrowserServiceCompat
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.media.session.MediaButtonReceiver

private const val TAG = "VF5MediaRemoteService"
private const val CHANNEL_ID = "vf5_tiktok_remote_channel"
private const val NOTIFICATION_ID = 101

/**
 * Dịch vụ Media chuẩn Android Auto:
 * - Phát luồng AudioTrack PCM toàn số 0 (im lặng 100%, setVolume(0f), KHÔNG gọi requestAudioFocus)
 *   để giữ quyền nhận phím cứng vô-lăng mà không làm tạm dừng ứng dụng TikTok chính chủ.
 * - Chuyển tiếp các lệnh Next / Previous / Play / Pause từ vô-lăng sang TikTokSwipeAccessibilityService.
 */
class VF5MediaBrowserService : MediaBrowserServiceCompat() {

    companion object {
        const val ACTION_START_REMOTE = "com.vf5plus.media.ACTION_START_REMOTE"
        const val ACTION_STOP_REMOTE = "com.vf5plus.media.ACTION_STOP_REMOTE"

        private const val MEDIA_ID_ENABLE_REMOTE = "item_enable_tiktok_remote"
        private const val MEDIA_ID_OPEN_TIKTOK = "item_open_tiktok_app"
        private const val MEDIA_ID_SWIPE_NEXT = "item_swipe_next_video"

        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        var clipCounter: Int = 1
            private set
    }

    private lateinit var mediaSession: MediaSessionCompat
    private var isPlayingState: Boolean = true
    private var lastSubtitle: String = "Bấm vô-lăng để lướt clip"

    @Volatile
    private var isSilentTrackActive: Boolean = false
    private var silentAudioThread: Thread? = null
    private var silentAudioTrack: AudioTrack? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createNotificationChannel()

        mediaSession = MediaSessionCompat(this, "VF5TikTokRemoteSession").apply {
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            )

            val mediaButtonIntent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
                setClass(this@VF5MediaBrowserService, MediaButtonReceiver::class.java)
            }
            val mediaButtonPendingIntent = PendingIntent.getBroadcast(
                this@VF5MediaBrowserService,
                0,
                mediaButtonIntent,
                PendingIntent.FLAG_IMMUTABLE
            )
            setMediaButtonReceiver(mediaButtonPendingIntent)
            setCallback(MediaSessionCallback())
            isActive = true
            setSessionToken(sessionToken)
        }

        startSilentAudioLoop()
        updateMetadataAndNotification()
        updatePlaybackState(isPlayingState)

        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_REMOTE) {
            shutdownService()
            return START_NOT_STICKY
        }

        MediaButtonReceiver.handleIntent(mediaSession, intent)
        ensureRemoteActive()
        return START_STICKY
    }

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?
    ): BrowserRoot {
        return BrowserRoot("vf5_tiktok_remote_root", null)
    }

    override fun onLoadChildren(
        parentId: String,
        result: Result<MutableList<MediaBrowserCompat.MediaItem>>
    ) {
        val items = mutableListOf<MediaBrowserCompat.MediaItem>()

        fun menuItem(id: String, title: String, subtitle: String): MediaBrowserCompat.MediaItem {
            val desc = MediaDescriptionCompat.Builder()
                .setMediaId(id)
                .setTitle(title)
                .setSubtitle(subtitle)
                .build()
            return MediaBrowserCompat.MediaItem(desc, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE)
        }

        items.add(
            menuItem(
                MEDIA_ID_ENABLE_REMOTE,
                "Bật điều khiển TikTok",
                "Kích hoạt luồng giữ phím vô-lăng"
            )
        )
        items.add(
            menuItem(
                MEDIA_ID_OPEN_TIKTOK,
                "Mở app TikTok trên điện thoại",
                "Khởi chạy TikTok chính chủ trên điện thoại"
            )
        )
        items.add(
            menuItem(
                MEDIA_ID_SWIPE_NEXT,
                "Lướt video tiếp theo",
                "Chuyển sang clip TikTok kế tiếp ngay"
            )
        )

        result.sendResult(items)
    }

    // ─── Luồng AudioTrack Im Lặng (Không gọi requestAudioFocus) ───────────────

    @Synchronized
    private fun startSilentAudioLoop() {
        if (isSilentTrackActive) return
        isSilentTrackActive = true

        silentAudioThread = Thread({
            var track: AudioTrack? = null
            try {
                val sampleRate = 8000
                val channelMask = AudioFormat.CHANNEL_OUT_MONO
                val encoding = AudioFormat.ENCODING_PCM_16BIT
                val bufferSize = AudioTrack.getMinBufferSize(sampleRate, channelMask, encoding)
                    .coerceAtLeast(4096)

                track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(encoding)
                            .setSampleRate(sampleRate)
                            .setChannelMask(channelMask)
                            .build()
                    )
                    .setBufferSizeInBytes(bufferSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

                track.setVolume(0f)
                track.play()
                silentAudioTrack = track

                val silenceBuffer = ByteArray(bufferSize)
                while (isSilentTrackActive && !Thread.currentThread().isInterrupted) {
                    val result = track.write(silenceBuffer, 0, silenceBuffer.size)
                    if (result < 0) {
                        Log.w(TAG, "Silent AudioTrack write error: $result")
                        break
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Silent AudioTrack loop error: ${e.message}")
            } finally {
                try {
                    track?.pause()
                    track?.flush()
                    track?.stop()
                    track?.release()
                } catch (_: Exception) {}
                silentAudioTrack = null
            }
        }, "VF5SilentAudioThread").apply {
            isDaemon = true
            start()
        }
    }

    @Synchronized
    private fun stopSilentAudioLoop() {
        isSilentTrackActive = false
        try {
            silentAudioTrack?.pause()
            silentAudioTrack?.flush()
        } catch (_: Exception) {}
        silentAudioThread?.interrupt()
        silentAudioThread = null
    }

    private fun ensureRemoteActive() {
        startSilentAudioLoop()
        mediaSession.isActive = true
        isPlayingState = true
        lastSubtitle = "Bấm vô-lăng để lướt clip"
        updatePlaybackState(true)
        updateMetadataAndNotification()
    }

    // ─── Hành Động Điều Khiển TikTok ─────────────────────────────────────────

    private fun handleSwipeNext() {
        val swiped = TikTokSwipeAccessibilityService.swipeNext()
        if (swiped) {
            clipCounter++
            lastSubtitle = "Bấm vô-lăng để lướt clip"
        } else {
            lastSubtitle = diagnosticBlockedReason()
        }
        isPlayingState = true
        updatePlaybackState(true)
        updateMetadataAndNotification()
    }

    private fun handleSwipePrevious() {
        val swiped = TikTokSwipeAccessibilityService.swipePrevious()
        if (swiped) {
            if (clipCounter > 1) clipCounter--
            lastSubtitle = "Bấm vô-lăng để lướt clip"
        } else {
            lastSubtitle = diagnosticBlockedReason()
        }
        isPlayingState = true
        updatePlaybackState(true)
        updateMetadataAndNotification()
    }

    private fun handlePlayPauseToggle(targetPlaying: Boolean) {
        val tapped = TikTokSwipeAccessibilityService.tapCenter()
        lastSubtitle = if (tapped) {
            "Bấm vô-lăng để lướt clip"
        } else {
            diagnosticBlockedReason()
        }
        isPlayingState = targetPlaying
        updatePlaybackState(targetPlaying)
        updateMetadataAndNotification()
    }

    private fun diagnosticBlockedReason(): String {
        return if (!TikTokSwipeAccessibilityService.isAccessibilityEnabled(this)) {
            "Chưa bật Hỗ trợ tiếp cận trên điện thoại"
        } else {
            "Chưa mở TikTok (đã chặn vuốt nhầm app khác)"
        }
    }

    // ─── Cập Nhật MediaSession & Notification ─────────────────────────────────

    private fun updateMetadataAndNotification() {
        val title = "TikTok Chính Chủ — Video #$clipCounter"
        val metadata = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, lastSubtitle)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, lastSubtitle)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, "VF5 TikTok Remote")
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, -1L)
            .build()
        mediaSession.setMetadata(metadata)

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun updatePlaybackState(isPlaying: Boolean) {
        val state = if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        val playbackState = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_PLAY_PAUSE or
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackStateCompat.ACTION_STOP
            )
            .setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1.0f)
            .build()
        mediaSession.isActive = true
        mediaSession.setPlaybackState(playbackState)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "VF5 TikTok Remote",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Nhận phím vô-lăng Android Auto điều khiển TikTok chính chủ"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java)
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val prevIntent = MediaButtonReceiver.buildMediaButtonPendingIntent(
            this,
            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
        )
        val playPauseAction = if (isPlayingState) {
            PlaybackStateCompat.ACTION_PAUSE
        } else {
            PlaybackStateCompat.ACTION_PLAY
        }
        val playPauseIntent = MediaButtonReceiver.buildMediaButtonPendingIntent(this, playPauseAction)
        val nextIntent = MediaButtonReceiver.buildMediaButtonPendingIntent(
            this,
            PlaybackStateCompat.ACTION_SKIP_TO_NEXT
        )
        val stopIntent = MediaButtonReceiver.buildMediaButtonPendingIntent(
            this,
            PlaybackStateCompat.ACTION_STOP
        )

        val playPauseIcon = if (isPlayingState) {
            android.R.drawable.ic_media_pause
        } else {
            android.R.drawable.ic_media_play
        }
        val playPauseLabel = if (isPlayingState) "Tạm dừng" else "Phát"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("TikTok Chính Chủ — Video #$clipCounter")
            .setContentText(lastSubtitle)
            .setSubText("VF5 TikTok Remote")
            .setContentIntent(contentPendingIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(android.R.drawable.ic_media_previous, "Trước", prevIntent)
            .addAction(playPauseIcon, playPauseLabel, playPauseIntent)
            .addAction(android.R.drawable.ic_media_next, "Tiếp", nextIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Tắt", stopIntent)
            .setStyle(
                MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .setOngoing(true)
            .build()
    }

    private fun shutdownService() {
        stopSilentAudioLoop()
        try {
            mediaSession.isActive = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.cancel(NOTIFICATION_ID)
        } catch (_: Exception) {}
        isRunning = false
        stopSelf()
    }

    // ─── MediaSession Callback ────────────────────────────────────────────────

    private inner class MediaSessionCallback : MediaSessionCompat.Callback() {

        override fun onSkipToNext() {
            handleSwipeNext()
        }

        override fun onSkipToPrevious() {
            handleSwipePrevious()
        }

        override fun onPlay() {
            handlePlayPauseToggle(targetPlaying = true)
        }

        override fun onPause() {
            handlePlayPauseToggle(targetPlaying = false)
        }

        override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
            when (mediaId) {
                MEDIA_ID_ENABLE_REMOTE -> ensureRemoteActive()
                MEDIA_ID_OPEN_TIKTOK -> {
                    ensureRemoteActive()
                    TikTokLauncher.openTikTokApp(this@VF5MediaBrowserService)
                }
                MEDIA_ID_SWIPE_NEXT -> handleSwipeNext()
                else -> ensureRemoteActive()
            }
        }

        override fun onStop() {
            shutdownService()
        }
    }

    override fun onDestroy() {
        stopSilentAudioLoop()
        isRunning = false
        try {
            mediaSession.release()
        } catch (_: Exception) {}
        super.onDestroy()
    }
}
