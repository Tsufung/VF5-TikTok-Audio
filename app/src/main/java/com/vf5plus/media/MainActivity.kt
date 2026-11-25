package com.vf5plus.media

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var ledAccessibility: View
    private lateinit var tvAccessibilityStatus: TextView
    private lateinit var ledMediaService: View
    private lateinit var tvMediaServiceStatus: TextView
    private lateinit var tvLiveInfo: TextView
    private lateinit var switchKeepScreenOn: SwitchCompat
    private lateinit var btnTestSwipe: Button

    private val mainHandler = Handler(Looper.getMainLooper())
    private var testCountdownRunnable: Runnable? = null

    private val statusRefreshRunnable = object : Runnable {
        override fun run() {
            refreshStatusIndicators()
            mainHandler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        requestNotificationPermissionIfNeeded()

        ledAccessibility = findViewById(R.id.ledAccessibility)
        tvAccessibilityStatus = findViewById(R.id.tvAccessibilityStatus)
        ledMediaService = findViewById(R.id.ledMediaService)
        tvMediaServiceStatus = findViewById(R.id.tvMediaServiceStatus)
        tvLiveInfo = findViewById(R.id.tvLiveInfo)
        switchKeepScreenOn = findViewById(R.id.switchKeepScreenOn)
        btnTestSwipe = findViewById(R.id.btnTestSwipe)

        val btnAccessibility: Button = findViewById(R.id.btnAccessibility)
        val btnStartRemote: Button = findViewById(R.id.btnStartRemote)
        val btnOpenTikTok: Button = findViewById(R.id.btnOpenTikTok)
        val btnStopRemote: Button = findViewById(R.id.btnStopRemote)

        switchKeepScreenOn.isChecked = TikTokSwipeAccessibilityService.keepScreenOnEnabled
        switchKeepScreenOn.setOnCheckedChangeListener { _, isChecked ->
            TikTokSwipeAccessibilityService.keepScreenOnEnabled = isChecked
            TikTokSwipeAccessibilityService.refreshKeepScreenOnState()
        }

        btnAccessibility.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
            Toast.makeText(
                this,
                "Tìm 'VF5 TikTok Remote' trong Dịch vụ đã cài đặt và bật lên",
                Toast.LENGTH_LONG
            ).show()
        }

        btnStartRemote.setOnClickListener {
            startRemoteMediaService()
            Toast.makeText(this, "Đã kích hoạt nhận phím vô-lăng Android Auto", Toast.LENGTH_SHORT).show()
            refreshStatusIndicators()
        }

        btnOpenTikTok.setOnClickListener {
            if (!TikTokLauncher.openTikTokApp(this)) {
                Toast.makeText(this, "Không tìm thấy ứng dụng TikTok trên máy", Toast.LENGTH_SHORT).show()
            }
        }

        btnTestSwipe.setOnClickListener {
            startFourSecondSwipeTest()
        }

        btnStopRemote.setOnClickListener {
            val stopIntent = Intent(this, VF5MediaBrowserService::class.java).apply {
                action = VF5MediaBrowserService.ACTION_STOP_REMOTE
            }
            startService(stopIntent)
            Toast.makeText(this, "Đã tắt service nhận phím vô-lăng", Toast.LENGTH_SHORT).show()
            mainHandler.postDelayed({ refreshStatusIndicators() }, 300L)
        }

        refreshStatusIndicators()
    }

    override fun onResume() {
        super.onResume()
        mainHandler.post(statusRefreshRunnable)
    }

    override fun onPause() {
        mainHandler.removeCallbacks(statusRefreshRunnable)
        super.onPause()
    }

    override fun onDestroy() {
        testCountdownRunnable?.let { mainHandler.removeCallbacks(it) }
        super.onDestroy()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }
    }

    private fun startRemoteMediaService() {
        val serviceIntent = Intent(this, VF5MediaBrowserService::class.java).apply {
            action = VF5MediaBrowserService.ACTION_START_REMOTE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(this, serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun startFourSecondSwipeTest() {
        if (!TikTokSwipeAccessibilityService.isAccessibilityEnabled(this)) {
            Toast.makeText(this, "Hãy bật quyền Hỗ trợ tiếp cận trước khi test vuốt!", Toast.LENGTH_LONG).show()
            return
        }

        testCountdownRunnable?.let { mainHandler.removeCallbacks(it) }

        var remainingSeconds = 4
        Toast.makeText(
            this,
            "Chuyển sang TikTok ngay! Sẽ tự động vuốt sau 4 giây...",
            Toast.LENGTH_LONG
        ).show()

        val runnable = object : Runnable {
            override fun run() {
                if (remainingSeconds > 0) {
                    btnTestSwipe.text = "Đang đếm ngược: ${remainingSeconds}s (mở TikTok ngay)..."
                    remainingSeconds--
                    mainHandler.postDelayed(this, 1000L)
                } else {
                    btnTestSwipe.text = "4. Test vuốt sau 4 giây"
                    val swiped = TikTokSwipeAccessibilityService.swipeNext()
                    val msg = if (swiped) {
                        "Đã thực hiện vuốt chuyển clip TikTok!"
                    } else {
                        "Đã hủy vuốt vì bạn chưa mở app TikTok trên màn hình."
                    }
                    Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
                }
            }
        }
        testCountdownRunnable = runnable
        mainHandler.post(runnable)
    }

    private fun refreshStatusIndicators() {
        val a11yOn = TikTokSwipeAccessibilityService.isAccessibilityEnabled(this)
        setLedColor(ledAccessibility, if (a11yOn) "#22C55E" else "#EF4444")
        tvAccessibilityStatus.text = if (a11yOn) "Đã bật" else "Chưa bật"
        tvAccessibilityStatus.setTextColor(Color.parseColor(if (a11yOn) "#4ADE80" else "#F87171"))

        val serviceOn = VF5MediaBrowserService.isRunning
        setLedColor(ledMediaService, if (serviceOn) "#22C55E" else "#6B7280")
        tvMediaServiceStatus.text = if (serviceOn) "Đang chạy" else "Chưa kích hoạt"
        tvMediaServiceStatus.setTextColor(Color.parseColor(if (serviceOn) "#4ADE80" else "#9CA3AF"))

        val fgPkg = TikTokSwipeAccessibilityService.currentPackageName ?: "Chưa xác định"
        val onTikTok = if (TikTokSwipeAccessibilityService.isTikTokForeground()) "Đang mở TikTok" else "App hiện tại: $fgPkg"
        tvLiveInfo.text = "TikTok Chính Chủ — Video #${VF5MediaBrowserService.clipCounter} • $onTikTok"
    }

    private fun setLedColor(view: View, hexColor: String) {
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor(hexColor))
        }
    }
}
