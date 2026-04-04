package com.autoclicker

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 화면에서 좌표를 직접 탭하여 선택할 수 있는 전체화면 투명 오버레이 서비스.
 * 탭 좌표를 MainActivity.pickedCoord 에 저장한 뒤 앱을 포그라운드로 복귀시킨다.
 */
class CoordPickerService : Service() {

    companion object {
        const val EXTRA_PICK_TARGET = "pick_target"
        const val TARGET_START   = "start"   // 시작 X, Y
        const val TARGET_END     = "end"     // 스와이프 끝 X, Y
        const val TARGET_TRIGGER = "trigger" // 트리거 확인 X, Y
    }

    private lateinit var wm: WindowManager
    private var root: View? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val target = intent?.getStringExtra(EXTRA_PICK_TARGET) ?: TARGET_START
        showPicker(target)
        return START_NOT_STICKY
    }

    private fun showPicker(target: String) {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager

        val rootView = FrameLayout(this)

        // 반투명 전체화면 터치 캡처 레이어
        val touchLayer = View(this).apply {
            setBackgroundColor(0x33000000)
            isClickable = true
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_UP) {
                    deliver(event.rawX.toInt(), event.rawY.toInt(), target)
                }
                true
            }
        }

        // 상단 안내 배너 (터치 흡수하여 좌표 전달 방지)
        val banner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xDD1A237E.toInt())
            isClickable = true
            setPadding(dp(16), dp(48), dp(16), dp(16))
        }

        banner.addView(TextView(this).apply {
            text = getString(R.string.coord_picker_instruction)
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        banner.addView(Button(this).apply {
            text = getString(android.R.string.cancel)
            setOnClickListener { cancel() }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).also { it.gravity = Gravity.CENTER_HORIZONTAL; it.topMargin = dp(8) })

        rootView.addView(touchLayer, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        rootView.addView(banner, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP
        ))

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        wm.addView(rootView, params)
        root = rootView
    }

    private fun deliver(x: Int, y: Int, target: String) {
        MainActivity.pickedCoord = Triple(x, y, target)
        bringAppToFront()
        stopSelf()
    }

    private fun cancel() {
        MainActivity.pickCancelled = true
        bringAppToFront()
        stopSelf()
    }

    private fun bringAppToFront() {
        startActivity(Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_NEW_TASK
        })
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    override fun onDestroy() {
        super.onDestroy()
        root?.let { runCatching { wm.removeView(it) } }
    }
}