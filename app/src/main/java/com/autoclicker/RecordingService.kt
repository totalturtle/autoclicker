package com.autoclicker

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.IBinder
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView

class RecordingService : Service() {

    companion object {
        const val ACTION_STOP_RECORDING = "com.autoclicker.ACTION_STOP_RECORDING"
        const val ACTION_RECORDING_DONE = "com.autoclicker.ACTION_RECORDING_DONE"
        const val EXTRA_RECORDED_POINTS = "extra_recorded_points"

        // 탭 vs 스와이프 구분 임계값
        private const val SWIPE_THRESHOLD_PX = 20
        private const val LONG_PRESS_THRESHOLD_MS = 500L
    }

    private lateinit var wm: WindowManager
    private var touchOverlay: View? = null
    private var stopButton: TextView? = null

    private val recordedPoints = mutableListOf<ClickPoint>()
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var lastUpTime = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        showOverlay()
    }

    override fun onDestroy() {
        super.onDestroy()
        touchOverlay?.let { runCatching { wm.removeView(it) } }
        stopButton?.let { runCatching { wm.removeView(it) } }
    }

    private fun overlayType() =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    private fun showOverlay() {
        // 투명 전체화면 터치 수집 뷰
        val overlay = object : View(this) {
            override fun onTouchEvent(event: MotionEvent): Boolean {
                handleTouch(event)
                return true
            }
        }
        val overlayParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        wm.addView(overlay, overlayParams)
        touchOverlay = overlay

        // 중지 버튼
        val stopBtn = TextView(this).apply {
            text = "⏹ 녹화 중지"
            setBackgroundColor(0xFFE53935.toInt())
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setOnClickListener { finishRecording() }
        }
        val stopParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(48)
        }
        wm.addView(stopBtn, stopParams)
        stopButton = stopBtn
    }

    private fun handleTouch(event: MotionEvent) {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                downTime = SystemClock.uptimeMillis()
            }
            MotionEvent.ACTION_UP -> {
                val upX = event.rawX
                val upY = event.rawY
                val upTime = SystemClock.uptimeMillis()
                val pressDuration = upTime - downTime
                val dx = upX - downX
                val dy = upY - downY
                val dist = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()

                // 이전 포인트와의 딜레이
                val delayAfterMs = if (lastUpTime == 0L) 0L else (downTime - lastUpTime)
                lastUpTime = upTime

                val point = when {
                    dist > SWIPE_THRESHOLD_PX -> ClickPoint(
                        x = downX.toInt(),
                        y = downY.toInt(),
                        gesture = GestureType.SWIPE,
                        endX = upX.toInt(),
                        endY = upY.toInt(),
                        swipeDurationMs = pressDuration,
                        delayAfterMs = delayAfterMs
                    )
                    pressDuration >= LONG_PRESS_THRESHOLD_MS -> ClickPoint(
                        x = downX.toInt(),
                        y = downY.toInt(),
                        gesture = GestureType.LONG_PRESS,
                        longPressDurationMs = pressDuration,
                        delayAfterMs = delayAfterMs
                    )
                    else -> ClickPoint(
                        x = downX.toInt(),
                        y = downY.toInt(),
                        gesture = GestureType.TAP,
                        delayAfterMs = delayAfterMs
                    )
                }
                recordedPoints.add(point)
            }
        }
    }

    private fun finishRecording() {
        val intent = Intent(ACTION_RECORDING_DONE).apply {
            setPackage(packageName)
            val jsonArray = org.json.JSONArray()
            recordedPoints.forEach { pt ->
                jsonArray.put(org.json.JSONObject().apply {
                    put("x", pt.x); put("y", pt.y)
                    put("gesture", pt.gesture.name)
                    put("endX", pt.endX); put("endY", pt.endY)
                    put("longPressDurationMs", pt.longPressDurationMs)
                    put("swipeDurationMs", pt.swipeDurationMs)
                    put("delayAfterMs", pt.delayAfterMs)
                })
            }
            putExtra(EXTRA_RECORDED_POINTS, jsonArray.toString())
        }
        sendBroadcast(intent)
        stopSelf()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()
}
