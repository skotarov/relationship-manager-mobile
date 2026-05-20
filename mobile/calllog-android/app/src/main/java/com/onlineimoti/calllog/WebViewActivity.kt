package com.onlineimoti.calllog

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.onlineimoti.calllog.databinding.ActivityWebViewBinding

class WebViewActivity : AppCompatActivity() {
    private lateinit var binding: ActivityWebViewBinding

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWebViewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        if (url.isBlank()) {
            finish()
            return
        }

        binding.webView.settings.javaScriptEnabled = true
        binding.webView.settings.domStorageEnabled = true
        binding.webView.settings.loadsImagesAutomatically = true
        binding.webView.addJavascriptInterface(CallReportBridge(), "CallReportBridge")
        binding.webView.webChromeClient = WebChromeClient()
        binding.webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                binding.webLoadingText.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                binding.webLoadingText.visibility = View.GONE
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                return false
            }
        }

        binding.webView.loadUrl(url)
    }

    companion object {
        const val EXTRA_URL = "url"

        fun intent(context: Context, url: String): Intent {
            return Intent(context, WebViewActivity::class.java)
                .putExtra(EXTRA_URL, url)
        }
    }

    inner class CallReportBridge {
        @JavascriptInterface
        fun closeWithMessage(message: String?) {
            runOnUiThread {
                val trimmed = message?.trim().orEmpty()
                if (trimmed.isNotEmpty()) {
                    Toast.makeText(this@WebViewActivity, trimmed, Toast.LENGTH_SHORT).show()
                }
                finish()
            }
        }
    }
}
