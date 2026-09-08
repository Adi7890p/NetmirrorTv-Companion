package com.netmirror.companion

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

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
        // Check if event is from NetMirror or TV app
        if (!packageNameStr.contains("netmirror", ignoreCase = true) &&
            !packageNameStr.contains("companion", ignoreCase = true)
        ) {
            return
        }

        if (packageNameStr == packageName) {
            // Ignore events from our own companion app
            return
        }

        if (!isSearching) {
            isSearching = true
            Log.d(TAG, "NetMirror TV window detected ($packageNameStr). Starting auto-click & type sequence...")
            attemptAutoFillWithRetries(otp, attemptsLeft = 20)
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
                Log.d(TAG, "Found target OTP input node: ${targetNode.className}. Performing click and fill...")

                val rect = Rect()
                targetNode.getBoundsInScreen(rect)

                // 1. Accessibility Actions
                targetNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                targetNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)

                // Also click parent if any
                targetNode.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK)

                // 2. Physical Gesture Tap (React Native touch event trigger)
                if (rect.width() > 0 && rect.height() > 0) {
                    dispatchTapAt(rect.centerX().toFloat(), rect.centerY().toFloat())
                } else {
                    // Fallback to screen center coords for NetMirror TV
                    val displayMetrics = resources.displayMetrics
                    val x = displayMetrics.widthPixels / 2f
                    val y = displayMetrics.heightPixels * 0.42f
                    dispatchTapAt(x, y)
                }

                // 3. Insert text after tap
                handler.postDelayed({
                    val args = Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, otp)
                    }
                    val setTextSuccess = targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                    targetNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)

                    // Check focused node
                    val focusedNode = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                    focusedNode?.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                    focusedNode?.performAction(AccessibilityNodeInfo.ACTION_PASTE)

                    Log.d(TAG, "OTP entry dispatched (success: $setTextSuccess). OTP: $otp")

                    AutoFillManager.autoFillCompleted = true
                    AutoFillManager.lastFilledOtp = otp
                    isSearching = false

                    try {
                        Toast.makeText(applicationContext, "OTP ($otp) Auto-Filled!", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {}
                }, 450)
                return
            }
        }

        // Retry every 350ms while NetMirror TV splash / animations render
        handler.postDelayed({
            attemptAutoFillWithRetries(otp, attemptsLeft - 1)
        }, 350)
    }

    private fun dispatchTapAt(x: Float, y: Float) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                val path = Path().apply {
                    moveTo(x, y)
                }
                val stroke = GestureDescription.StrokeDescription(path, 0, 50)
                val gesture = GestureDescription.Builder().addStroke(stroke).build()
                dispatchGesture(gesture, null, null)
                Log.d(TAG, "Dispatched physical touch gesture at ($x, $y)")
            } catch (e: Exception) {
                Log.e(TAG, "Gesture dispatch error: ${e.localizedMessage}", e)
            }
        }
    }

    private fun findOtpInputNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val className = node.className?.toString() ?: ""
        if (className.contains("EditText", ignoreCase = true) ||
            className.contains("TextInput", ignoreCase = true)
        ) {
            return node
        }

        val text = node.text?.toString() ?: ""
        val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            node.hintText?.toString() ?: ""
        } else {
            ""
        }
        val contentDesc = node.contentDescription?.toString() ?: ""
        val viewId = node.viewIdResourceName ?: ""

        if (text.contains("Enter OTP", ignoreCase = true) ||
            hint.contains("Enter OTP", ignoreCase = true) ||
            contentDesc.contains("Enter OTP", ignoreCase = true) ||
            text.contains("Enter 6-digit", ignoreCase = true) ||
            hint.contains("OTP", ignoreCase = true) ||
            viewId.contains("otp", ignoreCase = true) ||
            viewId.contains("input", ignoreCase = true)
        ) {
            return node
        }

        // Inspect children
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
