package com.example.mememanagement

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView

class FloatingService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View
    private lateinit var layoutParams: WindowManager.LayoutParams

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

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

        floatingView.setOnClickListener {
            val intent = Intent(this, MainActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            startActivity(intent)
        }

        val size = dp2px(48)

        // === 位置调整：右下角 ===
        layoutParams = WindowManager.LayoutParams().apply {
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            }
            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

            // 重力设为：底部 + 右侧
            gravity = Gravity.BOTTOM or Gravity.END
            x = 0
            y = 300 // 距离底部 300px (大约拇指位置)
            width = size
            height = size
        }

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
                        contentBackground.setColor(Color.parseColor("#B3FFFFFF"))
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        contentBackground.setColor(Color.parseColor("#D9FFFFFF"))
                        if (!isMoving) {
                            v.performClick()
                        }
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        // 注意：Gravity.END 时，x 轴也是反向的，但这里我们用简单的加减法即可，
                        // 因为 updateViewLayout 会处理相对位移，或者最简单的逻辑是改为 TOP|START 绝对坐标计算。
                        // 为了拖动顺滑，建议拖动时暂时改为 TOP|START，或者依然用 updateViewLayout。
                        // 简单起见，拖动时我们修改 x, y 是相对于 gravity 的。
                        // Gravity.BOTTOM | END 时：
                        // x 越大越往左，y 越大越往上。
                        // 所以这里逻辑要反过来：手指往左(-dx)，x要增加；手指往上(-dy)，y要增加。

                        val dx = (initialTouchX - event.rawX).toInt() // 反向
                        val dy = (initialTouchY - event.rawY).toInt() // 反向

                        if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                            isMoving = true
                            layoutParams.x = initialX + dx
                            layoutParams.y = initialY + dy
                            windowManager.updateViewLayout(floatingView, layoutParams)
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
        if (::floatingView.isInitialized) {
            windowManager.removeView(floatingView)
        }
    }
}
