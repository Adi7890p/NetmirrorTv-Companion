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
    var lastFilledOtp: String? = null

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
            if (componentName.equals(expectedServiceName, ignoreCase = true) ||
                componentName.contains("NetMirrorAutoFillService", ignoreCase = true)
            ) {
                return true
            }
        }
        return false
    }
}

class NetMirrorAutoFillService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var isSearching = false

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
        if (AutoFillManager.autoFillCompleted && AutoFillManager.lastFilledOtp == otp) {
            return
        }

        val packageNameStr = event.packageName?.toString() ?: ""
        if (!packageNameStr.contains("netmirror", ignoreCase = true) &&
            !packageNameStr.contains("netmirrortv", ignoreCase = true)
        ) {
            return
        }

        // Search and retry across a short window while the TV animation plays
        if (!isSearching) {
            isSearching = true
            attemptAutoFillWithRetries(otp, attemptsLeft = 15)
        }
    }

    private fun attemptAutoFillWithRetries(otp: String, attemptsLeft: Int) {
        if (AutoFillManager.autoFillCompleted && AutoFillManager.lastFilledOtp == otp) {
            isSearching = false
            return
        }

        if (attemptsLeft <= 0) {
            isSearching = false
            return
        }

        val rootNode = rootInActiveWindow
        if (rootNode != null) {
            val targetNode = findOtpInputNode(rootNode)
            if (targetNode != null) {
                Log.d(TAG, "Found target OTP input node: ${targetNode.className}. Performing auto-click and auto-fill...")

                // 1. Click and focus the input box
                targetNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                targetNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)

                // 2. Type the OTP after a short delay
                handler.postDelayed({
                    val args = Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, otp)
                    }
                    val setTextSuccess = targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                    targetNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                    Log.d(TAG, "Set text success: $setTextSuccess for OTP: $otp")

                    if (setTextSuccess) {
                        AutoFillManager.autoFillCompleted = true
                        AutoFillManager.lastFilledOtp = otp
                        isSearching = false
                    }
                }, 350)
                return
            }
        }

        // Retry every 400ms while the TV app loads its splash/startup animation
        handler.postDelayed({
            attemptAutoFillWithRetries(otp, attemptsLeft - 1)
        }, 400)
    }

    private fun findOtpInputNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // Direct match on EditText or TextInput
        val className = node.className?.toString() ?: ""
        if (className.contains("EditText", ignoreCase = true) ||
            className.contains("TextInput", ignoreCase = true)
        ) {
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
        isSearching = false
    }
}
