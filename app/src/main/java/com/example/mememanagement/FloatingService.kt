package com.example.mememanagement

import android.app.Service
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

    // === 新增：长按检测相关变量 ===
    private val handler = Handler(Looper.getMainLooper())
    private var isLongPressTriggered = false // 标记是否触发了长按
    private val longPressRunnable = Runnable {
        isLongPressTriggered = true
        // 触发长按逻辑：暂时关闭
        try {
            // 可选：加一个震动反馈让用户知道生效了 (需 VIBRATE 权限，没有也不影响运行)
            // val v = getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
            // v.vibrate(android.os.VibrationEffect.createOneShot(50, -1))
        } catch (e: Exception) {}

        Toast.makeText(applicationContext, "悬浮窗已暂时隐藏", Toast.LENGTH_SHORT).show()
        stopSelf() // 销毁服务 -> 悬浮窗消失
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val container = FrameLayout(this)

        // 1. 保留原有的彩虹描边样式
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

        // 点击回主页逻辑
        floatingView.setOnClickListener {
            val intent = Intent(this, MainActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            startActivity(intent)
        }

        val size = dp2px(48)

        // 2. 保留原有的右下角定位参数
        layoutParams = WindowManager.LayoutParams().apply {
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            }
            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

            gravity = Gravity.BOTTOM or Gravity.END
            x = 0
            y = 300
            width = size
            height = size
        }

        // 3. 增强版触摸监听 (集成长按逻辑)
        floatingView.setOnTouchListener(object : View.OnTouchListener {
            var initialX = 0
            var initialY = 0
            var initialTouchX = 0f
            var initialTouchY = 0f
            var isMoving = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = layoutParams.x
                        initialY = layoutParams.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isMoving = false
                        contentBackground.setColor(Color.parseColor("#B3FFFFFF")) // 按下变色效果

                        // === 核心：开始长按倒计时 ===
                        isLongPressTriggered = false
                        handler.postDelayed(longPressRunnable, 1500) // 3000毫秒 = 3秒

                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        // === 核心：取消长按倒计时 ===
                        handler.removeCallbacks(longPressRunnable)
                        contentBackground.setColor(Color.parseColor("#D9FFFFFF")) // 恢复颜色

                        // 如果已经触发了长按，就不要再响应点击了
                        if (isLongPressTriggered) return true

                        if (!isMoving) {
                            v.performClick()
                        }
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (initialTouchX - event.rawX).toInt() // 反向计算 (适应 Gravity.END)
                        val dy = (initialTouchY - event.rawY).toInt() // 反向计算 (适应 Gravity.BOTTOM)

                        // 如果移动超过 10 像素
                        if (abs(dx) > 10 || abs(dy) > 10) {
                            // === 核心：一旦开始拖拽，立刻取消长按计时 ===
                            handler.removeCallbacks(longPressRunnable)

                            isMoving = true
                            layoutParams.x = initialX + dx
                            layoutParams.y = initialY + dy
                            try {
                                windowManager.updateViewLayout(floatingView, layoutParams)
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
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
        // 退出时务必移除 Runnable，防止内存泄漏
        handler.removeCallbacks(longPressRunnable)

        if (::floatingView.isInitialized) {
            try {
                windowManager.removeView(floatingView)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
