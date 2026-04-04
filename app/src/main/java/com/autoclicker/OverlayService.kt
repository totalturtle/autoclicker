package com.autoclicker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.*
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * 항상 화면 위에 떠 있는 플로팅 컨트롤 패널 + 드래그 가능한 포인트 마커.
 *
 * 동작:
 * - ⚙  → 메인 앱 열기
 * - +  → 화면 중앙에 새 포인트 마커 생성 (SequencePrefs 저장)
 * - -  → 마지막 포인트 마커 삭제
 * - 마커 드래그 → 포인트 좌표 이동 저장
 * - 마커 탭     → PointSettingsActivity 실행
 * - ▶/■         → 자동 클릭 시작/정지
 */
class OverlayService : Service() {

    companion object {
        const val EXTRA_SEQUENCE_JSON = "extra_sequence_json"
        private const val CHANNEL_ID  = "overlay_channel"
        private const val NOTIF_ID    = 1001
    }

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView:   View
    private lateinit var panelParams:   WindowManager.LayoutParams

    private var isRunning    = false
    private var sequenceJson: String? = null

    /** 드래그 가능한 마커 목록 */
    private data class MarkerEntry(val view: View, val params: WindowManager.LayoutParams)
    private val markers = mutableListOf<MarkerEntry>()

    private val markerSizePx by lazy { dp(56) }

    // ── 브로드캐스트 리시버 ─────────────────────────────────────────────

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                AutoClickAccessibilityService.ACTION_CLICK_COUNT -> {
                    val count = intent.getIntExtra(AutoClickAccessibilityService.EXTRA_COUNT, 0)
                    overlayView.findViewById<TextView>(R.id.tvOverlayStatus)?.text = count.toString()
                }
                AutoClickAccessibilityService.ACTION_STOP -> setRunningState(false)

                AutoClickAccessibilityService.ACTION_AUTO_PROFILE -> {
                    sequenceJson = SequencePrefs.load(this@OverlayService)?.toJsonString() ?: sequenceJson
                    refreshMarkers()
                }

