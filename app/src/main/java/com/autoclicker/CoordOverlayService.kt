package com.autoclicker

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.*
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager

/**
 * 포인트 좌표를 화면에 시각적으로 표시하는 오버레이 서비스.
 * - 각 포인트: 반투명 원 + 십자선 + 순번 숫자
 * - 스와이프 끝 좌표: 점선 원 + 화살표 표시
 * - 우상단 떠 있는 토글 버튼으로 숨기기/보이기
 */
class CoordOverlayService : Service() {

    companion object {
        const val ACTION_UPDATE  = "com.autoclicker.COORD_OVERLAY_UPDATE"
        const val ACTION_TOGGLE  = "com.autoclicker.COORD_OVERLAY_TOGGLE"
        const val EXTRA_POINTS_JSON = "points_json"
    }

    private lateinit var wm: WindowManager
    private val markerViews = mutableListOf<View>()
    private var toggleBtn: MarkerToggleView? = null
    private var visible = true

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                ACTION_UPDATE -> {
                    val json = intent.getStringExtra(EXTRA_POINTS_JSON) ?: return
                    updateMarkers(ClickSequenceConfig.fromJsonString(json)?.points ?: emptyList())
                }
                ACTION_TOGGLE -> flipVisibility()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val filter = IntentFilter().apply {
            addAction(ACTION_UPDATE)
            addAction(ACTION_TOGGLE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
        if (Settings.canDrawOverlays(this)) showToggleButton()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Settings.canDrawOverlays(this)) return START_NOT_STICKY
        val json = intent?.getStringExtra(EXTRA_POINTS_JSON)
        if (json != null) {
            updateMarkers(ClickSequenceConfig.fromJsonString(json)?.points ?: emptyList())
        }
        return START_STICKY
    }

    // ── 마커 ────────────────────────────────────────────────────────────

    private fun updateMarkers(points: List<ClickPoint>) {
        clearMarkers()
        val size = dp(56)
        val half = size / 2

        points.forEachIndexed { idx, pt ->
            addMarker(PointMarkerView(this, idx + 1, isEnd = false, gesture = pt.gesture),
                size, pt.x - half, pt.y - half)

            if (pt.gesture == GestureType.SWIPE && (pt.endX != pt.x || pt.endY != pt.y)) {
                addMarker(PointMarkerView(this, idx + 1, isEnd = true, gesture = pt.gesture),
                    size, pt.endX - half, pt.endY - half)
            }
        }
        applyVisibility()
    }

    private fun addMarker(view: View, size: Int, x: Int, y: Int) {
        val params = markerParams(size, size, x, y)
        runCatching { wm.addView(view, params) }
        markerViews.add(view)
    }

    private fun clearMarkers() {
        markerViews.forEach { runCatching { wm.removeView(it) } }
        markerViews.clear()
    }

    private fun applyVisibility() {
        val v = if (visible) View.VISIBLE else View.INVISIBLE
        markerViews.forEach { it.visibility = v }
        toggleBtn?.setMarkersVisible(visible)
    }

    private fun flipVisibility() {
        visible = !visible
        applyVisibility()
    }

    // ── 토글 버튼 ───────────────────────────────────────────────────────

    private fun showToggleButton() {
        val btn = MarkerToggleView(this, onToggle = { flipVisibility() })
        val size = dp(40)
        val params = WindowManager.LayoutParams(
            size, size,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(12)
            y = dp(120)
        }
        runCatching { wm.addView(btn, params) }
        toggleBtn = btn

        // 드래그 이동 지원
        btn.setOnTouchListener(object : View.OnTouchListener {
            var initialX = 0; var initialY = 0
            var touchX = 0f; var touchY = 0f

            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x; initialY = params.y
                        touchX = e.rawX; touchY = e.rawY
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = initialX - (e.rawX - touchX).toInt()
                        params.y = initialY + (e.rawY - touchY).toInt()
                        runCatching { wm.updateViewLayout(btn, params) }
                    }
                    MotionEvent.ACTION_UP -> {
                        val dx = Math.abs(e.rawX - touchX)
                        val dy = Math.abs(e.rawY - touchY)
                        if (dx < dp(5) && dy < dp(5)) flipVisibility()
                    }
                }
                return true
            }
        })
    }

    // ── 헬퍼 ────────────────────────────────────────────────────────────

    private fun markerParams(w: Int, h: Int, x: Int, y: Int) =
        WindowManager.LayoutParams(
            w, h, x, y,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }

    private fun overlayType() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    override fun onDestroy() {
        super.onDestroy()
        runCatching { unregisterReceiver(receiver) }
        clearMarkers()
        toggleBtn?.let { runCatching { wm.removeView(it) } }
    }
}

