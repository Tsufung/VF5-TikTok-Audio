package com.vf5plus.media

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity(), WebAudioEngine.EngineListener {

    private lateinit var engine: WebAudioEngine
    private lateinit var webViewContainer: FrameLayout
    private lateinit var btnPlayPause: Button
    private lateinit var tvAudioStatus: TextView
    private lateinit var tvMetadata: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Request POST_NOTIFICATIONS on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        // Start VF5MediaBrowserService as foreground service
        val serviceIntent = Intent(this, VF5MediaBrowserService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(this, serviceIntent)
        } else {
            startService(serviceIntent)
        }

        engine = WebAudioEngine.getInstance(this)
        engine.listener = this

        webViewContainer = findViewById(R.id.webViewContainer)
        btnPlayPause = findViewById(R.id.btnPlayPause)
        tvAudioStatus = findViewById(R.id.tvAudioStatus)
        tvMetadata = findViewById(R.id.tvMetadata)

        val btnTikTok: Button = findViewById(R.id.btnTikTok)
        val btnYouTube: Button = findViewById(R.id.btnYouTube)
        val btnTestNext: Button = findViewById(R.id.btnTestNext)
        val btnStopAll: Button = findViewById(R.id.btnStopAll)

        btnTikTok.setOnClickListener {
            engine.loadTikTokForYou()
        }

        btnYouTube.setOnClickListener {
            engine.loadYouTube()
        }

        btnTestNext.setOnClickListener {
            engine.skipNext()
        }

        btnPlayPause.setOnClickListener {
            if (engine.isPlaying) {
                engine.pause()
            } else {
                engine.play()
            }
        }

        btnStopAll.setOnClickListener {
            engine.stopAndRelease()
            val stopIntent = Intent(this, VF5MediaBrowserService::class.java)
            stopService(stopIntent)
            finishAffinity()
            android.os.Process.killProcess(android.os.Process.myPid())
        }

        updateUiState(engine.currentAuthor, engine.currentTitle, engine.isPlaying)
    }

    override fun onStart() {
        super.onStart()
        // Gắn BackgroundWebView vào màn hình điện thoại để người dùng xem và đăng nhập
        engine.attachToActivity(webViewContainer)
    }

    override fun onStop() {
        // Tháo BackgroundWebView khỏi màn hình điện thoại và gắn lại vào VirtualDisplay ngầm
        engine.detachFromActivity()
        super.onStop()
    }

    override fun onDestroy() {
        if (isFinishing) {
            engine.stopAndRelease()
            val stopIntent = Intent(this, VF5MediaBrowserService::class.java)
            stopService(stopIntent)
        }
        super.onDestroy()
    }

    // ─── EngineListener ───────────────────────────────────────────────────────

    override fun onMetadataUpdated(author: String, title: String) {
        runOnUiThread {
            updateUiState(author, title, engine.isPlaying)
        }
    }

    override fun onPlaybackStateChanged(isPlaying: Boolean) {
        runOnUiThread {
            updateUiState(engine.currentAuthor, engine.currentTitle, isPlaying)
        }
    }

    private fun updateUiState(author: String, title: String, isPlaying: Boolean) {
        tvAudioStatus.text = if (isPlaying) "🔊 Âm thanh nền xe VF5: Đang phát" else "⏸ Âm thanh nền xe VF5: Tạm dừng"
        tvMetadata.text = "$author • $title"
        btnPlayPause.text = if (isPlaying) "Tạm dừng" else "Phát"
    }
}