                PointSettingsActivity.ACTION_POINT_UPDATED -> {
                    sequenceJson = SequencePrefs.load(this@OverlayService)?.toJsonString()
                    refreshMarkers()
                }
            }
        }
    }

    // ── 라이프사이클 ────────────────────────────────────────────────────

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        startForegroundWithNotification()
        setupPanel()
        loadMarkersFromPrefs()

        val filter = IntentFilter().apply {
            addAction(AutoClickAccessibilityService.ACTION_CLICK_COUNT)
            addAction(AutoClickAccessibilityService.ACTION_STOP)
            addAction(AutoClickAccessibilityService.ACTION_AUTO_PROFILE)
            addAction(PointSettingsActivity.ACTION_POINT_UPDATED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        else
            registerReceiver(receiver, filter)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getStringExtra(EXTRA_SEQUENCE_JSON)?.let {
            sequenceJson = it
            refreshMarkers()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { unregisterReceiver(receiver) }
        clearMarkers()
        runCatching { windowManager.removeView(overlayView) }
    }

    // ── 패널 설정 ───────────────────────────────────────────────────────

    private fun setupPanel() {
        val themedCtx = android.view.ContextThemeWrapper(this, R.style.Theme_AutoClicker)
        overlayView = LayoutInflater.from(themedCtx).inflate(R.layout.overlay_control, null)

        panelParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 16; y = 200
        }

        windowManager.addView(overlayView, panelParams)
        bindPanelButtons()
        setupPanelDrag()
    }

    private fun bindPanelButtons() {
        overlayView.findViewById<ImageButton>(R.id.btnOverlaySettings).setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_NEW_TASK
            })
        }

        overlayView.findViewById<ImageButton>(R.id.btnOverlayAddPoint).setOnClickListener {
            addNewPoint()
        }

        overlayView.findViewById<ImageButton>(R.id.btnOverlayRemovePoint).setOnClickListener {
            removeLastPoint()
        }

        overlayView.findViewById<Button>(R.id.btnOverlayToggle).setOnClickListener {
            if (isRunning) {
                sendBroadcast(Intent(AutoClickAccessibilityService.ACTION_STOP).apply { setPackage(packageName) })
                setRunningState(false)
            } else {
                val json = sequenceJson ?: SequencePrefs.load(this)?.toJsonString() ?: return@setOnClickListener
                sendBroadcast(Intent(AutoClickAccessibilityService.ACTION_START).apply {
                    setPackage(packageName)
                    putExtra(AutoClickAccessibilityService.EXTRA_SEQUENCE_JSON, json)
                })
                setRunningState(true)
            }
        }
    }

    private fun setRunningState(running: Boolean) {
        isRunning = running
        overlayView.findViewById<Button>(R.id.btnOverlayToggle)?.apply {
            text = if (running) "■" else "▶"
            setTextColor(if (running) 0xFFF85149.toInt() else 0xFF3FB950.toInt())
        }
        if (!running) overlayView.findViewById<TextView>(R.id.tvOverlayStatus)?.text = "0"
    }

    private fun setupPanelDrag() {
        val handle = overlayView.findViewById<View>(R.id.overlayDragHandle)
        var startX = 0f; var startY = 0f; var initX = 0; var initY = 0

        handle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX; startY = event.rawY
                    initX = panelParams.x; initY = panelParams.y; true
                }
                MotionEvent.ACTION_MOVE -> {
                    panelParams.x = initX + (event.rawX - startX).toInt()
                    panelParams.y = initY + (event.rawY - startY).toInt()
                    runCatching { windowManager.updateViewLayout(overlayView, panelParams) }; true
                }
                else -> false
            }
        }
    }

    // ── 마커 관리 ───────────────────────────────────────────────────────

    private fun loadMarkersFromPrefs() {
        val cfg = SequencePrefs.load(this) ?: return
        sequenceJson = cfg.toJsonString()
        clearMarkers()
        cfg.points.forEachIndexed { idx, pt -> addMarkerView(idx, pt.x, pt.y) }
    }

    private fun refreshMarkers() {
        val cfg = SequencePrefs.load(this)
        sequenceJson = cfg?.toJsonString()
        clearMarkers()
        cfg?.points?.forEachIndexed { idx, pt -> addMarkerView(idx, pt.x, pt.y) }
    }

    private fun addNewPoint() {
        val cfg = SequencePrefs.load(this) ?: ClickSequenceConfig(emptyList(), 1000L, 0)
        if (cfg.points.size >= 50) return

        val dm = resources.displayMetrics
        val cx = dm.widthPixels / 2
        val cy = dm.heightPixels / 3  // 중앙보다 위쪽에 배치

        val newPoint = ClickPoint(x = cx, y = cy)
        val updated  = cfg.copy(points = cfg.points + newPoint)
        SequencePrefs.save(this, updated)
        sequenceJson = updated.toJsonString()

        addMarkerView(updated.points.size - 1, cx, cy)
    }

    private fun removeLastPoint() {
        val cfg = SequencePrefs.load(this) ?: return
        if (cfg.points.isEmpty()) return

        val updated = cfg.copy(points = cfg.points.dropLast(1))
        SequencePrefs.save(this, updated)
        sequenceJson = updated.toJsonString()

        markers.lastOrNull()?.let {
            runCatching { windowManager.removeView(it.view) }
            markers.removeLast()
        }
    }

    private fun addMarkerView(index: Int, x: Int, y: Int) {
        val view = MarkerDotView(this, index + 1)

        val params = WindowManager.LayoutParams(
            markerSizePx, markerSizePx,
            x - markerSizePx / 2,
            y - markerSizePx / 2,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }

        runCatching { windowManager.addView(view, params) }
        markers.add(MarkerEntry(view, params))

        setupMarkerTouch(view, params, index)
    }

    private fun setupMarkerTouch(view: View, params: WindowManager.LayoutParams, markerIdx: Int) {
        var startRawX = 0f; var startRawY = 0f
        var initParamX = 0; var initParamY = 0
        val tapThresh = dp(8)

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startRawX = event.rawX; startRawY = event.rawY
                    initParamX = params.x; initParamY = params.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initParamX + (event.rawX - startRawX).toInt()
                    params.y = initParamY + (event.rawY - startRawY).toInt()
                    runCatching { windowManager.updateViewLayout(view, params) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val dx = Math.abs(event.rawX - startRawX)
                    val dy = Math.abs(event.rawY - startRawY)
                    if (dx < tapThresh && dy < tapThresh) {
                        // 탭 → 설정 열기
                        openPointSettings(markerIdx)
                    } else {
                        // 드래그 종료 → 좌표 저장
                        val newX = params.x + markerSizePx / 2
                        val newY = params.y + markerSizePx / 2
                        saveMarkerPosition(markerIdx, newX, newY)
                    }
                    true
                }
                else -> true
            }
        }
    }

    private fun openPointSettings(index: Int) {
        startActivity(Intent(this, PointSettingsActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra(PointSettingsActivity.EXTRA_POINT_INDEX, index)
        })
    }

    private fun saveMarkerPosition(index: Int, x: Int, y: Int) {
        val cfg = SequencePrefs.load(this) ?: return
        if (index >= cfg.points.size) return
        val pts = cfg.points.toMutableList()
        pts[index] = pts[index].copy(x = x, y = y)
        val updated = cfg.copy(points = pts)
        SequencePrefs.save(this, updated)
        sequenceJson = updated.toJsonString()
    }

    private fun clearMarkers() {
        markers.forEach { runCatching { windowManager.removeView(it.view) } }
        markers.clear()
    }

    // ── 헬퍼 ────────────────────────────────────────────────────────────

    private fun overlayType() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun startForegroundWithNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "AutoClicker 실행 중", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notif: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AutoClicker")
            .setContentText("플로팅 패널로 포인트를 설정하세요.")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        ServiceCompat.startForeground(
            this, NOTIF_ID, notif,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        )
    }
}

// ── 마커 커스텀 뷰 ──────────────────────────────────────────────────────

class MarkerDotView(context: Context, private val number: Int) : View(context) {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x806366F1.toInt()
        style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCC6366F1.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCCFFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 2f
        strokeCap = Paint.Cap.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r  = minOf(cx, cy) * 0.80f

        canvas.drawCircle(cx, cy, r, fillPaint)
        canvas.drawCircle(cx, cy, r, strokePaint)

        // 십자선
        val arm = r * 0.55f
        canvas.drawLine(cx - arm, cy, cx + arm, cy, crossPaint)
        canvas.drawLine(cx, cy - arm, cx, cy + arm, crossPaint)

        // 번호
        textPaint.textSize = height * 0.34f
        canvas.drawText(number.toString(), cx, cy + textPaint.textSize * 0.36f, textPaint)
    }
}
