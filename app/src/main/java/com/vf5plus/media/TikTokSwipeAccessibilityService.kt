package com.vf5plus.media

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

private const val TAG = "TikTokSwipeA11y"

/**
 * Dịch vụ Hỗ trợ tiếp cận nhận lệnh từ vô-lăng Android Auto để giả lập vuốt/chạm
 * trên ứng dụng TikTok chính chủ và giữ sáng màn hình khi TikTok đang hiển thị.
 */
class TikTokSwipeAccessibilityService : AccessibilityService() {

    companion object {
        private val VALID_TIKTOK_PACKAGES = TikTokLauncher.TIKTOK_PACKAGES.toSet()

        @Volatile
        var instance: TikTokSwipeAccessibilityService? = null
            private set

        @Volatile
        var currentPackageName: String? = null
            private set

        @Volatile
        var keepScreenOnEnabled: Boolean = true

        /**
         * Kiểm tra nhanh xem AccessibilityService đã được bật trong Cài đặt hệ thống hay chưa.
         */
        fun isAccessibilityEnabled(context: Context): Boolean {
            if (instance != null) return true
            return try {
                val enabled = Settings.Secure.getInt(
                    context.contentResolver,
                    Settings.Secure.ACCESSIBILITY_ENABLED,
                    0
                )
                if (enabled != 1) return false
                val expectedName = "${context.packageName}/${TikTokSwipeAccessibilityService::class.java.name}"
                val settingValue = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ) ?: return false
                val splitter = TextUtils.SimpleStringSplitter(':')
                splitter.setString(settingValue)
                while (splitter.hasNext()) {
                    if (splitter.next().equals(expectedName, ignoreCase = true)) {
                        return true
                    }
                }
                false
            } catch (e: Exception) {
                false
            }
        }

        fun isTikTokForeground(): Boolean {
            val pkg = currentPackageName ?: return false
            return pkg in VALID_TIKTOK_PACKAGES
        }

        fun swipeNext(): Boolean {
            val service = instance ?: return false
            return service.performTikTokSwipe(isNext = true)
        }

        fun swipePrevious(): Boolean {
            val service = instance ?: return false
            return service.performTikTokSwipe(isNext = false)
        }

        fun tapCenter(): Boolean {
            val service = instance ?: return false
            return service.performTikTokTapCenter()
        }

        fun refreshKeepScreenOnState() {
            instance?.updateKeepScreenOnOverlay()
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var keepAwakeView: View? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "TikTokSwipeAccessibilityService connected")
        updateKeepScreenOnOverlay()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString() ?: return
            val cls = event.className?.toString().orEmpty()

            // Bỏ qua các popup thoáng qua (Toast / thanh âm lượng hệ thống) để không làm gián đoạn lướt TikTok
            if (cls.contains("Toast", ignoreCase = true) || cls.contains("Volume", ignoreCase = true)) {
                return
            }

            if (currentPackageName != pkg) {
                currentPackageName = pkg
                Log.d(TAG, "Foreground app changed to: $pkg")
                updateKeepScreenOnOverlay()
            }
        }
    }

    override fun onInterrupt() {
        removeKeepScreenOnOverlay()
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        removeKeepScreenOnOverlay()
        instance = null
        currentPackageName = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        removeKeepScreenOnOverlay()
        instance = null
        super.onDestroy()
    }

    // ─── Cử chỉ giả lập an toàn (chỉ vuốt khi TikTok đang mở) ────────────────

    private fun performTikTokSwipe(isNext: Boolean): Boolean {
        if (!isTikTokForeground()) {
            Log.w(TAG, "Blocked swipe: foreground package '$currentPackageName' is not TikTok.")
            return false
        }

        mainHandler.post {
            val (width, height) = getRealScreenSize()
            val x = width * 0.5f
            val startY = if (isNext) height * 0.75f else height * 0.25f
            val endY = if (isNext) height * 0.25f else height * 0.75f

            val path = Path().apply {
                moveTo(x, startY)
                lineTo(x, endY)
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, 200L))
                .build()

            dispatchGesture(gesture, null, null)
        }
        return true
    }

    private fun performTikTokTapCenter(): Boolean {
        if (!isTikTokForeground()) {
            Log.w(TAG, "Blocked tapCenter: foreground package '$currentPackageName' is not TikTok.")
            return false
        }

        mainHandler.post {
            val (width, height) = getRealScreenSize()
            val cx = width * 0.5f
            val cy = height * 0.5f

            val path = Path().apply {
                moveTo(cx, cy)
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, 50L))
                .build()

            dispatchGesture(gesture, null, null)
        }
        return true
    }

    private fun getRealScreenSize(): Pair<Int, Int> {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            if (bounds.width() > 0 && bounds.height() > 0) {
                return bounds.width() to bounds.height()
            }
        }
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        return if (metrics.widthPixels > 0 && metrics.heightPixels > 0) {
            metrics.widthPixels to metrics.heightPixels
        } else {
            val fallback = resources.displayMetrics
            fallback.widthPixels to fallback.heightPixels
        }
    }

    // ─── Giữ sáng màn hình thông minh bằng TYPE_ACCESSIBILITY_OVERLAY ──────────

    private fun updateKeepScreenOnOverlay() {
        mainHandler.post {
            val shouldKeepAwake = keepScreenOnEnabled && isTikTokForeground()
            if (shouldKeepAwake) {
                attachKeepScreenOnOverlay()
            } else {
                removeKeepScreenOnOverlay()
            }
        }
    }

    private fun attachKeepScreenOnOverlay() {
        if (keepAwakeView != null) return
        try {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val view = View(this).apply {
                setBackgroundColor(Color.TRANSPARENT)
            }
            val params = WindowManager.LayoutParams(
                1,
                1,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 0
                y = 0
            }
            wm.addView(view, params)
            keepAwakeView = view
            Log.d(TAG, "Attached 1x1 keep-screen-on overlay for TikTok")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to attach keep-screen-on overlay: ${e.message}")
        }
    }

    private fun removeKeepScreenOnOverlay() {
        val view = keepAwakeView ?: return
        try {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm.removeViewImmediate(view)
            Log.d(TAG, "Removed 1x1 keep-screen-on overlay")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove keep-screen-on overlay: ${e.message}")
        } finally {
            keepAwakeView = null
        }
    }
}
