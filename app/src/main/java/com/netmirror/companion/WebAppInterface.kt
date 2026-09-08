package com.netmirror.companion

import android.webkit.JavascriptInterface

/**
 * JavaScript Interface bridge to receive extracted OTP from the WebView DOM.
 */
class WebAppInterface(private val onOtpReceivedListener: (String) -> Unit) {

    @JavascriptInterface
    fun onOtpExtracted(otp: String) {
        if (otp.isNotBlank() && otp.length == 6) {
            onOtpReceivedListener(otp)
        }
    }
}