// ── 포인트 마커 뷰 ──────────────────────────────────────────────────────

class PointMarkerView(
    context: Context,
    private val number: Int,
    private val isEnd: Boolean,
    private val gesture: GestureType
) : View(context) {

    // 색상: 인디고 계열
    private val fillColor   = if (isEnd) 0x306366F1.toInt() else 0x506366F1.toInt()
    private val strokeColor = if (isEnd) 0x886366F1.toInt() else 0xBB6366F1.toInt()

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = fillColor
        style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = strokeColor
        style = Paint.Style.STROKE
        strokeWidth = 3f
        if (isEnd) pathEffect = DashPathEffect(floatArrayOf(10f, 7f), 0f)
    }
    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCCFFFFFF.toInt()
        strokeWidth = 1.8f
        style = Paint.Style.STROKE
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val subTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCCFFFFFF.toInt()
        textAlign = Paint.Align.CENTER
        textSize = 20f
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r  = minOf(cx, cy) * 0.82f

        canvas.drawCircle(cx, cy, r, fillPaint)
        canvas.drawCircle(cx, cy, r, strokePaint)

        if (!isEnd) {
            // 십자선
            val arm = r * 0.65f
            canvas.drawLine(cx - arm, cy, cx + arm, cy, crossPaint)
            canvas.drawLine(cx, cy - arm, cx, cy + arm, crossPaint)

            // 순번 숫자
            textPaint.textSize = height * 0.36f
            val numStr = number.toString()
            canvas.drawText(numStr, cx, cy + textPaint.textSize * 0.36f, textPaint)
        } else {
            // 끝점: 화살표 + 번호
            subTextPaint.textSize = height * 0.22f
            textPaint.textSize = height * 0.28f
            canvas.drawText("→", cx, cy - textPaint.textSize * 0.1f, subTextPaint)
            canvas.drawText(number.toString(), cx, cy + textPaint.textSize * 0.9f, textPaint)
        }
    }
}

// ── 토글 버튼 뷰 ────────────────────────────────────────────────────────

class MarkerToggleView(context: Context, private val onToggle: () -> Unit) : View(context) {

    private var markersVisible = true

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xDD161B22.toInt()
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x886366F1.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCCE6EDF3.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 2f
        strokeCap = Paint.Cap.ROUND
    }
    private val slashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCCF85149.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        strokeCap = Paint.Cap.ROUND
    }

    fun setMarkersVisible(v: Boolean) {
        markersVisible = v
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r  = minOf(cx, cy) - 2f

        // 배경 원
        canvas.drawCircle(cx, cy, r, bgPaint)
        canvas.drawCircle(cx, cy, r, borderPaint)

        // 눈 아이콘
        val ew = r * 0.7f  // eye half-width
        val eh = r * 0.38f // eye half-height

        if (markersVisible) {
            // 눈 윤곽 (위아래 호)
            val eyePath = Path().apply {
                moveTo(cx - ew, cy)
                quadTo(cx, cy - eh * 2f, cx + ew, cy)
                quadTo(cx, cy + eh * 2f, cx - ew, cy)
            }
            canvas.drawPath(eyePath, iconPaint)
            // 동공
            iconPaint.style = Paint.Style.FILL_AND_STROKE
            canvas.drawCircle(cx, cy, r * 0.22f, iconPaint)
            iconPaint.style = Paint.Style.STROKE
        } else {
            // 눈 윤곽 (흐리게)
            iconPaint.alpha = 80
            val eyePath = Path().apply {
                moveTo(cx - ew, cy)
                quadTo(cx, cy - eh * 2f, cx + ew, cy)
                quadTo(cx, cy + eh * 2f, cx - ew, cy)
            }
            canvas.drawPath(eyePath, iconPaint)
            iconPaint.alpha = 200
            // 사선 (숨김 표시)
            canvas.drawLine(cx - ew * 0.7f, cy + eh * 1.4f, cx + ew * 0.7f, cy - eh * 1.4f, slashPaint)
        }
    }
}
