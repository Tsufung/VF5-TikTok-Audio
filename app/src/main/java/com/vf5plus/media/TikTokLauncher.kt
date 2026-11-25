package com.vf5plus.media

import android.content.Context
import android.content.Intent

/**
 * Helper mở ứng dụng TikTok chính chủ theo thứ tự ưu tiên khu vực Việt Nam / Quốc tế.
 */
object TikTokLauncher {

    val TIKTOK_PACKAGES = listOf(
        "com.ss.android.ugc.trill",
        "com.zhiliaoapp.musically",
        "com.ss.android.ugc.trill.go",
        "com.zhiliaoapp.musically.go"
    )

    fun openTikTokApp(context: Context): Boolean {
        val pm = context.packageManager
        for (pkg in TIKTOK_PACKAGES) {
            val launchIntent = pm.getLaunchIntentForPackage(pkg)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                context.startActivity(launchIntent)
                return true
            }
        }
        return false
    }
}
