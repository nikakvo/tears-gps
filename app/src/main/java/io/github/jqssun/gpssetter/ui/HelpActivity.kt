package io.github.jqssun.gpssetter.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import io.github.jqssun.gpssetter.BuildConfig
import io.github.jqssun.gpssetter.R
import java.io.ByteArrayOutputStream

/**
 * Offline help page (assets/help.html). The current launcher icon and the version are injected,
 * so the page always matches the icon chosen in build.sh. Links open in the browser.
 */
class HelpActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val web = WebView(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            // JavaScript only drives the Copy buttons of the bundled page (no remote content).
            settings.javaScriptEnabled = true
            settings.allowFileAccess = false
            addJavascriptInterface(ClipboardBridge(), "TearsGPS")
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, request.url))
                    } catch (e: ActivityNotFoundException) {
                        // no browser: ignore
                    }
                    return true
                }
            }
        }
        setContentView(web)
        ViewCompat.setOnApplyWindowInsetsListener(web) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        val html = assets.open("help.html").bufferedReader().use { it.readText() }
            .replace("{{VERSION}}", BuildConfig.VERSION_NAME)
            .replace("{{ICON}}", iconDataUri())
        web.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
    }

    inner class ClipboardBridge {
        @JavascriptInterface
        fun copy(text: String) {
            runOnUiThread {
                val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("command", text))
                // Android 13+ shows its own confirmation.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    Toast.makeText(this@HelpActivity, R.string.copied, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun iconDataUri(): String {
        val drawable = ContextCompat.getDrawable(this, R.drawable.toolkit_adaptive_fg) ?: return ""
        val out = ByteArrayOutputStream()
        drawable.toBitmap(256, 256).compress(Bitmap.CompressFormat.PNG, 100, out)
        return "data:image/png;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}
