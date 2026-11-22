package com.vf5plus.media

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.webkit.WebView

/**
 * BackgroundWebView ghi đè onWindowVisibilityChanged & onVisibilityChanged
 * để luôn giữ trạng thái View.VISIBLE đối với nhân Chromium.
 * Nhờ đó, WebView tiếp tục giải mã âm thanh & chạy JavaScript ngay cả khi
 * màn hình điện thoại bị tắt hoặc khoá.
 */
class BackgroundWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : WebView(context, attrs, defStyleAttr) {

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(View.VISIBLE)
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, View.VISIBLE)
    }
}
