package com.netmirror.companion

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

object AutoFillManager {
    var activeOtp: String? = null
    var autoFillCompleted: Boolean = false

    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val expectedServiceName = "${context.packageName}/${NetMirrorAutoFillService::class.java.canonicalName}"
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServices)
        while (colonSplitter.hasNext()) {
            val componentName = colonSplitter.next()
            if (componentName.equals(expectedServiceName, ignoreCase = true)) {
                return true
            }
        }
        return false
    }
}

class NetMirrorAutoFillService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())

    companion object {
        private const val TAG = "NetMirrorAutoFill"
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val eventType = event?.eventType ?: return
        if (eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            return
        }

        val otp = AutoFillManager.activeOtp ?: return
        if (AutoFillManager.autoFillCompleted) return

        val rootNode = rootInActiveWindow ?: return

        try {
            // Find input box (EditText or node matching 'Enter OTP' / 'OTP' / '6-digit')
            val targetInput = findOtpInputNode(rootNode)
            if (targetInput != null) {
                Log.d(TAG, "Found target OTP input node! Performing auto-click and fill...")

                // 1. Click input box
                targetInput.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                targetInput.performAction(AccessibilityNodeInfo.ACTION_FOCUS)

                // 2. Set text after a small delay to simulate user typing
                handler.postDelayed({
                    val args = Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, otp)
                    }
                    val setTextSuccess = targetInput.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                    Log.d(TAG, "Set OTP text success: $setTextSuccess for OTP: $otp")

                    if (setTextSuccess) {
                        AutoFillManager.autoFillCompleted = true
                    }
                }, 400)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in auto-filling OTP: ${e.localizedMessage}", e)
        }
    }

    private fun findOtpInputNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // Check if current node is an EditText
        if (node.className?.toString()?.contains("EditText", ignoreCase = true) == true) {
            return node
        }

        val text = node.text?.toString() ?: ""
        val hint = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            node.hintText?.toString() ?: ""
        } else {
            ""
        }
        val contentDesc = node.contentDescription?.toString() ?: ""

        if (text.contains("Enter OTP", ignoreCase = true) ||
            hint.contains("Enter OTP", ignoreCase = true) ||
            contentDesc.contains("Enter OTP", ignoreCase = true) ||
            text.contains("Enter 6-digit", ignoreCase = true) ||
            hint.contains("OTP", ignoreCase = true)
        ) {
            if (node.isClickable || node.isEditable || node.isFocusable) {
                return node
            }
        }

        // Recursively inspect children
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findOtpInputNode(child)
            if (found != null) return found
        }

        return null
    }

    override fun onInterrupt() {
        Log.d(TAG, "NetMirrorAutoFillService interrupted")
    }
}
