package com.autoclicker

import android.app.Service
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
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
 * 화면을 터치하여 좌표·색상을 선택하는 전체화면 투명 오버레이 서비스.
 *
 * - TARGET_START / TARGET_END / TARGET_TRIGGER : MainActivity 다이얼로그 복원용
 * - TARGET_OVERLAY_ADD : OverlayService에서 빠른 포인트 추가용
 * - TARGET_COLOR / TARGET_OVERLAY_COLOR : 스포이트 UI → 좌표 선택 후 접근성 서비스가 픽셀 색상 추출
 */
class CoordPickerService : Service() {

    companion object {
        const val EXTRA_PICK_TARGET    = "pick_target"
        const val TARGET_START         = "start"
        const val TARGET_END           = "end"
        const val TARGET_TRIGGER       = "trigger"
        const val TARGET_OVERLAY_ADD   = "overlay_add"
        const val TARGET_COLOR           = "color"
        const val TARGET_OVERLAY_COLOR   = "overlay_color"
        const val TARGET_REGION_CAPTURE  = "region_capture"
        const val EXTRA_REGION_RESULT_TARGET = "region_result_target"

        const val ACTION_OVERLAY_COORD_PICKED = "com.autoclicker.OVERLAY_COORD_PICKED"
        const val EXTRA_PICKED_X = "picked_x"
        const val EXTRA_PICKED_Y = "picked_y"
    }

