package com.autoclicker

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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
 * - TARGET_COLOR / TARGET_OVERLAY_COLOR : 스포이트 드래그 UI로 색상 추출
 *   → 스크린샷 기반 실시간 색상 미리보기 + 드래그 커서
 */
class CoordPickerService : Service() {

    companion object {
        const val EXTRA_PICK_TARGET    = "pick_target"
        const val TARGET_START         = "start"
        const val TARGET_END           = "end"
        const val TARGET_TRIGGER       = "trigger"
        const val TARGET_OVERLAY_ADD   = "overlay_add"
        const val TARGET_COLOR         = "color"
        const val TARGET_OVERLAY_COLOR = "overlay_color"

        const val ACTION_OVERLAY_COORD_PICKED = "com.autoclicker.OVERLAY_COORD_PICKED"
        const val EXTRA_PICKED_X = "picked_x"
        const val EXTRA_PICKED_Y = "picked_y"

        private const val SCREENSHOT_TIMEOUT_MS = 2000L
    }

    private lateinit var wm: WindowManager
    private var root: View? = null
    private var screenshotBitmap: Bitmap? = null
    private var screenshotReceiver: BroadcastReceiver? = null
    private val timeoutHandler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val target = intent?.getStringExtra(EXTRA_PICK_TARGET) ?: TARGET_START
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        if (target == TARGET_COLOR || target == TARGET_OVERLAY_COLOR) {
            requestScreenshotAndShowDropper(target)
        } else {
            showTapPicker(target)
        }
        return START_NOT_STICKY
    }

    // ── 스포이트 드래그 모드 ─────────────────────────────────────────────

    private fun requestScreenshotAndShowDropper(target: String) {
        val recv = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                timeoutHandler.removeCallbacksAndMessages(null)
                unregisterReceiver(this)
                screenshotReceiver = null
                val path = intent.getStringExtra(AutoClickAccessibilityService.EXTRA_DROPPER_SCREENSHOT_PATH)
                screenshotBitmap = path?.let { runCatching { BitmapFactory.decodeFile(it) }.getOrNull() }
                showDropperOverlay(target)
            }
        }
        screenshotReceiver = recv
        val filter = IntentFilter(AutoClickAccessibilityService.ACTION_DROPPER_SCREENSHOT_READY)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            registerReceiver(recv, filter, RECEIVER_NOT_EXPORTED)
        else
            registerReceiver(recv, filter)

        sendBroadcast(Intent(AutoClickAccessibilityService.ACTION_REQUEST_DROPPER_SCREENSHOT).apply {
            setPackage(packageName)
        })

        // 타임아웃: 접근성 서비스가 응답 없으면 비트맵 없이 표시
        timeoutHandler.postDelayed({
            screenshotReceiver?.let {
                runCatching { unregisterReceiver(it) }
                screenshotReceiver = null
            }
            showDropperOverlay(target)
        }, SCREENSHOT_TIMEOUT_MS)
    }

    private fun showDropperOverlay(target: String) {
        val bitmap = screenshotBitmap
        val dm = resources.displayMetrics
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels

        val rootView = FrameLayout(this)

        val dropperView = DropperView(this, bitmap, screenW, screenH) { x, y, color ->
            dismiss()
            deliverDropperResult(x, y, color, target)
        }

        val cancelBtn = Button(this).apply {
            text = "취소"
            setBackgroundColor(0xDD1A1A2E.toInt())
            setTextColor(Color.WHITE)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            setOnClickListener { cancelDropper(target) }
        }

        rootView.addView(dropperView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        rootView.addView(cancelBtn, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.END
        ).also { it.topMargin = dp(48); it.marginEnd = dp(16) })

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

    private fun deliverDropperResult(x: Int, y: Int, color: Int?, target: String) {
        if (color != null) {
            // 비트맵에서 이미 색상을 얻었으므로 바로 브로드캐스트
            sendBroadcast(Intent(AutoClickAccessibilityService.ACTION_COLOR_SAMPLED).apply {
                setPackage(packageName)
                putExtra(AutoClickAccessibilityService.EXTRA_SAMPLE_X, x)
                putExtra(AutoClickAccessibilityService.EXTRA_SAMPLE_Y, y)
                putExtra(AutoClickAccessibilityService.EXTRA_SAMPLED_COLOR, color)
                putExtra(AutoClickAccessibilityService.EXTRA_SAMPLE_TARGET, target)
            })
        } else {
            // 비트맵 없음 — AccessibilityService에 위임
            sendBroadcast(Intent(AutoClickAccessibilityService.ACTION_REQUEST_COLOR_SAMPLE).apply {
                setPackage(packageName)
                putExtra(AutoClickAccessibilityService.EXTRA_SAMPLE_X, x)
                putExtra(AutoClickAccessibilityService.EXTRA_SAMPLE_Y, y)
                putExtra(AutoClickAccessibilityService.EXTRA_SAMPLE_TARGET, target)
            })
        }
        if (target == TARGET_COLOR) bringAppToFront()
        stopSelf()
    }

    private fun cancelDropper(target: String) {
        dismiss()
        if (target == TARGET_COLOR) {
            MainActivity.pickCancelled = true
            bringAppToFront()
        }
        stopSelf()
    }

    // ── 스포이트 드래그 뷰 ───────────────────────────────────────────────

    private inner class DropperView(
        ctx: Context,
        private val bitmap: Bitmap?,
        private val screenW: Int,
        private val screenH: Int,
        private val onPick: (x: Int, y: Int, color: Int?) -> Unit
    ) : View(ctx) {

        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.WHITE
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.LEFT
        }
        private val centerTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
        }

        private var touchX = 0f
        private var touchY = 0f
        private var hasTouch = false
        private var currentColor = Color.GRAY
        private var hasSampledColor = false

        private fun colorFromBitmap(x: Float, y: Float): Int? {
            val bmp = bitmap ?: return null
            if (bmp.isRecycled) return null
            val bx = (x / screenW * bmp.width).toInt().coerceIn(0, bmp.width - 1)
            val by = (y / screenH * bmp.height).toInt().coerceIn(0, bmp.height - 1)
            return bmp.getPixel(bx, by)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            touchX = event.x
            touchY = event.y
            hasTouch = true
            val c = colorFromBitmap(event.x, event.y)
            if (c != null) { currentColor = c; hasSampledColor = true }
            invalidate()
            if (event.action == MotionEvent.ACTION_UP) {
                onPick(event.x.toInt(), event.y.toInt(), if (hasSampledColor) currentColor else null)
            }
            return true
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()

            if (!hasTouch) {
                // 첫 안내 메시지
                centerTextPaint.textSize = sp(16f)
                centerTextPaint.color = Color.WHITE
                canvas.drawText("드래그하여 색상을 선택하세요", w / 2, h * 0.08f, centerTextPaint)
                centerTextPaint.textSize = sp(12f)
                centerTextPaint.color = 0xFFAAAAAA.toInt()
                canvas.drawText("손가락을 떼면 선택 완료", w / 2, h * 0.08f + dp(26), centerTextPaint)
                return
            }

            val cx = touchX
            val cy = touchY

            // 십자선 (4방향 선)
            val cross = dp(22).toFloat()
            val gap   = dp(7).toFloat()
            strokePaint.strokeWidth = dp(2).toFloat()
            strokePaint.color = 0xCCFFFFFF.toInt()
            canvas.drawLine(cx, cy - gap - cross, cx, cy - gap, strokePaint)
            canvas.drawLine(cx, cy + gap, cx, cy + gap + cross, strokePaint)
            canvas.drawLine(cx - gap - cross, cy, cx - gap, cy, strokePaint)
            canvas.drawLine(cx + gap, cy, cx + gap + cross, cy, strokePaint)

            // 중심 원 (현재 색상으로 채움)
            val dotR = dp(10).toFloat()
            fillPaint.color = currentColor
            canvas.drawCircle(cx, cy, dotR, fillPaint)
            strokePaint.color = Color.WHITE
            strokePaint.strokeWidth = dp(2).toFloat()
            canvas.drawCircle(cx, cy, dotR, strokePaint)
            // 외곽 검정 테두리 (가독성)
            strokePaint.color = 0x99000000.toInt()
            strokePaint.strokeWidth = dp(1).toFloat()
            canvas.drawCircle(cx, cy, dotR + dp(1), strokePaint)

            // 색상 칩 — 커서 위쪽 (화면 상단에 가까우면 아래쪽으로)
            val chipW = dp(140).toFloat()
            val chipH = dp(44).toFloat()
            val chipMargin = dp(20).toFloat()
            val chipY = if (cy > h * 0.25f) cy - chipMargin - chipH else cy + chipMargin
            val chipX = (cx - chipW / 2).coerceIn(dp(8).toFloat(), w - chipW - dp(8))

            // 칩 배경
            fillPaint.color = 0xEE111827.toInt()
            val chipRect = RectF(chipX, chipY, chipX + chipW, chipY + chipH)
            canvas.drawRoundRect(chipRect, dp(10).toFloat(), dp(10).toFloat(), fillPaint)

            // 색상 스와치
            val swatchR = dp(13).toFloat()
            val swatchCx = chipX + dp(22)
            val swatchCy = chipY + chipH / 2
            fillPaint.color = currentColor
            canvas.drawCircle(swatchCx, swatchCy, swatchR, fillPaint)
            strokePaint.color = Color.WHITE
            strokePaint.strokeWidth = dp(1.5f).toFloat()
            canvas.drawCircle(swatchCx, swatchCy, swatchR, strokePaint)

            // HEX 텍스트
            textPaint.textSize = sp(13f)
            textPaint.color = Color.WHITE
            val hex = "#%06X".format(currentColor and 0xFFFFFF)
            canvas.drawText(hex, chipX + dp(42), chipY + chipH / 2 + sp(5f), textPaint)

            // 좌표 텍스트 (칩 아래 또는 위)
            val coordY = if (cy > h * 0.25f) chipY - dp(4) else chipY + chipH + dp(16)
            centerTextPaint.textSize = sp(10f)
            centerTextPaint.color = 0xFFAAAAAA.toInt()
            canvas.drawText("(${touchX.toInt()}, ${touchY.toInt()})", cx, coordY, centerTextPaint)
        }

        @Suppress("DEPRECATION")
        private fun sp(v: Float) = v * resources.displayMetrics.scaledDensity
    }

    // ── 일반 탭 피커 모드 (색상 외 용도) ────────────────────────────────

    private fun showTapPicker(target: String) {
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
            setOnClickListener { cancelTap(target) }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).also { it.gravity = Gravity.CENTER_HORIZONTAL; it.topMargin = dp(8) })

        rootView.addView(touchLayer, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))
        rootView.addView(banner, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP
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
        when (target) {
            TARGET_OVERLAY_ADD -> {
                sendBroadcast(Intent(ACTION_OVERLAY_COORD_PICKED).apply {
                    setPackage(packageName)
                    putExtra(EXTRA_PICKED_X, x)
                    putExtra(EXTRA_PICKED_Y, y)
                })
            }
            else -> {
                MainActivity.pickedCoord = Triple(x, y, target)
                bringAppToFront()
            }
        }
        stopSelf()
    }

    private fun cancelTap(target: String) {
        dismiss()
        if (target != TARGET_OVERLAY_ADD) {
            MainActivity.pickCancelled = true
            bringAppToFront()
        }
        stopSelf()
    }

    // ── 공통 ─────────────────────────────────────────────────────────────

    private fun dismiss() {
        root?.let { runCatching { wm.removeView(it) } }
        root = null
        screenshotBitmap?.recycle()
        screenshotBitmap = null
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

    private fun dp(value: Float): Float =
        value * resources.displayMetrics.density

    override fun onDestroy() {
        super.onDestroy()
        timeoutHandler.removeCallbacksAndMessages(null)
        screenshotReceiver?.let { runCatching { unregisterReceiver(it) } }
        screenshotReceiver = null
        dismiss()
    }
}
