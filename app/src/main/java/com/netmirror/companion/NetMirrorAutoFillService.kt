package com.netmirror.companion

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
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

/**
 * Custom Visual Highlighter View drawn on top of the target app
 * showing exactly where the auto-clicker tapped.
 */
class ClickHighlighterView(
    context: Context,
    private val clickX: Float,
    private val clickY: Float,
    private val infoLabel: String
) : View(context) {

    private var rippleRadius = 20f
    private var rippleAlpha = 255
    private var animator: ValueAnimator? = null

    private val outerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        color = Color.parseColor("#00E5FF") // Neon Cyan
    }

    private val innerDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#FF0055") // Neon Magenta Red
    }

    private val crosshairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.parseColor("#FFFF00") // Neon Yellow
    }

    private val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#EE121829") // Dark Glass
    }

    private val badgeBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.parseColor("#00E5FF")
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 32f
        typeface = Typeface.DEFAULT_BOLD
    }

    init {
        startRippleAnimation()
    }

    private fun startRippleAnimation() {
        animator = ValueAnimator.ofFloat(20f, 90f).apply {
            duration = 900
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = DecelerateInterpolator()
            addUpdateListener { animation ->
                rippleRadius = animation.animatedValue as Float
                val fraction = animation.animatedFraction
                rippleAlpha = ((1f - fraction) * 255).toInt().coerceIn(0, 255)
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 1. Draw Expanding Animated Ripple Ring
        outerRingPaint.alpha = rippleAlpha
        canvas.drawCircle(clickX, clickY, rippleRadius, outerRingPaint)

        // 2. Draw Static Outer Guide Ring
        outerRingPaint.alpha = 200
        canvas.drawCircle(clickX, clickY, 32f, outerRingPaint)

        // 3. Draw Center Touch Target Dot
        canvas.drawCircle(clickX, clickY, 14f, innerDotPaint)

        // 4. Draw Crosshairs
        val crossLength = 45f
        canvas.drawLine(clickX - crossLength, clickY, clickX - 18f, clickY, crosshairPaint)
        canvas.drawLine(clickX + 18f, clickY, clickX + crossLength, clickY, crosshairPaint)
        canvas.drawLine(clickX, clickY - crossLength, clickX, clickY - 18f, crosshairPaint)
        canvas.drawLine(clickX, clickY + 18f, clickX, clickY + crossLength, crosshairPaint)

        // 5. Draw Info Badge Tooltip
        val labelText = infoLabel
        val textWidth = textPaint.measureText(labelText)
        val badgePadding = 20f
        val badgeHeight = 54f
        val badgeWidth = textWidth + (badgePadding * 2)

        var badgeLeft = clickX - (badgeWidth / 2f)
        var badgeTop = clickY - 100f

        // Screen boundary safety
        if (badgeLeft < 20f) badgeLeft = 20f
        if (badgeLeft + badgeWidth > width - 20f) badgeLeft = (width - badgeWidth - 20f)
        if (badgeTop < 60f) badgeTop = clickY + 50f

        val badgeRect = RectF(badgeLeft, badgeTop, badgeLeft + badgeWidth, badgeTop + badgeHeight)
        canvas.drawRoundRect(badgeRect, 14f, 14f, badgeBgPaint)
        canvas.drawRoundRect(badgeRect, 14f, 14f, badgeBorderPaint)

        // Center text in badge
        val textX = badgeLeft + badgePadding
        val textY = badgeTop + 38f
        canvas.drawText(labelText, textX, textY, textPaint)
    }

    fun stopAnimation() {
        animator?.cancel()
    }
}

class NetMirrorAutoFillService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var isSearching = false
    private var activeHighlightView: ClickHighlighterView? = null
    private var windowManager: WindowManager? = null

    companion object {
        private const val TAG = "NetMirrorAutoFill"
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        Log.d(TAG, "NetMirrorAutoFillService connected and active")
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
            showToast("📺 NetMirror TV detected! Scanning for OTP box...")
            Log.d(TAG, "NetMirror TV window detected ($packageNameStr). Starting auto-click sequence...")
            attemptAutoFillWithRetries(otp, attemptsLeft = 25)
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
                val rect = Rect()
                targetNode.getBoundsInScreen(rect)

                val clickX: Float
                val clickY: Float
                if (rect.width() > 0 && rect.height() > 0) {
                    clickX = rect.centerX().toFloat()
                    clickY = rect.centerY().toFloat()
                } else {
                    val dm = resources.displayMetrics
                    clickX = dm.widthPixels / 2f
                    clickY = dm.heightPixels * 0.44f
                }

                Log.d(TAG, "Target node found! Coordinates: ($clickX, $clickY). Triggering visual highlight & click...")

                // 1. Show Visual Highlight on Screen & Toast Message
                showClickHighlight(clickX, clickY, "🎯 Tapped OTP Box ($otp)")
                showToast("🎯 Clicked OTP Box at (${clickX.toInt()}, ${clickY.toInt()})")

                // 2. Perform Accessibility Actions
                targetNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                targetNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                targetNode.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK)

                // 3. Dispatch Physical Touch Gesture
                dispatchTapAt(clickX, clickY)

                // 4. Fill OTP after delay
                handler.postDelayed({
                    val args = Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, otp)
                    }
                    val setTextSuccess = targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                    targetNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)

                    val focusedNode = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                    focusedNode?.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                    focusedNode?.performAction(AccessibilityNodeInfo.ACTION_PASTE)

                    Log.d(TAG, "OTP entry dispatched (success: $setTextSuccess). OTP: $otp")

                    AutoFillManager.autoFillCompleted = true
                    AutoFillManager.lastFilledOtp = otp
                    isSearching = false

                    showToast("✅ OTP ($otp) Entered Successfully!")
                }, 400)
                return
            } else if (attemptsLeft == 12) {
                // If node is not found directly after several attempts (e.g. custom canvas / React Native view),
                // use calculated TV screen center coordinates
                val dm = resources.displayMetrics
                val clickX = dm.widthPixels / 2f
                val clickY = dm.heightPixels * 0.44f

                Log.d(TAG, "Fallback click triggered at screen center ($clickX, $clickY)")
                showClickHighlight(clickX, clickY, "🎯 Tapped OTP Area ($otp)")
                showToast("🎯 Auto-Clicking OTP Box at (${clickX.toInt()}, ${clickY.toInt()})")
                dispatchTapAt(clickX, clickY)

                handler.postDelayed({
                    val focused = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                    val args = Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, otp)
                    }
                    focused?.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                    focused?.performAction(AccessibilityNodeInfo.ACTION_PASTE)

                    AutoFillManager.autoFillCompleted = true
                    AutoFillManager.lastFilledOtp = otp
                    isSearching = false
                    showToast("✅ OTP ($otp) Auto-Filled!")
                }, 400)
                return
            }
        }

        // Retry every 350ms while NetMirror TV splash / animation is running
        handler.postDelayed({
            attemptAutoFillWithRetries(otp, attemptsLeft - 1)
        }, 350)
    }

    /**
     * Spawns a glowing animated overlay directly above the clicked coordinates
     * using TYPE_ACCESSIBILITY_OVERLAY.
     */
    private fun showClickHighlight(x: Float, y: Float, label: String) {
        handler.post {
            try {
                removeExistingHighlight()

                val wm = windowManager ?: getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                if (wm != null) {
                    val overlay = ClickHighlighterView(this, x, y, label)
                    activeHighlightView = overlay

                    val params = WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                        PixelFormat.TRANSLUCENT
                    )

                    wm.addView(overlay, params)

                    // Auto-remove after 2.5 seconds
                    handler.postDelayed({
                        removeExistingHighlight()
                    }, 2500)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to show click highlight overlay: ${e.message}", e)
            }
        }
    }

    private fun removeExistingHighlight() {
        activeHighlightView?.let { view ->
            try {
                view.stopAnimation()
                val wm = windowManager ?: getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                wm?.removeView(view)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing highlight view: ${e.message}")
            }
            activeHighlightView = null
        }
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
            text.contains("------") ||
            viewId.contains("otp", ignoreCase = true) ||
            viewId.contains("input", ignoreCase = true)
        ) {
            return node
        }

        // Inspect children recursively
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findOtpInputNode(child)
            if (found != null) return found
        }

        return null
    }

    private fun showToast(msg: String) {
        handler.post {
            try {
                Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e(TAG, "Toast error: ${e.message}")
            }
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "NetMirrorAutoFillService interrupted")
        isSearching = false
        removeExistingHighlight()
    }

    override fun onDestroy() {
        super.onDestroy()
        removeExistingHighlight()
    }
}
