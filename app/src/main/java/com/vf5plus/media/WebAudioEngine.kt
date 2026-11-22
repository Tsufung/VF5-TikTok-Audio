package com.vf5plus.media

import android.app.Presentation
import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout

private const val TAG = "WebAudioEngine"

class WebAudioEngine private constructor(private val appContext: Context) {

    companion object {
        @Volatile
        private var instance: WebAudioEngine? = null

        fun getInstance(context: Context): WebAudioEngine {
            return instance ?: synchronized(this) {
                instance ?: WebAudioEngine(context.applicationContext).also { instance = it }
            }
        }
    }

    interface EngineListener {
        fun onMetadataUpdated(author: String, title: String)
        fun onPlaybackStateChanged(isPlaying: Boolean)
    }

    var listener: EngineListener? = null

    val webView: BackgroundWebView = BackgroundWebView(appContext)
    private var virtualDisplay: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private val presentationRoot = FrameLayout(appContext)

    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
    private var wakeLock: PowerManager.WakeLock? = null

    var currentAuthor: String = "TikTok"
        private set
    var currentTitle: String = "VF5 Audio"
        private set
    var isPlaying: Boolean = false
        private set

    private var isActivityAttached = false
    private val mainHandler = Handler(Looper.getMainLooper())

    init {
        setupWakeLock()
        setupVirtualDisplayAndPresentation()
        setupWebView()
        requestAudioFocus()
    }