    private lateinit var wm: WindowManager
    private var root: View? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val target = intent?.getStringExtra(EXTRA_PICK_TARGET) ?: TARGET_START
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        when {
            target == TARGET_COLOR || target == TARGET_OVERLAY_COLOR -> showDropperPicker(target)
            target == TARGET_REGION_CAPTURE -> {
                val resultTarget = intent?.getStringExtra(EXTRA_REGION_RESULT_TARGET) ?: "overlay_region"
                showRegionPicker(resultTarget)
            }
            else -> showTapPicker(target)
        }
        return START_NOT_STICKY
    }

    // ── 스포이트 모드 ─────────────────────────────────────────────────────

    private fun showDropperPicker(target: String) {
        val rootView = FrameLayout(this)

        // 스포이트 위치로 포인트 이동 여부 (기본: OFF)
        var moveToDropper = false

        val moveBtn = Button(this).apply {
            text = "탭 위치도 동일하게: OFF"
            setBackgroundColor(0xFF37474F.toInt())
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }

        val dropperView = DropperPickerView(this) { x, y ->
            dismiss()
            sendBroadcast(Intent(AutoClickAccessibilityService.ACTION_REQUEST_COLOR_SAMPLE).apply {
                setPackage(packageName)
                putExtra(AutoClickAccessibilityService.EXTRA_SAMPLE_X, x)
                putExtra(AutoClickAccessibilityService.EXTRA_SAMPLE_Y, y)
                putExtra(AutoClickAccessibilityService.EXTRA_SAMPLE_TARGET, target)
                putExtra(AutoClickAccessibilityService.EXTRA_MOVE_TO_DROPPER, moveToDropper)
            })
            if (target == TARGET_COLOR) bringAppToFront()
            stopSelf()
        }

        moveBtn.setOnClickListener {
            moveToDropper = !moveToDropper
            if (moveToDropper) {
                moveBtn.text = "탭 위치도 동일하게: ON"
                moveBtn.setBackgroundColor(0xFF1B5E20.toInt())
            } else {
                moveBtn.text = "탭 위치도 동일하게: OFF"
                moveBtn.setBackgroundColor(0xFF37474F.toInt())
            }
        }

        val cancelBtn = Button(this).apply {
            text = "취소"
            setBackgroundColor(0xDD1A1A2E.toInt())
            setTextColor(Color.WHITE)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            setOnClickListener { cancelDropper(target) }
        }

        rootView.addView(dropperView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))
        // 취소 버튼: 우상단
        rootView.addView(cancelBtn, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.END
        ).also { it.topMargin = dp(48); it.marginEnd = dp(16) })
        // 이동 토글: 좌상단
        rootView.addView(moveBtn, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.START
        ).also { it.topMargin = dp(48); it.marginStart = dp(16) })

        wm.addView(rootView, overlayParams())
        root = rootView
    }

    private fun cancelDropper(target: String) {
        dismiss()
        when (target) {
            TARGET_COLOR -> {
                MainActivity.pickCancelled = true
                bringAppToFront()
            }
            TARGET_OVERLAY_COLOR -> {
                // pendingColorPickIndex 초기화용 취소 신호
                sendBroadcast(Intent(AutoClickAccessibilityService.ACTION_COLOR_SAMPLED).apply {
                    setPackage(packageName)
                    putExtra(AutoClickAccessibilityService.EXTRA_SAMPLE_TARGET, TARGET_OVERLAY_COLOR)
                    putExtra(AutoClickAccessibilityService.EXTRA_SAMPLED_COLOR, Int.MIN_VALUE)
                    putExtra(AutoClickAccessibilityService.EXTRA_SAMPLE_X, 0)
                    putExtra(AutoClickAccessibilityService.EXTRA_SAMPLE_Y, 0)
                })
            }
        }
        stopSelf()
    }

    // ── 스포이트 커서 뷰 ──────────────────────────────────────────────────
    // 끝점이 손가락보다 위쪽에 표시되어 손가락에 가려지지 않음.
    // 오버레이가 열리자마자 화면 중앙에 스포이트가 표시되고,
    // 드래그하면 스포이트가 따라움직이며 끝점 위치 좌표를 실시간으로 보여줌.

    private inner class DropperPickerView(
        ctx: android.content.Context,
        private val onPick: (x: Int, y: Int) -> Unit
    ) : View(ctx) {

        // 끝점은 손가락보다 이 만큼 위에 표시
        private val TIP_OFFSET_Y get() = dp(90)

        private val bgPaint      = Paint().apply { color = 0x55000000; style = Paint.Style.FILL }
        private val shadowPaint  = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xCC000000.toInt(); style = Paint.Style.STROKE
        }
        private val crossPaint   = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.STROKE
        }
        private val circlePaint  = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.STROKE
        }
        private val linePaint    = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x88FFFFFF.toInt(); style = Paint.Style.STROKE
        }
        private val fingerDotFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x66FFFFFF.toInt(); style = Paint.Style.FILL
        }
        private val fingerDotStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xAAFFFFFF.toInt(); style = Paint.Style.STROKE
        }
        private val chipFill     = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xEE111827.toInt(); style = Paint.Style.FILL
        }
        private val coordPaint   = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textAlign = Paint.Align.CENTER
        }
        private val textPaint    = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textAlign = Paint.Align.CENTER
        }
        private val subTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFAAAAAA.toInt(); textAlign = Paint.Align.CENTER
        }

        // 손가락 위치 (-1 = 아직 터치 없음 → 화면 중앙 사용)
        private var fingerX = -1f
        private var fingerY = -1f
        private var hasTouch = false

        // tip 위치 계산
        private fun tipX(): Float = if (fingerX < 0) width / 2f else fingerX
        private fun tipY(): Float = if (fingerY < 0) height * 0.45f
                                    else (fingerY - TIP_OFFSET_Y).toFloat().coerceAtLeast(0f)

        override fun onTouchEvent(event: MotionEvent): Boolean {
            fingerX = event.x
            fingerY = event.y
            hasTouch = true
            invalidate()
            if (event.action == MotionEvent.ACTION_UP) {
                // 색상 추출은 끝점(tip) 위치로
                onPick(tipX().toInt(), tipY().toInt())
            }
            return true
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            canvas.drawRect(0f, 0f, w, h, bgPaint)

            val tx = tipX()
            val ty = tipY()

            // 안내 텍스트 (첫 터치 전)
            if (!hasTouch) {
                textPaint.textSize = sp(17f)
                canvas.drawText("드래그하여 스포이트 끝점을 위치시키세요", w / 2f, h * 0.06f, textPaint)
                subTextPaint.textSize = sp(12f)
                canvas.drawText("손가락을 떼면 끝점(✕) 위치의 색상이 추출됩니다", w / 2f, h * 0.06f + dp(26), subTextPaint)
            }

            // 손가락→끝점 연결선 + 손가락 위치 표시
            if (hasTouch) {
                linePaint.strokeWidth = dp(1.5f)
                canvas.drawLine(tx, ty, fingerX, fingerY, linePaint)
                val fingerR = dp(18).toFloat()
                fingerDotFill.color = 0x33FFFFFF.toInt()
                canvas.drawCircle(fingerX, fingerY, fingerR, fingerDotFill)
                fingerDotStroke.strokeWidth = dp(1).toFloat()
                canvas.drawCircle(fingerX, fingerY, fingerR, fingerDotStroke)
            }

            // 끝점 십자선 (그림자 → 흰색 순서로 그려 가독성 확보)
            val crossLen = dp(14).toFloat()
            val crossGap = dp(6).toFloat()
            val circleR  = dp(18).toFloat()

            shadowPaint.strokeWidth = dp(4).toFloat()
            canvas.drawCircle(tx, ty, circleR, shadowPaint)
            shadowPaint.strokeWidth = dp(3.5f)
            canvas.drawLine(tx - crossLen - crossGap, ty, tx - crossGap, ty, shadowPaint)
            canvas.drawLine(tx + crossGap, ty, tx + crossLen + crossGap, ty, shadowPaint)
            canvas.drawLine(tx, ty - crossLen - crossGap, tx, ty - crossGap, shadowPaint)
            canvas.drawLine(tx, ty + crossGap, tx, ty + crossLen + crossGap, shadowPaint)

            circlePaint.strokeWidth = dp(2).toFloat()
            canvas.drawCircle(tx, ty, circleR, circlePaint)
            crossPaint.strokeWidth = dp(2).toFloat()
            canvas.drawLine(tx - crossLen - crossGap, ty, tx - crossGap, ty, crossPaint)
            canvas.drawLine(tx + crossGap, ty, tx + crossLen + crossGap, ty, crossPaint)
            canvas.drawLine(tx, ty - crossLen - crossGap, tx, ty - crossGap, crossPaint)
            canvas.drawLine(tx, ty + crossGap, tx, ty + crossLen + crossGap, crossPaint)

            // 좌표 칩 (끝점 바로 위)
            coordPaint.textSize = sp(12f)
            val coordText = "(${tx.toInt()}, ${ty.toInt()})"
            val chipPad = dp(8).toFloat()
            val chipH   = dp(26).toFloat()
            val chipW   = coordPaint.measureText(coordText) + chipPad * 2
            val chipX   = (tx - chipW / 2).coerceIn(dp(4).toFloat(), w - chipW - dp(4))
            val chipY   = ty - circleR - chipH - dp(4)
            canvas.drawRoundRect(
                RectF(chipX, chipY, chipX + chipW, chipY + chipH),
                dp(6).toFloat(), dp(6).toFloat(), chipFill
            )
            canvas.drawText(coordText, chipX + chipW / 2, chipY + chipH / 2 + sp(4.5f), coordPaint)
        }

        @Suppress("DEPRECATION")
        private fun sp(v: Float) = v * resources.displayMetrics.scaledDensity
    }

    // ── 이미지 영역 드래그 선택 모드 ──────────────────────────────────────

    private fun showRegionPicker(resultTarget: String) {
        val rootView = FrameLayout(this)

        val regionView = RegionSelectView(this) { x, y, w, h ->
            dismiss()
            sendBroadcast(Intent(AutoClickAccessibilityService.ACTION_REQUEST_REGION_CAPTURE).apply {
                setPackage(packageName)
                putExtra(AutoClickAccessibilityService.EXTRA_REGION_X, x)
                putExtra(AutoClickAccessibilityService.EXTRA_REGION_Y, y)
                putExtra(AutoClickAccessibilityService.EXTRA_REGION_W, w)
                putExtra(AutoClickAccessibilityService.EXTRA_REGION_H, h)
                putExtra(AutoClickAccessibilityService.EXTRA_REGION_TARGET, resultTarget)
            })
            if (resultTarget == "main_region") bringAppToFront()
            stopSelf()
        }

        val cancelBtn = Button(this).apply {
            text = "취소"
            setBackgroundColor(0xDD1A1A2E.toInt())
            setTextColor(Color.WHITE)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            setOnClickListener {
                dismiss()
                // 빈 pixels 로 취소 신호 → 각 수신자가 원래 다이얼로그 복원
                sendBroadcast(Intent(AutoClickAccessibilityService.ACTION_REGION_CAPTURED).apply {
                    setPackage(packageName)
                    putExtra(AutoClickAccessibilityService.EXTRA_REGION_TARGET, resultTarget)
                    putExtra(AutoClickAccessibilityService.EXTRA_REGION_PIXELS, IntArray(0))
                    putExtra(AutoClickAccessibilityService.EXTRA_REGION_W, 0)
                    putExtra(AutoClickAccessibilityService.EXTRA_REGION_H, 0)
                })
                if (resultTarget == "main_region") bringAppToFront()
                stopSelf()
            }
        }

        rootView.addView(regionView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))
        rootView.addView(cancelBtn, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.END
        ).also { it.topMargin = dp(48); it.marginEnd = dp(16) })

        wm.addView(rootView, overlayParams())
        root = rootView
    }

    private inner class RegionSelectView(
        ctx: android.content.Context,
        private val onRegionSelected: (x: Int, y: Int, w: Int, h: Int) -> Unit
    ) : View(ctx) {

        private var startX = 0f
        private var startY = 0f
        private var curX   = 0f
        private var curY   = 0f
        private var isSelecting = false

        private val MAX_PX = 300  // 최대 캡처 크기 (픽셀)

        private val dimPaint = Paint().apply { color = Color.argb(160, 0, 0, 0) }
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 3f
            pathEffect = DashPathEffect(floatArrayOf(20f, 10f), 0f)
        }
        private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        private val sizePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        private val instructPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
        }
        private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFAAAAAA.toInt()
            textAlign = Paint.Align.CENTER
        }
        private val chipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xEE111827.toInt()
            style = Paint.Style.FILL
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.x; startY = event.y
                    curX   = event.x; curY   = event.y
                    isSelecting = true; invalidate()
                }
                MotionEvent.ACTION_MOVE -> {
                    curX = event.x; curY = event.y; invalidate()
                }
                MotionEvent.ACTION_UP -> {
                    curX = event.x; curY = event.y
                    isSelecting = false; invalidate()
                    val left  = minOf(startX, curX).toInt().coerceAtLeast(0)
                    val top   = minOf(startY, curY).toInt().coerceAtLeast(0)
                    val right = maxOf(startX, curX).toInt()
                    val bottom = maxOf(startY, curY).toInt()
                    val w = (right - left).coerceAtMost(MAX_PX)
                    val h = (bottom - top).coerceAtMost(MAX_PX)
                    if (w > 10 && h > 10) onRegionSelected(left, top, w, h)
                }
            }
            return true
        }

        override fun onDraw(canvas: Canvas) {
            val vw = width.toFloat()
            val vh = height.toFloat()
            val density = resources.displayMetrics.density

            if (!isSelecting) {
                canvas.drawRect(0f, 0f, vw, vh, dimPaint)
                instructPaint.textSize = 18f * density
                canvas.drawText("드래그하여 비교 영역을 지정하세요", vw / 2f, vh * 0.12f, instructPaint)
                subPaint.textSize = 13f * density
                canvas.drawText("손을 떼면 해당 영역이 캡처됩니다 (최대 ${MAX_PX}×${MAX_PX}px)",
                    vw / 2f, vh * 0.12f + 32f * density, subPaint)
                return
            }

            val left   = minOf(startX, curX)
            val top    = minOf(startY, curY)
            val right  = maxOf(startX, curX)
            val bottom = maxOf(startY, curY)

            // 선택 영역 바깥 어두운 오버레이 (4분할)
            canvas.drawRect(0f, 0f, vw, top, dimPaint)
            canvas.drawRect(0f, bottom, vw, vh, dimPaint)
            canvas.drawRect(0f, top, left, bottom, dimPaint)
            canvas.drawRect(right, top, vw, bottom, dimPaint)

            // 선택 테두리 (점선)
            canvas.drawRect(left, top, right, bottom, borderPaint)

            // 모서리 핸들
            val h = 10f * density
            for ((cx, cy) in listOf(left to top, right to top, left to bottom, right to bottom)) {
                canvas.drawRect(cx - h, cy - h, cx + h, cy + h, cornerPaint)
            }

            // 크기 표시 칩 (선택 영역 중앙)
            val selW = (right - left).toInt().coerceAtMost(MAX_PX)
            val selH = (bottom - top).toInt().coerceAtMost(MAX_PX)
            val sizeText = "${selW}×${selH}px"
            sizePaint.textSize = 14f * density
            val chipW = sizePaint.measureText(sizeText) + 24f * density
            val chipH = 28f * density
            val cx = (left + right) / 2f
            val cy = (top + bottom) / 2f
            canvas.drawRoundRect(
                RectF(cx - chipW / 2, cy - chipH / 2, cx + chipW / 2, cy + chipH / 2),
                8f * density, 8f * density, chipPaint
            )
            canvas.drawText(sizeText, cx, cy + 5f * density, sizePaint)
        }
    }

    // ── 일반 탭 피커 모드 ─────────────────────────────────────────────────

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
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        banner.addView(Button(this).apply {
            text = "취소"
            setOnClickListener { cancelTap(target) }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).also { it.gravity = Gravity.CENTER_HORIZONTAL; it.topMargin = dp(8) })

        rootView.addView(touchLayer, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))
        rootView.addView(banner, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP
        ))

        wm.addView(rootView, overlayParams())
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

    // ── 공통 ──────────────────────────────────────────────────────────────

    private fun overlayParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    )

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

    private fun dp(value: Float): Float =
        value * resources.displayMetrics.density

    override fun onDestroy() {
        super.onDestroy()
        dismiss()
    }
}
