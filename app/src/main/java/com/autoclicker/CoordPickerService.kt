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
 * 화면을 터치하여 좌표를 선택하는 전체화면 투명 오버레이 서비스.
 *
 * - TARGET_START / TARGET_END / TARGET_TRIGGER : MainActivity 다이얼로그 복원용
 *   → pickedCoord 에 저장 후 앱 포그라운드 복귀
 * - TARGET_OVERLAY_ADD : OverlayService에서 빠른 포인트 추가용
 *   → ACTION_OVERLAY_COORD_PICKED 브로드캐스트 전송 (앱 복귀 없음)
 */
class CoordPickerService : Service() {

    companion object {
        const val EXTRA_PICK_TARGET = "pick_target"
        const val TARGET_START       = "start"
        const val TARGET_END         = "end"
        const val TARGET_TRIGGER     = "trigger"
        const val TARGET_OVERLAY_ADD = "overlay_add"

        /** OverlayService 가 수신하는 브로드캐스트 */
        const val ACTION_OVERLAY_COORD_PICKED = "com.autoclicker.OVERLAY_COORD_PICKED"
        const val EXTRA_PICKED_X = "picked_x"
        const val EXTRA_PICKED_Y = "picked_y"
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

        val instructionText = when (target) {
            TARGET_OVERLAY_ADD -> "클릭할 위치를 터치하세요\n(포인트로 즉시 추가됩니다)"
            TARGET_END         -> "스와이프 끝 좌표를 터치하세요"
            TARGET_TRIGGER     -> "트리거 확인 좌표를 터치하세요"
            else               -> getString(R.string.coord_picker_instruction)
        }

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

        val banner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xDD0D1117.toInt())
            isClickable = true
            setPadding(dp(16), dp(48), dp(16), dp(16))
        }

        banner.addView(TextView(this).apply {
            text = instructionText
            setTextColor(Color.WHITE)
            textSize = 17f
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        banner.addView(Button(this).apply {
            text = "취소"
            setOnClickListener { cancel(target) }
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
        dismiss()
        if (target == TARGET_OVERLAY_ADD) {
            // 오버레이에서 빠른 추가 — 앱 복귀 없이 브로드캐스트만
            sendBroadcast(Intent(ACTION_OVERLAY_COORD_PICKED).apply {
                setPackage(packageName)
                putExtra(EXTRA_PICKED_X, x)
                putExtra(EXTRA_PICKED_Y, y)
            })
        } else {
            // 메인 다이얼로그 복원용
            MainActivity.pickedCoord = Triple(x, y, target)
            bringAppToFront()
        }
        stopSelf()
    }

    private fun cancel(target: String) {
        dismiss()
        if (target != TARGET_OVERLAY_ADD) {
            MainActivity.pickCancelled = true
            bringAppToFront()
        }
        stopSelf()
    }

    private fun dismiss() {
        root?.let { runCatching { wm.removeView(it) } }
        root = null
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
        dismiss()
    }
}