    private fun setupWakeLock() {
        try {
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VF5Audio:WakeLock").apply {
                setReferenceCounted(false)
            }
        } catch (e: Exception) {
            Log.e(TAG, "WakeLock init error: ${e.message}")
        }
    }

    private fun setupVirtualDisplayAndPresentation() {
        try {
            val displayManager = appContext.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val imageReader = ImageReader.newInstance(480, 854, PixelFormat.RGBA_8888, 2)
            virtualDisplay = displayManager.createVirtualDisplay(
                "TikTokBg",
                480,
                854,
                160,
                imageReader.surface,
                0 // flags = 0
            )

            virtualDisplay?.display?.let { display ->
                presentation = Presentation(appContext, display, android.R.style.Theme_DeviceDefault_NoActionBar).apply {
                    setContentView(presentationRoot)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "VirtualDisplay/Presentation setup error: ${e.message}")
        }
    }

    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
            useWideViewPort = true
            loadWithOverviewMode = true
            userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    return false
                }
                // Chặn triệt để intent://, market://, snssdk:// để không bị văng sang CH Play
                return true
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                unmuteAndPlay()
            }
        }

        webView.addJavascriptInterface(JsBridge(), "AndroidBridge")

        // Mặc định gắn vào Presentation để chạy nền ngay từ đầu
        attachToPresentationInternal()

        // Load trang khởi động TikTok For You
        loadTikTokForYou()
    }

    private inner class JsBridge {
        @JavascriptInterface
        fun updateMetadata(author: String, desc: String) {
            mainHandler.post {
                val safeAuthor = if (author.isNotBlank()) author.trim() else "TikTok"
                val safeTitle = if (desc.isNotBlank()) desc.trim() else "Đang phát âm thanh"
                currentAuthor = safeAuthor
                currentTitle = safeTitle
                listener?.onMetadataUpdated(safeAuthor, safeTitle)
            }
        }

        @JavascriptInterface
        fun onPlaybackState(playing: Boolean) {
            mainHandler.post {
                isPlaying = playing
                listener?.onPlaybackStateChanged(playing)
            }
        }
    }

    // ─── Attach / Detach giữa Điện Thoại và Màn Hình Ảo Chạy Nền ─────────────

    fun attachToActivity(container: ViewGroup) {
        mainHandler.post {
            isActivityAttached = true
            try {
                if (presentation?.isShowing == true) {
                    presentation?.hide()
                }
            } catch (e: Exception) {
                Log.e(TAG, "hide presentation error: ${e.message}")
            }

            (webView.parent as? ViewGroup)?.removeView(webView)
            container.addView(
                webView,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }
    }

    fun detachFromActivity() {
        mainHandler.post {
            isActivityAttached = false
            attachToPresentationInternal()
        }
    }

    private fun attachToPresentationInternal() {
        try {
            (webView.parent as? ViewGroup)?.removeView(webView)
            presentationRoot.addView(
                webView,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            presentation?.show()
        } catch (e: Exception) {
            Log.e(TAG, "attachToPresentationInternal error: ${e.message}")
        }
    }

    // ─── AudioFocus & WakeLock ────────────────────────────────────────────────

    fun requestAudioFocus() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
                val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(audioAttributes)
                    .setOnAudioFocusChangeListener { focusChange ->
                        if (focusChange == AudioManager.AUDIOFOCUS_LOSS) {
                            pause()
                        }
                    }
                    .build()
                audioManager.requestAudioFocus(focusRequest)
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(
                    { focusChange ->
                        if (focusChange == AudioManager.AUDIOFOCUS_LOSS) {
                            pause()
                        }
                    },
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN
                )
            }

            wakeLock?.let {
                if (!it.isHeld) {
                    it.acquire(3 * 60 * 60 * 1000L) // Giữ tối đa 3 giờ chạy liên tục
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "requestAudioFocus error: ${e.message}")
        }
    }

    // ─── Điều Hướng URL ───────────────────────────────────────────────────────

    fun loadTikTokForYou() {
        mainHandler.post {
            requestAudioFocus()
            currentAuthor = "TikTok"
            currentTitle = "TikTok — Dành cho bạn"
            listener?.onMetadataUpdated(currentAuthor, currentTitle)
            webView.loadUrl("https://www.tiktok.com/foryou")
        }
    }

    fun loadTikTokExplore() {
        mainHandler.post {
            requestAudioFocus()
            currentAuthor = "TikTok"
            currentTitle = "TikTok — Khám phá"
            listener?.onMetadataUpdated(currentAuthor, currentTitle)
            webView.loadUrl("https://www.tiktok.com/explore")
        }
    }

    fun loadYouTube() {
        mainHandler.post {
            requestAudioFocus()
            currentAuthor = "YouTube"
            currentTitle = "YouTube — Trang chủ"
            listener?.onMetadataUpdated(currentAuthor, currentTitle)
            webView.loadUrl("https://m.youtube.com")
        }
    }

    fun reload() {
        mainHandler.post {
            requestAudioFocus()
            webView.reload()
        }
    }

    // ─── Điều Khiển Chuyển Bài Vô-Lăng ────────────────────────────────────────

    fun skipNext() {
        mainHandler.post {
            requestAudioFocus()
            val url = webView.url ?: ""
            if (url.contains("youtube.com", ignoreCase = true)) {
                // YouTube Next
                val js = """
                    (function() {
                        var nextBtn = document.querySelector('.ytp-next-button, button.slim-video-action-bar-next-button, [aria-label*="Next" i], [aria-label*="tiếp" i], [aria-label*="Kế tiếp" i]');
                        if (nextBtn) {
                            nextBtn.click();
                        } else {
                            var v = document.querySelector('video');
                            if (v && v.duration) {
                                v.currentTime = v.duration - 0.5;
                            }
                        }
                        setTimeout(function() {
                            var v = document.querySelector('video');
                            if (v) { v.muted = false; v.play(); }
                            var title = (document.querySelector('h1.title, .slim-video-metadata-title, h2.title')?.textContent || document.title || '').trim();
                            var author = (document.querySelector('.ytm-channel-name, .ytd-channel-name, #channel-name')?.textContent || 'YouTube').trim();
                            if (window.AndroidBridge) {
                                window.AndroidBridge.updateMetadata(author, title);
                            }
                        }, 800);
                    })();
                """.trimIndent()
                webView.evaluateJavascript(js, null)
            } else {
                // TikTok Next: 3 bước tuần tự
                val jsStep1 = """
                    (function() {
                        var selectors = [
                            'button[data-e2e="arrow-right"]',
                            'button[aria-label*="next" i]',
                            'button[aria-label*="tiếp" i]',
                            'button[data-e2e="feed-next"]',
                            '[data-e2e="arrow-right"]',
                            'button[aria-label="Next video"]',
                            'button[aria-label="Video tiếp theo"]'
                        ];
                        var clicked = false;
                        for (var i = 0; i < selectors.length; i++) {
                            var btn = document.querySelector(selectors[i]);
                            if (btn) {
                                btn.click();
                                clicked = true;
                                break;
                            }
                        }
                        if (!clicked) {
                            try {
                                var ev = new KeyboardEvent('keydown', {key: 'ArrowDown', code: 'ArrowDown', keyCode: 40, which: 40, bubbles: true});
                                document.dispatchEvent(ev);
                                window.dispatchEvent(ev);
                            } catch(e) {}
                            window.scrollBy({top: window.innerHeight, behavior: 'smooth'});
                        }
                    })();
                """.trimIndent()
                webView.evaluateJavascript(jsStep1, null)

                // Sau 500ms, tìm video đang ở trung tâm để bật tiếng và đọc thông tin tác giả
                mainHandler.postDelayed({
                    val jsStep2 = """
                        (function() {
                            var vids = document.querySelectorAll('video');
                            for (var i = 0; i < vids.length; i++) {
                                var r = vids[i].getBoundingClientRect();
                                if (r.top >= -150 && r.bottom <= window.innerHeight + 150) {
                                    vids[i].muted = false;
                                    vids[i].volume = 1.0;
                                    vids[i].play();
                                    break;
                                }
                            }
                            var muteBtns = document.querySelectorAll('button[data-e2e="video-sound-mute"], [aria-label*="unmute" i], [aria-label*="bật âm" i], [aria-label*="Bật âm" i]');
                            for (var j = 0; j < muteBtns.length; j++) {
                                muteBtns[j].click();
                            }
                            var author = (document.querySelector('[data-e2e="video-author-uniqueid"]')?.textContent || '').trim();
                            var desc = (document.querySelector('[data-e2e="browse-video-desc"]')?.textContent || '').trim();
                            if (!author && !desc) {
                                desc = document.title;
                            }
                            if (window.AndroidBridge) {
                                window.AndroidBridge.updateMetadata(author || 'TikTok Video', desc || 'Đang phát TikTok');
                            }
                        })();
                    """.trimIndent()
                    webView.evaluateJavascript(jsStep2, null)
                }, 500)
            }
            isPlaying = true
            listener?.onPlaybackStateChanged(true)
        }
    }

    fun skipPrevious() {
        mainHandler.post {
            requestAudioFocus()
            val url = webView.url ?: ""
            if (url.contains("youtube.com", ignoreCase = true)) {
                // YouTube Previous
                val js = """
                    (function() {
                        var prevBtn = document.querySelector('.ytp-prev-button, [aria-label*="Previous" i], [aria-label*="trước" i]');
                        if (prevBtn) {
                            prevBtn.click();
                        } else {
                            history.back();
                        }
                    })();
                """.trimIndent()
                webView.evaluateJavascript(js, null)
            } else {
                // TikTok Previous
                val jsStep1 = """
                    (function() {
                        var selectors = [
                            'button[data-e2e="arrow-left"]',
                            'button[aria-label*="prev" i]',
                            'button[aria-label*="trước" i]',
                            '[data-e2e="arrow-left"]'
                        ];
                        var clicked = false;
                        for (var i = 0; i < selectors.length; i++) {
                            var btn = document.querySelector(selectors[i]);
                            if (btn) {
                                btn.click();
                                clicked = true;
                                break;
                            }
                        }
                        if (!clicked) {
                            try {
                                var ev = new KeyboardEvent('keydown', {key: 'ArrowUp', code: 'ArrowUp', keyCode: 38, which: 38, bubbles: true});
                                document.dispatchEvent(ev);
                                window.dispatchEvent(ev);
                            } catch(e) {}
                            window.scrollBy({top: -window.innerHeight, behavior: 'smooth'});
                        }
                    })();
                """.trimIndent()
                webView.evaluateJavascript(jsStep1, null)

                mainHandler.postDelayed({
                    unmuteAndPlay()
                }, 500)
            }
            isPlaying = true
            listener?.onPlaybackStateChanged(true)
        }
    }

    fun play() {
        mainHandler.post {
            requestAudioFocus()
            val js = """
                (function() {
                    var vids = document.querySelectorAll('video');
                    for (var i = 0; i < vids.length; i++) {
                        vids[i].muted = false;
                        vids[i].play();
                    }
                })();
            """.trimIndent()
            webView.evaluateJavascript(js, null)
            isPlaying = true
            listener?.onPlaybackStateChanged(true)
        }
    }

    fun pause() {
        mainHandler.post {
            val js = """
                (function() {
                    var vids = document.querySelectorAll('video');
                    for (var i = 0; i < vids.length; i++) {
                        vids[i].pause();
                    }
                })();
            """.trimIndent()
            webView.evaluateJavascript(js, null)
            isPlaying = false
            listener?.onPlaybackStateChanged(false)
        }
    }

    private fun unmuteAndPlay() {
        val js = """
            (function() {
                var vids = document.querySelectorAll('video');
                for (var i = 0; i < vids.length; i++) {
                    vids[i].muted = false;
                    vids[i].volume = 1.0;
                    vids[i].play();
                }
                var muteBtns = document.querySelectorAll('button[data-e2e="video-sound-mute"], [aria-label*="unmute" i], [aria-label*="bật âm" i], [aria-label*="Bật âm" i]');
                for (var j = 0; j < muteBtns.length; j++) {
                    muteBtns[j].click();
                }
                var author = (document.querySelector('[data-e2e="video-author-uniqueid"]')?.textContent || '').trim();
                var desc = (document.querySelector('[data-e2e="browse-video-desc"]')?.textContent || '').trim();
                if (!author && !desc) {
                    desc = document.title;
                }
                if (window.AndroidBridge) {
                    window.AndroidBridge.updateMetadata(author || 'TikTok Video', desc || 'Đang phát TikTok');
                }
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }
}
