package com.example.mememanagement

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import kotlin.math.abs

class FloatingService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View
    private lateinit var layoutParams: WindowManager.LayoutParams

    // 长按检测
    private val handler = Handler(Looper.getMainLooper())
    private var isLongPressTriggered = false
    private val longPressRunnable = Runnable {
        isLongPressTriggered = true
        try {
            Toast.makeText(applicationContext, "悬浮窗已暂时隐藏", Toast.LENGTH_SHORT).show()
            stopSelf()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        // --- 1. 读取上次保存的坐标 ---
        val prefs = getSharedPreferences("meme_prefs", Context.MODE_PRIVATE)
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels

        // 默认位置：屏幕右侧，垂直居中
        // 注意：Gravity.BOTTOM | END 时，x=0 表示最右边，y 是距离底部的距离
        val defaultX = 0
        val defaultY = screenHeight / 2

        val savedX = prefs.getInt("float_x", defaultX)
        val savedY = prefs.getInt("float_y", defaultY)

        val container = FrameLayout(this)

        // 彩虹描边
        val rainbowColors = intArrayOf(Color.parseColor("#FF0000"), Color.parseColor("#FF7F00"), Color.parseColor("#FFFF00"), Color.parseColor("#00FF00"), Color.parseColor("#00FFFF"), Color.parseColor("#0000FF"), Color.parseColor("#8B00FF"))
        val rainbowBackground = GradientDrawable(GradientDrawable.Orientation.TL_BR, rainbowColors).apply {
            gradientType = GradientDrawable.SWEEP_GRADIENT
            shape = GradientDrawable.OVAL
        }
        container.background = rainbowBackground

        // 内容层
        val contentContainer = FrameLayout(this)
        val contentBackground = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#D9FFFFFF"))
        }
        contentContainer.background = contentBackground

        val icon = ImageView(this)
        icon.setImageResource(android.R.drawable.ic_menu_gallery)
        icon.setColorFilter(Color.parseColor("#333333"))

        val iconParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT).apply {
            val padding = dp2px(10)
            setMargins(padding, padding, padding, padding)
            gravity = Gravity.CENTER
        }
        contentContainer.addView(icon, iconParams)

        val borderWidth = dp2px(3)
        val contentParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT).apply {
            setMargins(borderWidth, borderWidth, borderWidth, borderWidth)
        }
        container.addView(contentContainer, contentParams)

        floatingView = container

        val size = dp2px(48)

        layoutParams = WindowManager.LayoutParams().apply {
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            }
            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

            // 保持右下角重力，方便反向计算
            gravity = Gravity.BOTTOM or Gravity.END
            x = savedX
            y = savedY
            width = size
            height = size
        }

        floatingView.setOnTouchListener(object : View.OnTouchListener {
            var initialX = 0
            var initialY = 0
            var initialTouchX = 0f
            var initialTouchY = 0f
            var isMoving = false
            var startClickTime: Long = 0

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = layoutParams.x
                        initialY = layoutParams.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isMoving = false
                        startClickTime = System.currentTimeMillis()
                        contentBackground.setColor(Color.parseColor("#B3FFFFFF"))
                        isLongPressTriggered = false
                        handler.postDelayed(longPressRunnable, 1500)
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        handler.removeCallbacks(longPressRunnable)
                        contentBackground.setColor(Color.parseColor("#D9FFFFFF"))

                        // 保存位置
                        if (isMoving) {
                            prefs.edit().putInt("float_x", layoutParams.x).putInt("float_y", layoutParams.y).apply()
                        }

                        if (isLongPressTriggered) {
                            isLongPressTriggered = false
                            return true
                        }

                        if (!isMoving) {
                            val duration = System.currentTimeMillis() - startClickTime
                            if (duration < 500) {
                                val intent = Intent(this@FloatingService, MainActivity::class.java)
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                                startActivity(intent)
                            }
                        }
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (initialTouchX - event.rawX).toInt()
                        val dy = (initialTouchY - event.rawY).toInt()

                        if (abs(dx) > 10 || abs(dy) > 10) {
                            handler.removeCallbacks(longPressRunnable)
                            isMoving = true
                            layoutParams.x = initialX + dx
                            layoutParams.y = initialY + dy
                            try {
                                windowManager.updateViewLayout(floatingView, layoutParams)
                            } catch (e: Exception) {}
                        }
                        return true
                    }
                }
                return false
            }
        })

        try {
            windowManager.addView(floatingView, layoutParams)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun dp2px(dp: Int): Int {
        val scale = resources.displayMetrics.density
        return (dp * scale + 0.5f).toInt()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(longPressRunnable)
        if (::floatingView.isInitialized) {
            try {
                // 退出前再保存一次，双重保险
                val prefs = getSharedPreferences("meme_prefs", Context.MODE_PRIVATE)
                prefs.edit().putInt("float_x", layoutParams.x).putInt("float_y", layoutParams.y).apply()
                windowManager.removeView(floatingView)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
