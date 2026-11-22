package com.vf5plus.media

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.media.MediaBrowserServiceCompat
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.media.session.MediaButtonReceiver

private const val CHANNEL_ID = "vf5_audio_channel"
private const val NOTIFICATION_ID = 101

class VF5MediaBrowserService : MediaBrowserServiceCompat(), WebAudioEngine.EngineListener {

    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var engine: WebAudioEngine

    override fun onCreate() {
        super.onCreate()

        createNotificationChannel()

        engine = WebAudioEngine.getInstance(this)
        engine.listener = this

        mediaSession = MediaSessionCompat(this, "VF5AudioMediaSession").apply {
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
            setSessionToken(sessionToken)
        }

        updatePlaybackState(engine.isPlaying)
        updateMetadata(engine.currentAuthor, engine.currentTitle)

        startForeground(NOTIFICATION_ID, buildNotification(engine.currentAuthor, engine.currentTitle, engine.isPlaying))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        MediaButtonReceiver.handleIntent(mediaSession, intent)
        return START_STICKY
    }

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?
    ): BrowserRoot {
        // Cho phép kết nối từ Android Auto và mọi media controller hợp lệ
        return BrowserRoot("vf5_root_media_id", null)
    }

    override fun onLoadChildren(
        parentId: String,
        result: Result<MutableList<MediaBrowserCompat.MediaItem>>
    ) {
        val mediaItems = mutableListOf<MediaBrowserCompat.MediaItem>()

        fun buildItem(id: String, title: String, subtitle: String): MediaBrowserCompat.MediaItem {
            val desc = MediaDescriptionCompat.Builder()
                .setMediaId(id)
                .setTitle(title)
                .setSubtitle(subtitle)
                .build()
            return MediaBrowserCompat.MediaItem(desc, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE)
        }

        mediaItems.add(buildItem("item_tiktok_foryou", "TikTok — Dành cho bạn", "Dành cho bạn trên TikTok"))
        mediaItems.add(buildItem("item_tiktok_explore", "TikTok — Khám phá", "Kênh khám phá xu hướng TikTok"))
        mediaItems.add(buildItem("item_youtube", "YouTube", "Trang chủ YouTube Mobile"))
        mediaItems.add(buildItem("item_reload", "Tải lại nguồn phát", "Làm mới lại trang web hiện tại"))

        result.sendResult(mediaItems)
    }

    // ─── Engine Callbacks ─────────────────────────────────────────────────────

    override fun onMetadataUpdated(author: String, title: String) {
        updateMetadata(author, title)
        val notif = buildNotification(author, title, engine.isPlaying)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notif)
    }

    override fun onPlaybackStateChanged(isPlaying: Boolean) {
        updatePlaybackState(isPlaying)
        val notif = buildNotification(engine.currentAuthor, engine.currentTitle, isPlaying)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notif)
    }

    private fun updateMetadata(author: String, title: String) {
        val metadata = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, author)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, "VF5 Audio")
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, -1L)
            .build()
        mediaSession.setMetadata(metadata)
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
            .setState(state, 0L, 1.0f)
            .build()
        mediaSession.setPlaybackState(playbackState)
    }

    // ─── Notification Channel & MediaStyle Notification ───────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "VF5 Audio Playback",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Kênh phát âm thanh TikTok & YouTube trên xe VF5"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(author: String, title: String, isPlaying: Boolean): Notification {
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
        val playPauseAction = if (isPlaying) {
            PlaybackStateCompat.ACTION_PAUSE
        } else {
            PlaybackStateCompat.ACTION_PLAY
        }
        val playPauseIntent = MediaButtonReceiver.buildMediaButtonPendingIntent(this, playPauseAction)
        val nextIntent = MediaButtonReceiver.buildMediaButtonPendingIntent(
            this,
            PlaybackStateCompat.ACTION_SKIP_TO_NEXT
        )

        val playPauseIcon = if (isPlaying) {
            android.R.drawable.ic_media_pause
        } else {
            android.R.drawable.ic_media_play
        }
        val playPauseTitle = if (isPlaying) "Tạm dừng" else "Phát"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(author)
            .setSubText("VF5 Audio")
            .setContentIntent(contentPendingIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(android.R.drawable.ic_media_previous, "Trước", prevIntent)
            .addAction(playPauseIcon, playPauseTitle, playPauseIntent)
            .addAction(android.R.drawable.ic_media_next, "Sau", nextIntent)
            .setStyle(
                MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .setOngoing(isPlaying)
            .build()
    }

    // ─── MediaSession Callback (Vô-lăng & Màn hình xe) ────────────────────────

    private inner class MediaSessionCallback : MediaSessionCompat.Callback() {

        override fun onPlay() {
            engine.play()
        }

        override fun onPause() {
            engine.pause()
        }

        override fun onSkipToNext() {
            engine.skipNext()
        }

        override fun onSkipToPrevious() {
            engine.skipPrevious()
        }

        override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
            when (mediaId) {
                "item_tiktok_foryou" -> engine.loadTikTokForYou()
                "item_tiktok_explore" -> engine.loadTikTokExplore()
                "item_youtube" -> engine.loadYouTube()
                "item_reload" -> engine.reload()
                else -> engine.play()
            }
        }

        override fun onStop() {
            engine.pause()
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }

    override fun onDestroy() {
        mediaSession.release()
        super.onDestroy()
    }
}
