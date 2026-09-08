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
                componentName.contains("NetMirrorAutoFillService", ignoreCase = true) ||
                componentName.contains(context.packageName, ignoreCase = true)
            ) {
                return true
            }
        }
        return false
    }
}

/**
 * Custom Visual Highlighter View drawn on top of the screen
 * showing a pulsing animated neon crosshair and ripple where the click happened.
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

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        color = Color.WHITE
        textSize = 34f
        typeface = Typeface.DEFAULT_BOLD
    }

    init {
        startRippleAnimation()
    }

    private fun startRippleAnimation() {
        animator = ValueAnimator.ofFloat(20f, 95f).apply {
            duration = 800
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

        // 1. Expanding Animated Ripple Ring
        outerRingPaint.alpha = rippleAlpha
        canvas.drawCircle(clickX, clickY, rippleRadius, outerRingPaint)

        // 2. Static Outer Guide Ring
        outerRingPaint.alpha = 220
        canvas.drawCircle(clickX, clickY, 34f, outerRingPaint)

        // 3. Center Touch Target Dot
        canvas.drawCircle(clickX, clickY, 14f, innerDotPaint)

        // 4. Crosshairs
        val crossLength = 45f
        canvas.drawLine(clickX - crossLength, clickY, clickX - 18f, clickY, crosshairPaint)
        canvas.drawLine(clickX + 18f, clickY, clickX + crossLength, clickY, crosshairPaint)
        canvas.drawLine(clickX, clickY - crossLength, clickX, clickY - 18f, crosshairPaint)
        canvas.drawLine(clickX, clickY + 18f, clickX, clickY + crossLength, crosshairPaint)

        // 5. Tooltip Badge
        val labelText = infoLabel
        val textWidth = textPaint.measureText(labelText)
        val badgePadding = 24f
        val badgeHeight = 58f
        val badgeWidth = textWidth + (badgePadding * 2)

        var badgeLeft = clickX - (badgeWidth / 2f)
        var badgeTop = clickY - 110f

        // Boundary safety
        if (badgeLeft < 20f) badgeLeft = 20f
        if (badgeLeft + badgeWidth > width - 20f) badgeLeft = (width - badgeWidth - 20f)
        if (badgeTop < 60f) badgeTop = clickY + 50f

        val badgeRect = RectF(badgeLeft, badgeTop, badgeLeft + badgeWidth, badgeTop + badgeHeight)
        canvas.drawRoundRect(badgeRect, 16f, 16f, badgeBgPaint)
        canvas.drawRoundRect(badgeRect, 16f, 16f, badgeBorderPaint)

        val textX = badgeLeft + badgePadding
        val textY = badgeTop + 40f
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
        var instance: NetMirrorAutoFillService? = null

        fun startDirectAutoFill(otp: String) {
            val service = instance
            if (service != null) {
                Log.d(TAG, "Direct auto-fill triggered with OTP: $otp")
                service.scheduleAutoFillSequence(otp, delayMs = 8000L)
            } else {
                Log.w(TAG, "Service instance is null. Is Accessibility enabled in Android Settings?")
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        windowManager = getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        Log.d(TAG, "NetMirrorAutoFillService connected!")
        showToast("NetMirror Auto-Clicker Service Active!")
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
        if (packageNameStr == packageName) {
            // Ignore events from our own companion app
            return
        }

        if (!isSearching) {
            isSearching = true
            Log.d(TAG, "Foreground app change detected ($packageNameStr). Waiting 8s before clicking...")
            scheduleAutoFillSequence(otp, delayMs = 8000L)
        }
    }

    fun scheduleAutoFillSequence(otp: String, delayMs: Long) {
        showToast("Waiting 8s for NetMirror TV animation to load...")
        handler.postDelayed({
            isSearching = true
            showToast("Starting multi-click on upper-center OTP area...")
            performUpperHalfMultiClickSequence(otp)
        }, delayMs)
    }

    /**
     * Performs a series of targeted clicks across the horizontal center and upper-half
     * of the screen where the OTP input boxes are positioned.
     */
    private fun performUpperHalfMultiClickSequence(otp: String) {
        val dm = resources.displayMetrics
        val w = dm.widthPixels.toFloat()
        val h = dm.heightPixels.toFloat()
        val centerX = w / 2f

        // Multi-point coordinates in the horizontally center & upper-half region
        val targetPoints = listOf(
            Pair(centerX, h * 0.38f),                           // Upper-center row 1
            Pair(centerX, h * 0.44f),                           // Upper-center row 2 (primary OTP line)
            Pair(centerX, h * 0.50f),                           // Center-middle row
            Pair(centerX - (w * 0.12f), h * 0.44f),            // Left digit box
            Pair(centerX + (w * 0.12f), h * 0.44f),            // Right digit box
            Pair(centerX, h * 0.32f)                            // High center fallback
        )

        // 1. First inspect if AccessibilityNodeInfo finds a specific node
        val rootNode = rootInActiveWindow
        if (rootNode != null) {
            val node = findOtpInputNode(rootNode)
            if (node != null) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                val nodeX = if (rect.width() > 0) rect.centerX().toFloat() else centerX
                val nodeY = if (rect.height() > 0) rect.centerY().toFloat() else h * 0.44f

                Log.d(TAG, "Found explicit node at ($nodeX, $nodeY)")
                showClickHighlight(nodeX, nodeY, "Target OTP Box ($otp)")
                showToast("Clicking OTP Box at (${nodeX.toInt()}, ${nodeY.toInt()})")

                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                node.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                dispatchTapAt(nodeX, nodeY)

                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, otp)
                }
                node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            }
        }

        // 2. Perform sequential physical taps across all upper-center points
        for ((index, point) in targetPoints.withIndex()) {
            val (px, py) = point
            val delay = index * 400L

            handler.postDelayed({
                if (!AutoFillManager.autoFillCompleted || index < 3) {
                    Log.d(TAG, "Multi-click step ${index + 1}/${targetPoints.size} at ($px, $py)")
                    showClickHighlight(px, py, "Click ${index + 1}: ($otp)")
                    showToast("Clicking (${px.toInt()}, ${py.toInt()})")
                    dispatchTapAt(px, py)

                    // Inject OTP at each step
                    handler.postDelayed({
                        val args = Bundle().apply {
                            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, otp)
                        }
                        val focused = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                        focused?.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                        focused?.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                    }, 150)
                }
            }, delay)
        }

        // Mark completed after full sequence runs
        handler.postDelayed({
            AutoFillManager.autoFillCompleted = true
            AutoFillManager.lastFilledOtp = otp
            isSearching = false
            showToast("OTP ($otp) Filled Across Target Area!")
        }, (targetPoints.size * 400L) + 500L)
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

                    // Auto-remove after 3 seconds
                    handler.postDelayed({
                        removeExistingHighlight()
                    }, 3000)
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
        if (instance == this) instance = null
        removeExistingHighlight()
    }
}
