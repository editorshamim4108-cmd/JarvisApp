package com.jarvis.app.service

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.jarvis.app.R

class OverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var bubbleView: View? = null
    private var stripView: View? = null
    private var isExpanded = false
    private var pulseAnimator: ValueAnimator? = null
    private var currentJarvisState = "STANDBY"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createBubbleView()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW -> showBubble()
            ACTION_HIDE -> hideBubble()
            ACTION_EXPAND -> expandToStrip()
            ACTION_COLLAPSE -> collapseToButton()
            ACTION_UPDATE_TEXT -> {
                val text = intent.getStringExtra(EXTRA_TEXT) ?: ""
                updateStripText(text)
            }
            ACTION_SET_STATE -> {
                val state = intent.getStringExtra(EXTRA_STATE) ?: "STANDBY"
                currentJarvisState = state
                updatePulseForState(state)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        pulseAnimator?.cancel()
        removeBubble()
        removeStrip()
        instance = null
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createBubbleView() {
        val density = resources.displayMetrics.density
        val size = (80 * density).toInt()

        val container = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(size, size)
        }

        // Outer glow circle
        val glowView = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(size, size)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x5000E0FF.toInt())
            }
            alpha = 0.4f
            tag = "glow_ring"
        }
        container.addView(glowView)

        // Inner dark circle with cyan border
        val innerSize = (size * 0.82).toInt()
        val innerCircle = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(innerSize, innerSize).apply {
                gravity = Gravity.CENTER
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF0A1025.toInt())
                setStroke((2 * density).toInt(), 0xFF00E0FF.toInt())
            }
        }
        container.addView(innerCircle)

        // Mic icon
        val iconSize = (36 * density).toInt()
        val icon = ImageView(this).apply {
            setImageResource(R.drawable.ic_mic)
            setColorFilter(0xFF00E0FF.toInt())
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            layoutParams = FrameLayout.LayoutParams(iconSize, iconSize).apply {
                gravity = Gravity.CENTER
            }
        }
        container.addView(icon)

        // Draggable touch handling
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        container.setOnTouchListener { _, event ->
            val params = bubbleView?.layoutParams as? WindowManager.LayoutParams
                ?: return@setOnTouchListener false

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (kotlin.math.abs(dx) > 10 || kotlin.math.abs(dy) > 10) {
                        isDragging = true
                    }
                    params.x = initialX + dx
                    params.y = initialY + dy
                    windowManager?.updateViewLayout(bubbleView, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        onBubbleTap()
                    }
                    true
                }
                else -> false
            }
        }

        bubbleView = container
    }

    private fun onBubbleTap() {
        val service = JarvisForegroundService.instance ?: return
        if (JarvisForegroundService.currentState == JarvisForegroundService.JarvisState.LISTENING) {
            service.stopSpeechRecognition()
            collapseToButton()
        } else {
            service.onWakeWordDetected()
            expandToStrip()
        }
    }

    private fun showBubble() {
        if (bubbleView?.parent != null) return
        val density = resources.displayMetrics.density
        val size = (80 * density).toInt()

        val params = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            x = (20 * density).toInt()
            y = (100 * density).toInt()
        }
        try {
            windowManager?.addView(bubbleView, params)
            startPulseAnimation()
        } catch (_: Exception) {}
    }

    private fun hideBubble() {
        pulseAnimator?.cancel()
        removeBubble()
        removeStrip()
    }

    private fun startPulseAnimation() {
        pulseAnimator?.cancel()
        val glowRing = (bubbleView as? FrameLayout)?.findViewWithTag<View>("glow_ring")
            ?: return

        val (minAlpha, maxAlpha, duration) = when (currentJarvisState) {
            "LISTENING" -> Triple(0.3f, 1.0f, 600L)
            "PROCESSING" -> Triple(0.4f, 0.9f, 900L)
            "SPEAKING" -> Triple(0.5f, 0.95f, 800L)
            else -> Triple(0.15f, 0.5f, 2000L)
        }

        pulseAnimator = ValueAnimator.ofFloat(minAlpha, maxAlpha).apply {
            this.duration = duration
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener { anim ->
                glowRing.alpha = anim.animatedValue as Float
            }
            start()
        }
    }

    private fun updatePulseForState(state: String) {
        currentJarvisState = state
        if (bubbleView?.parent != null) {
            startPulseAnimation()
        }

        // Also update glow color based on state
        val glowRing = (bubbleView as? FrameLayout)?.findViewWithTag<View>("glow_ring")
        if (glowRing != null) {
            val color = when (state) {
                "LISTENING" -> 0x6000FF9D.toInt()  // green glow
                "PROCESSING" -> 0x50B026FF.toInt() // purple glow
                "SPEAKING" -> 0x5000E0FF.toInt()   // cyan glow
                else -> 0x3000E0FF.toInt()          // dim cyan
            }
            glowRing.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
            }
        }
    }

    private fun expandToStrip() {
        if (stripView?.parent != null) return
        isExpanded = true

        val density = resources.displayMetrics.density
        val strip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xE6_0A0C16.toInt())
            setPadding(
                (12 * density).toInt(),
                (8 * density).toInt(),
                (12 * density).toInt(),
                (8 * density).toInt()
            )
            gravity = Gravity.CENTER_VERTICAL
        }

        val dot = View(this).apply {
            val sz = (10 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                marginEnd = (8 * density).toInt()
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF00FF9D.toInt())
            }
        }
        strip.addView(dot)

        val label = TextView(this).apply {
            text = "JARVIS শুনছে..."
            setTextColor(0xFF00E0FF.toInt())
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            tag = "strip_label"
        }
        strip.addView(label)

        val closeBtn = TextView(this).apply {
            text = "✕"
            setTextColor(0xFF888888.toInt())
            textSize = 16f
            setPadding((10 * density).toInt(), 0, 0, 0)
            setOnClickListener {
                collapseToButton()
                JarvisForegroundService.instance?.stopSpeechRecognition()
            }
        }
        strip.addView(closeBtn)

        stripView = strip

        val screenWidth = resources.displayMetrics.widthPixels
        val params = WindowManager.LayoutParams(
            screenWidth - (32 * density).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (40 * density).toInt()
        }

        try {
            windowManager?.addView(stripView, params)
        } catch (_: Exception) {}
    }

    private fun collapseToButton() {
        isExpanded = false
        removeStrip()
    }

    private fun updateStripText(text: String) {
        val strip = stripView as? LinearLayout ?: return
        for (i in 0 until strip.childCount) {
            val child = strip.getChildAt(i)
            if (child is TextView && child.tag == "strip_label") {
                child.text = text
                break
            }
        }
    }

    private fun removeBubble() {
        try {
            if (bubbleView?.parent != null) {
                windowManager?.removeView(bubbleView)
            }
        } catch (_: Exception) {}
    }

    private fun removeStrip() {
        try {
            if (stripView?.parent != null) {
                windowManager?.removeView(stripView)
            }
        } catch (_: Exception) {}
        stripView = null
    }

    companion object {
        const val ACTION_SHOW = "com.jarvis.overlay.SHOW"
        const val ACTION_HIDE = "com.jarvis.overlay.HIDE"
        const val ACTION_EXPAND = "com.jarvis.overlay.EXPAND"
        const val ACTION_COLLAPSE = "com.jarvis.overlay.COLLAPSE"
        const val ACTION_UPDATE_TEXT = "com.jarvis.overlay.UPDATE_TEXT"
        const val ACTION_SET_STATE = "com.jarvis.overlay.SET_STATE"
        const val EXTRA_TEXT = "extra_text"
        const val EXTRA_STATE = "extra_state"

        var instance: OverlayService? = null
            private set
    }
}
