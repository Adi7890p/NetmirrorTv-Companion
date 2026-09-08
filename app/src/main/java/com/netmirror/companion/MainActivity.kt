package com.netmirror.companion

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.netmirror.companion.R
import com.netmirror.companion.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var currentOtp: String? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        private const val OTP_TARGET_URL = "https://netmirror.gg/tv"
        private const val MAIN_APP_SCHEME_URI = "netmirror://otp?code="
        private const val MAIN_APP_PACKAGE_NAME = "com.netmirror.tvos"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupListeners()
        setupWebView()
        loadOtpPage()
    }

    private fun setupListeners() {
        binding.btnCopy.setOnClickListener {
            currentOtp?.let { otp ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("NetMirror OTP", otp)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, getString(R.string.copied_toast), Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnRefresh.setOnClickListener {
            resetOtpState()
            loadOtpPage()
        }

        binding.btnLaunchMainApp.setOnClickListener {
            currentOtp?.let { otp ->
                launchMainAppWithOtp(otp)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val settings = binding.webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        
        // Standard Android Chrome User-Agent to pass Cloudflare checks cleanly
        settings.userAgentString = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"

        // Register Native Android <-> JS Bridge
        binding.webView.addJavascriptInterface(WebAppInterface { extractedOtp ->
            mainHandler.post {
                onOtpSuccessfullyExtracted(extractedOtp)
            }
        }, "AndroidBridge")

        binding.webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                binding.progressBar.visibility = View.VISIBLE
                binding.tvStatus.text = getString(R.string.status_loading)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                binding.progressBar.visibility = View.VISIBLE
                binding.tvStatus.text = getString(R.string.status_waiting_dom)
                injectOtpExtractionScript()
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    binding.progressBar.visibility = View.GONE
                    binding.tvStatus.text = getString(R.string.status_error)
                }
            }
        }
    }

    private fun loadOtpPage() {
        binding.webView.loadUrl(OTP_TARGET_URL)
    }

    private fun injectOtpExtractionScript() {
        val script = """
            (function() {
                function checkAndExtractDigits() {
                    try {
                        var digits = document.querySelectorAll('div.digit');
                        if (digits && digits.length === 6) {
                            var code = '';
                            for (var i = 0; i < digits.length; i++) {
                                code += (digits[i].innerText || digits[i].textContent || '').trim();
                            }
                            if (code.length === 6 && /^\d{6}$/.test(code)) {
                                if (window.AndroidBridge && window.AndroidBridge.onOtpExtracted) {
                                    window.AndroidBridge.onOtpExtracted(code);
                                }
                                return true;
                            }
                        }
                    } catch (e) {
                        console.error('Extraction error:', e);
                    }
                    return false;
                }

                if (!checkAndExtractDigits()) {
                    var observer = new MutationObserver(function(mutations, obs) {
                        if (checkAndExtractDigits()) {
                            obs.disconnect();
                        }
                    });
                    observer.observe(document.body || document.documentElement, {
                        childList: true,
                        subtree: true,
                        characterData: true
                    });

                    var pollCount = 0;
                    var intervalId = setInterval(function() {
                        pollCount++;
                        if (checkAndExtractDigits() || pollCount > 40) {
                            clearInterval(intervalId);
                        }
                    }, 500);
                }
            })();
        """.trimIndent()

        binding.webView.evaluateJavascript(script, null)
    }

    private fun onOtpSuccessfullyExtracted(otp: String) {
        currentOtp = otp
        binding.progressBar.visibility = View.GONE
        binding.tvOtpCode.text = otp
        binding.tvStatus.text = getString(R.string.status_ready)
        binding.btnCopy.isEnabled = true
        binding.btnLaunchMainApp.isEnabled = true
    }

    private fun resetOtpState() {
        currentOtp = null
        binding.tvOtpCode.text = getString(R.string.otp_placeholder)
        binding.tvStatus.text = getString(R.string.status_loading)
        binding.progressBar.visibility = View.VISIBLE
        binding.btnCopy.isEnabled = false
        binding.btnLaunchMainApp.isEnabled = false
    }

    private fun launchMainAppWithOtp(otp: String) {
        try {
            val deepLinkUri = Uri.parse("$MAIN_APP_SCHEME_URI$otp")
            val deepLinkIntent = Intent(Intent.ACTION_VIEW, deepLinkUri).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }

            if (deepLinkIntent.resolveActivity(packageManager) != null) {
                startActivity(deepLinkIntent)
                return
            }

            val launchIntent = packageManager.getLaunchIntentForPackage(MAIN_APP_PACKAGE_NAME)
            if (launchIntent != null) {
                launchIntent.putExtra("otp_code", otp)
                launchIntent.putExtra("otp", otp)
                launchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                startActivity(launchIntent)
                return
            }

            val fallbackIntent = packageManager.getLaunchIntentForPackage("com.netmirror")
            if (fallbackIntent != null) {
                fallbackIntent.putExtra("otp_code", otp)
                fallbackIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                startActivity(fallbackIntent)
                return
            }

            Toast.makeText(this, getString(R.string.launch_error_toast), Toast.LENGTH_LONG).show()

        } catch (e: Exception) {
            Toast.makeText(this, "Error opening app: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        binding.webView.removeJavascriptInterface("AndroidBridge")
        binding.webView.destroy()
        super.onDestroy()
    }
}
