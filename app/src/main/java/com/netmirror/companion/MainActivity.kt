package com.netmirror.companion

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.netmirror.companion.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var currentOtp: String? = null
    private var countDownTimer: CountDownTimer? = null
    private var isPaused = false
    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        private const val TAG = "NetMirrorCompanion"
        private const val OTP_TARGET_URL = "https://netmirror.gg/tv"
        private const val COUNTDOWN_DURATION_MS = 5000L
        private const val COUNTDOWN_INTERVAL_MS = 50L

        // Candidate package identifiers for NetMirror TV
        private val KNOWN_PACKAGES = listOf(
            "com.netmirrortv",
            "com.netmirror.tv",
            "com.netmirror.tvos",
            "com.netmirror",
            "com.netmirror.app",
            "tv.netmirror",
            "org.netmirror",
            "com.netmirror.android"
        )

        // Deep links supported
        private val DEEP_LINK_SCHEMES = listOf(
            "netmirrortv://otp?code=",
            "netmirror://otp?code=",
            "netmirrortv://login?otp=",
            "netmirror://login?otp="
        )
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
                copyOtpToClipboard(otp)
                Toast.makeText(this, getString(R.string.copied_toast), Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnRefresh.setOnClickListener {
            resetOtpState()
            loadOtpPage()
        }

        binding.btnLaunchMainApp.setOnClickListener {
            countDownTimer?.cancel()
            currentOtp?.let { otp ->
                copyOtpToClipboard(otp)
                launchMainAppWithOtp(otp)
            } ?: run {
                launchMainAppDirectly()
            }
        }

        binding.btnPause.setOnClickListener {
            if (isPaused) {
                isPaused = false
                binding.btnPause.text = "Cancel"
                start5SecondCountdown()
            } else {
                isPaused = true
                countDownTimer?.cancel()
                binding.btnPause.text = "Resume"
                binding.tvCountdown.text = "Auto-launch paused"
            }
        }
    }

    private fun copyOtpToClipboard(otp: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("NetMirror OTP", otp)
        clipboard.setPrimaryClip(clip)
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
        settings.userAgentString =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"

        // Register Native Android <-> JS Bridge
        binding.webView.addJavascriptInterface(WebAppInterface { rawOtp ->
            mainHandler.post {
                val cleaned = rawOtp.trim()
                if (cleaned.length == 6 && cleaned.all { it.isDigit() } && cleaned != "000000") {
                    onOtpSuccessfullyExtracted(cleaned)
                }
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
                if (window.__otpObserverAttached) {
                    return;
                }
                window.__otpObserverAttached = true;

                var lastCode = '';

                function extractOtpFromDOM() {
                    try {
                        // 1. Check for div.digit or any element with 'digit' class
                        var digits = document.querySelectorAll('div.digit, .digit, [class*="digit"]');
                        if (digits && digits.length >= 6) {
                            var code = '';
                            for (var i = 0; i < 6; i++) {
                                code += (digits[i].innerText || digits[i].textContent || '').trim();
                            }
                            if (code.length === 6 && /^\d{6}$/.test(code) && code !== '000000') {
                                return code;
                            }
                        }

                        // 2. Look for container with 6 child boxes containing single digits
                        var containers = document.querySelectorAll('div, section, main');
                        for (var c = 0; c < containers.length; c++) {
                            var ch = containers[c].children;
                            if (ch && ch.length === 6) {
                                var candidate = '';
                                var valid = true;
                                for (var k = 0; k < 6; k++) {
                                    var txt = (ch[k].innerText || ch[k].textContent || '').trim();
                                    if (txt.length === 1 && /\d/.test(txt)) {
                                        candidate += txt;
                                    } else {
                                        valid = false;
                                        break;
                                    }
                                }
                                if (valid && candidate.length === 6 && candidate !== '000000') {
                                    return candidate;
                                }
                            }
                        }

                        // 3. Regex scan text nodes for 6 consecutive digits (excluding 000000)
                        var text = document.body ? (document.body.innerText || document.body.textContent || '') : '';
                        var matches = text.match(/\b\d{6}\b/g);
                        if (matches) {
                            for (var m = 0; m < matches.length; m++) {
                                if (matches[m] !== '000000') {
                                    return matches[m];
                                }
                            }
                        }
                    } catch (e) {
                        console.error('DOM OTP error:', e);
                    }
                    return null;
                }

                function checkAndDispatch() {
                    var found = extractOtpFromDOM();
                    if (found && found !== lastCode) {
                        lastCode = found;
                        if (window.AndroidBridge && window.AndroidBridge.onOtpExtracted) {
                            window.AndroidBridge.onOtpExtracted(found);
                        }
                    }
                }

                // Initial extraction attempt
                checkAndDispatch();

                // Continuous MutationObserver (Keeps listening for AJAX/DOM updates)
                var observer = new MutationObserver(function() {
                    checkAndDispatch();
                });
                observer.observe(document.body || document.documentElement, {
                    childList: true,
                    subtree: true,
                    characterData: true
                });

                // Periodic check interval (every 300ms)
                setInterval(checkAndDispatch, 300);
            })();
        """.trimIndent()

        binding.webView.evaluateJavascript(script, null)
    }

    private fun onOtpSuccessfullyExtracted(otp: String) {
        currentOtp = otp
        binding.progressBar.visibility = View.GONE
        binding.tvOtpCode.text = otp.chunked(1).joinToString(" ")
        binding.tvStatus.text = getString(R.string.status_ready)

        // Show floating countdown overlay with smooth fade-in
        binding.cardOtpOverlay.visibility = View.VISIBLE
        binding.cardOtpOverlay.alpha = 0f
        binding.cardOtpOverlay.animate().alpha(1f).setDuration(300).start()

        // Copy to clipboard immediately
        copyOtpToClipboard(otp)

        // Start the 5-second countdown timer animation
        start5SecondCountdown()
    }

    private fun start5SecondCountdown() {
        countDownTimer?.cancel()
        binding.pbCountdown.max = COUNTDOWN_DURATION_MS.toInt()

        countDownTimer = object : CountDownTimer(COUNTDOWN_DURATION_MS, COUNTDOWN_INTERVAL_MS) {
            override fun onTick(millisUntilFinished: Long) {
                val secondsLeft = ((millisUntilFinished + 999) / 1000).toInt()
                binding.tvCountdown.text = "Launching NetMirror TV in ${secondsLeft}s..."
                binding.pbCountdown.progress = millisUntilFinished.toInt()
            }

            override fun onFinish() {
                binding.pbCountdown.progress = 0
                binding.tvCountdown.text = "Launching NetMirror TV now..."
                currentOtp?.let { otp ->
                    launchMainAppWithOtp(otp)
                }
            }
        }.start()
    }

    private fun resetOtpState() {
        countDownTimer?.cancel()
        currentOtp = null
        binding.cardOtpOverlay.visibility = View.GONE
        binding.tvOtpCode.text = getString(R.string.otp_placeholder)
        binding.tvStatus.text = getString(R.string.status_loading)
        binding.progressBar.visibility = View.VISIBLE
    }

    private fun launchMainAppWithOtp(otp: String) {
        try {
            // 1. Try deep links
            for (scheme in DEEP_LINK_SCHEMES) {
                try {
                    val uri = Uri.parse("$scheme$otp")
                    val deepIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    }
                    if (deepIntent.resolveActivity(packageManager) != null) {
                        startActivity(deepIntent)
                        Toast.makeText(this, "Opening NetMirror TV...", Toast.LENGTH_SHORT).show()
                        return
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Deep link failed: $scheme", e)
                }
            }

            // 2. Try known package names
            for (pkg in KNOWN_PACKAGES) {
                val launchIntent = packageManager.getLaunchIntentForPackage(pkg)
                if (launchIntent != null) {
                    launchIntent.putExtra("otp_code", otp)
                    launchIntent.putExtra("otp", otp)
                    launchIntent.putExtra("code", otp)
                    launchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    startActivity(launchIntent)
                    Toast.makeText(this, "Opening NetMirror TV ($pkg)...", Toast.LENGTH_SHORT).show()
                    return
                }
            }

            // 3. Fallback if app is not installed
            Toast.makeText(this, getString(R.string.launch_error_toast), Toast.LENGTH_LONG).show()

        } catch (e: Exception) {
            Toast.makeText(this, "Error opening app: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun launchMainAppDirectly() {
        try {
            for (pkg in KNOWN_PACKAGES) {
                val launchIntent = packageManager.getLaunchIntentForPackage(pkg)
                if (launchIntent != null) {
                    launchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    startActivity(launchIntent)
                    Toast.makeText(this, "Opening NetMirror TV...", Toast.LENGTH_SHORT).show()
                    return
                }
            }

            Toast.makeText(this, "NetMirror TV app not found on this device.", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Error: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        countDownTimer?.cancel()
        binding.webView.removeJavascriptInterface("AndroidBridge")
        binding.webView.destroy()
        super.onDestroy()
    }
}
