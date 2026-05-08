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
import android.os.SystemClock
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.view.isVisible
import com.autoclicker.databinding.DialogAddPointBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder

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
        const val ACTION_TOGGLE_MARKERS = "com.autoclicker.OVERLAY_TOGGLE_MARKERS"
        const val ACTION_AUTO_SHOW = "com.autoclicker.OVERLAY_AUTO_SHOW"
        private const val CHANNEL_ID  = "overlay_channel"
        private const val NOTIF_ID    = 1001
    }

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView:   View
    private lateinit var panelParams:   WindowManager.LayoutParams

    private var isRunning             = false
    private var lastToggleMs          = 0L
    private var isEditMode            = false
    private var isDialogShowing       = false
    private var isPanelCollapsed      = false
    private var pendingColorPickIndex = -1
    private var pendingRegionPickIndex = -1
    private var sequenceJson: String? = null
    private var markersVisible        = true

    // 영역 캡처 대기 중 다이얼로그 상태 임시 저장
    private var pendingRegionX = 0
    private var pendingRegionY = 0
    private var pendingRegionW = 0
    private var pendingRegionH = 0
    private var pendingRegionThresholdPct = 90
    private var pendingRegionActionPos = 0
    private var pendingRegionTolerance = 20
    private var pendingRegionMaxRetries = 5
    private var pendingRegionRetryDelayMs = 500L

    /** 드래그 가능한 마커 목록 */
    private data class MarkerEntry(val view: View, val params: WindowManager.LayoutParams)
    private val markers = mutableListOf<MarkerEntry>()

    /** 스와이프 경유점 마커 목록 (waypointIndex: 0=1-1, 1=1-2, ...) */
    private data class SwipeEndEntry(val view: View, val params: WindowManager.LayoutParams, val pointIndex: Int, val waypointIndex: Int)
    private val swipeEndMarkers = mutableListOf<SwipeEndEntry>()

    private val markerSizePx by lazy { dp(56) }

    // ── 브로드캐스트 리시버 ─────────────────────────────────────────────

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                AutoClickAccessibilityService.ACTION_CLICK_COUNT -> {
                    val count = intent.getIntExtra(AutoClickAccessibilityService.EXTRA_COUNT, 0)
                    overlayView.findViewById<TextView>(R.id.tvOverlayStatus)?.text = count.toString()
                }
                AutoClickAccessibilityService.ACTION_STARTED -> setRunningState(true)
                AutoClickAccessibilityService.ACTION_STOP -> setRunningState(false)
                AutoClickAccessibilityService.ACTION_AUTO_PROFILE -> {
                    sequenceJson = SequencePrefs.load(this@OverlayService)?.toJsonString() ?: sequenceJson
                    refreshMarkers()
                }
                PointSettingsActivity.ACTION_POINT_UPDATED -> refreshMarkers()
                SequencePrefs.ACTION_POINTS_CHANGED -> refreshMarkers()
                ACTION_TOGGLE_MARKERS -> setMarkersVisible(!markersVisible)
                AutoClickAccessibilityService.ACTION_COLOR_SAMPLED -> {
                    val target = intent.getStringExtra(AutoClickAccessibilityService.EXTRA_SAMPLE_TARGET) ?: return
                    if (target != CoordPickerService.TARGET_OVERLAY_COLOR) return
                    val x = intent.getIntExtra(AutoClickAccessibilityService.EXTRA_SAMPLE_X, 0)
                    val y = intent.getIntExtra(AutoClickAccessibilityService.EXTRA_SAMPLE_Y, 0)
                    val color = intent.getIntExtra(AutoClickAccessibilityService.EXTRA_SAMPLED_COLOR, Int.MIN_VALUE)
                    val move  = intent.getBooleanExtra(AutoClickAccessibilityService.EXTRA_MOVE_TO_DROPPER, false)
                    val idx = pendingColorPickIndex
                    pendingColorPickIndex = -1
                    // color == Int.MIN_VALUE 는 취소 신호 — 다이얼로그 재오픈 없이 인덱스만 초기화
                    if (idx >= 0 && color != Int.MIN_VALUE) openPointSettings(
                        idx, pickedColor = color,
                        triggerCheckX = x,
                        triggerCheckY = y,
                        dropperX = if (move) x else null,
                        dropperY = if (move) y else null
                    )
                }
                AutoClickAccessibilityService.ACTION_REGION_CAPTURED -> {
                    val target = intent.getStringExtra(AutoClickAccessibilityService.EXTRA_REGION_TARGET) ?: return
                    if (target != "overlay_region") return
                    val pixels = intent.getIntArrayExtra(AutoClickAccessibilityService.EXTRA_REGION_PIXELS) ?: return
                    val idx = pendingRegionPickIndex
                    pendingRegionPickIndex = -1
                    if (idx >= 0 && pixels.isNotEmpty()) {
                        pendingRegionX = intent.getIntExtra(AutoClickAccessibilityService.EXTRA_REGION_X, 0)
                        pendingRegionY = intent.getIntExtra(AutoClickAccessibilityService.EXTRA_REGION_Y, 0)
                        pendingRegionW = intent.getIntExtra(AutoClickAccessibilityService.EXTRA_REGION_W, 0)
                        pendingRegionH = intent.getIntExtra(AutoClickAccessibilityService.EXTRA_REGION_H, 0)
                        openPointSettings(idx, capturedRegionPixels = pixels)
                    }
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
            addAction(AutoClickAccessibilityService.ACTION_STARTED)
            addAction(AutoClickAccessibilityService.ACTION_STOP)
            addAction(AutoClickAccessibilityService.ACTION_AUTO_PROFILE)
            addAction(AutoClickAccessibilityService.ACTION_COLOR_SAMPLED)
            addAction(AutoClickAccessibilityService.ACTION_REGION_CAPTURED)
            addAction(PointSettingsActivity.ACTION_POINT_UPDATED)
            addAction(SequencePrefs.ACTION_POINTS_CHANGED)
            addAction(ACTION_TOGGLE_MARKERS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        else
            registerReceiver(receiver, filter)

        // 기본 상태: 패널 최소화 + 마커 숨김
        setPanelCollapsed(true)
        setMarkersVisible(false)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        refreshMarkers()
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
            val dm = resources.displayMetrics
            x = dm.widthPixels - (52 * dm.density).toInt() - (12 * dm.density).toInt()
            y = dm.heightPixels / 2 - (150 * dm.density).toInt()
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

        overlayView.findViewById<Button>(R.id.btnOverlaySaveProfile).setOnClickListener {
            showOverlaySaveProfileDialog()
        }

        overlayView.findViewById<Button>(R.id.btnOverlayLoadProfile).setOnClickListener {
            showOverlayLoadProfileDialog()
        }

        overlayView.findViewById<Button>(R.id.btnOverlayEditMode).setOnClickListener {
            setEditMode(!isEditMode)
        }

        overlayView.findViewById<ImageButton>(R.id.btnOverlayAddPoint).setOnClickListener {
            addNewPoint()
        }

        overlayView.findViewById<ImageButton>(R.id.btnOverlayRemovePoint).setOnClickListener {
            removeLastPoint()
        }

        overlayView.findViewById<Button>(R.id.btnOverlayToggle).setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastToggleMs >= 400L) {
                        lastToggleMs = now
                        v.isPressed = true
                        if (isRunning) {
                            setRunningState(false)
                            AutoClickAccessibilityService.instance?.requestStop()
                                ?: sendBroadcast(Intent(AutoClickAccessibilityService.ACTION_STOP).apply { setPackage(packageName) })
                        } else {
                            val cfg = SequencePrefs.load(this)
                            if (cfg != null && cfg.points.isNotEmpty()) {
                                sendBroadcast(Intent(AutoClickAccessibilityService.ACTION_START).apply {
                                    setPackage(packageName)
                                })
                            }
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    true
                }
                else -> false
            }
        }

        overlayView.findViewById<ImageButton>(R.id.btnOverlayToggleMarkers).setOnClickListener {
            setMarkersVisible(!markersVisible)
        }

        overlayView.findViewById<Button>(R.id.btnOverlayReset).setOnClickListener {
            if (isRunning) {
                sendBroadcast(Intent(AutoClickAccessibilityService.ACTION_STOP).apply { setPackage(packageName) })
            }
            val empty = SequencePrefs.load(this)?.copy(points = emptyList())
                ?: ClickSequenceConfig(emptyList(), 1000L, 0)
            SequencePrefs.save(this, empty)
            sequenceJson = empty.toJsonString()
            clearMarkers()
        }

        overlayView.findViewById<ImageButton>(R.id.btnOverlayPower).setOnClickListener {
            if (isRunning) {
                sendBroadcast(Intent(AutoClickAccessibilityService.ACTION_STOP).apply { setPackage(packageName) })
            }
            stopSelf()
        }
    }

    private fun setEditMode(edit: Boolean) {
        isEditMode = edit
        val btn = overlayView.findViewById<Button>(R.id.btnOverlayEditMode)
        val addBtn = overlayView.findViewById<ImageButton>(R.id.btnOverlayAddPoint)
        val removeBtn = overlayView.findViewById<ImageButton>(R.id.btnOverlayRemovePoint)
        if (edit) {
            btn.text = "편집중"
            btn.setTextColor(0xFF6366F1.toInt())
            btn.setBackgroundResource(R.drawable.bg_edit_mode_on)
            addBtn.isVisible = true
            removeBtn.isVisible = true
        } else {
            btn.text = "편집"
            btn.setTextColor(0xFF8B949E.toInt())
            btn.setBackgroundResource(R.drawable.bg_edit_mode_off)
            addBtn.isVisible = false
            removeBtn.isVisible = false
        }
        // 모든 마커의 터치 통과 여부 업데이트
        markers.forEach { updateMarkerTouchable(it) }
        swipeEndMarkers.forEach { updateSwipeEndTouchable(it) }
    }

    private fun updateSwipeEndTouchable(entry: SwipeEndEntry) {
        val flag = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        if (isEditMode) entry.params.flags = entry.params.flags and flag.inv()
        else entry.params.flags = entry.params.flags or flag
        runCatching { windowManager.updateViewLayout(entry.view, entry.params) }
    }

    private fun updateMarkerTouchable(entry: MarkerEntry) {
        val flag = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        if (isEditMode) {
            entry.params.flags = entry.params.flags and flag.inv()
        } else {
            entry.params.flags = entry.params.flags or flag
        }
        runCatching { windowManager.updateViewLayout(entry.view, entry.params) }
    }

    private fun setRunningState(running: Boolean) {
        isRunning = running
        overlayView.findViewById<Button>(R.id.btnOverlayToggle)?.apply {
            text = if (running) "■" else "▶"
            setTextColor(if (running) 0xFFF85149.toInt() else 0xFF3FB950.toInt())
        }
        if (!running) overlayView.findViewById<TextView>(R.id.tvOverlayStatus)?.text = "0"
        // 실행 중엔 마커 숨김, 정지 시 사용자 설정 상태로 복원
        val v = if (markersVisible && !running) View.VISIBLE else View.INVISIBLE
        markers.forEach { it.view.visibility = v }
        swipeEndMarkers.forEach { it.view.visibility = v }
    }

    private fun setMarkersVisible(visible: Boolean) {
        markersVisible = visible
        val v = if (markersVisible && !isRunning) View.VISIBLE else View.INVISIBLE
        markers.forEach { it.view.visibility = v }
        swipeEndMarkers.forEach { it.view.visibility = v }
        overlayView.findViewById<ImageButton>(R.id.btnOverlayToggleMarkers)?.alpha =
            if (markersVisible) 1f else 0.4f
    }

    private fun setupPanelDrag() {
        val handle = overlayView.findViewById<View>(R.id.overlayDragHandle)
        var startX = 0f; var startY = 0f; var initX = 0; var initY = 0
        var wasDrag = false
        val tapSlop = (resources.displayMetrics.density * 6).toInt()

        handle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX; startY = event.rawY
                    initX = panelParams.x; initY = panelParams.y
                    wasDrag = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - startX).toInt()
                    val dy = (event.rawY - startY).toInt()
                    if (!wasDrag && (Math.abs(dx) > tapSlop || Math.abs(dy) > tapSlop)) wasDrag = true
                    if (wasDrag) {
                        panelParams.x = initX + dx
                        panelParams.y = initY + dy
                        runCatching { windowManager.updateViewLayout(overlayView, panelParams) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!wasDrag) setPanelCollapsed(!isPanelCollapsed)
                    true
                }
                else -> false
            }
        }
    }

    private fun setPanelCollapsed(collapsed: Boolean) {
        isPanelCollapsed = collapsed
        val topGroup    = overlayView.findViewById<View>(R.id.layoutCollapsibleTop)
        val bottomGroup = overlayView.findViewById<View>(R.id.layoutCollapsibleBottom)
        val handle      = overlayView.findViewById<TextView>(R.id.overlayDragHandle)
        topGroup.visibility    = if (collapsed) View.GONE else View.VISIBLE
        bottomGroup.visibility = if (collapsed) View.GONE else View.VISIBLE
        handle.text = if (collapsed) "▷" else "· · ·"
    }

    // ── 마커 관리 ───────────────────────────────────────────────────────

    private fun loadMarkersFromPrefs() {
        sequenceJson = SequencePrefs.loadRawJson(this)
        val cfg = ClickSequenceConfig.fromJsonString(sequenceJson) ?: return
        clearMarkers()
        cfg.points.forEachIndexed { idx, pt ->
            addMarkerView(idx, pt.x, pt.y)
            if (pt.gesture == GestureType.SWIPE) {
                addSwipeEndMarkerView(idx, pt.endX, pt.endY, 0)
                pt.swipeExtraPoints.forEachIndexed { wpIdx, wp ->
                    addSwipeEndMarkerView(idx, wp.x, wp.y, wpIdx + 1)
                }
            }
        }
    }

    private fun refreshMarkers() {
        sequenceJson = SequencePrefs.loadRawJson(this)
        val cfg = ClickSequenceConfig.fromJsonString(sequenceJson)
        clearMarkers()
        cfg?.points?.forEachIndexed { idx, pt ->
            addMarkerView(idx, pt.x, pt.y)
            if (pt.gesture == GestureType.SWIPE) {
                addSwipeEndMarkerView(idx, pt.endX, pt.endY, 0)
                pt.swipeExtraPoints.forEachIndexed { wpIdx, wp ->
                    addSwipeEndMarkerView(idx, wp.x, wp.y, wpIdx + 1)
                }
            }
        }
        if (!markersVisible || isRunning) {
            markers.forEach { it.view.visibility = View.INVISIBLE }
            swipeEndMarkers.forEach { it.view.visibility = View.INVISIBLE }
        }
    }

    private fun addNewPoint() {
        val cfg = SequencePrefs.load(this) ?: ClickSequenceConfig(emptyList(), 1000L, 0)
        if (!PremiumManager.isPremium && cfg.points.size >= PremiumManager.FREE_POINT_LIMIT) {
            // 오버레이에서는 MainActivity로 이동해서 구매 다이얼로그 표시
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("show_premium_dialog", true)
            }
            startActivity(intent)
            return
        }
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
        refreshMarkers()
    }

    private fun addMarkerView(index: Int, x: Int, y: Int) {
        val view = MarkerDotView(this, index + 1)

        // 기본: 편집 모드 아니면 터치 통과 (FLAG_NOT_TOUCHABLE)
        val touchFlag = if (isEditMode) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE

        val params = WindowManager.LayoutParams(
            markerSizePx, markerSizePx,
            x - markerSizePx / 2,
            y - markerSizePx / 2,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    touchFlag,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }

        runCatching { windowManager.addView(view, params) }
        val entry = MarkerEntry(view, params)
        markers.add(entry)

        setupMarkerTouch(view, params, index)
    }

    private fun addSwipeEndMarkerView(pointIndex: Int, x: Int, y: Int, waypointIndex: Int) {
        val view = SwipeEndMarkerView(this, pointIndex + 1, waypointIndex + 1)
        val touchFlag = if (isEditMode) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        val params = WindowManager.LayoutParams(
            markerSizePx, markerSizePx,
            x - markerSizePx / 2,
            y - markerSizePx / 2,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    touchFlag,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        runCatching { windowManager.addView(view, params) }
        val entry = SwipeEndEntry(view, params, pointIndex, waypointIndex)
        swipeEndMarkers.add(entry)
        setupSwipeEndMarkerTouch(view, params, pointIndex, waypointIndex)
    }

    private fun setupSwipeEndMarkerTouch(view: View, params: WindowManager.LayoutParams, pointIndex: Int, waypointIndex: Int) {
        var startRawX = 0f; var startRawY = 0f
        var initParamX = 0; var initParamY = 0
        val tapThresh = dp(8)

        view.setOnTouchListener { _, event ->
            if (!isEditMode) return@setOnTouchListener false
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
                        openPointSettings(pointIndex)
                    } else {
                        val newX = params.x + markerSizePx / 2
                        val newY = params.y + markerSizePx / 2
                        saveSwipeWaypointPosition(pointIndex, waypointIndex, newX, newY)
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun saveSwipeWaypointPosition(pointIndex: Int, waypointIndex: Int, x: Int, y: Int) {
        val cfg = SequencePrefs.load(this) ?: return
        if (pointIndex >= cfg.points.size) return
        val pts = cfg.points.toMutableList()
        val pt = pts[pointIndex]
        pts[pointIndex] = if (waypointIndex == 0) {
            pt.copy(endX = x, endY = y)
        } else {
            val extras = pt.swipeExtraPoints.toMutableList()
            val idx = waypointIndex - 1
            if (idx < extras.size) {
                extras[idx] = SwipeWaypoint(x, y)
                pt.copy(swipeExtraPoints = extras)
            } else pt
        }
        val updated = cfg.copy(points = pts)
        SequencePrefs.save(this, updated)
        sequenceJson = updated.toJsonString()
    }

    private fun setupMarkerTouch(view: View, params: WindowManager.LayoutParams, markerIdx: Int) {
        var startRawX = 0f; var startRawY = 0f
        var initParamX = 0; var initParamY = 0
        val tapThresh = dp(8)

        view.setOnTouchListener { _, event ->
            // 편집 모드가 아니면 터치 이벤트를 소비하지 않고 통과시킴
            if (!isEditMode) return@setOnTouchListener false

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
                        // 겹친 마커가 있을 때 가장 높은 인덱스(시각적으로 위에 있어야 할) 마커 선택
                        val tapX = event.rawX.toInt()
                        val tapY = event.rawY.toInt()
                        val half = markerSizePx / 2
                        val topIdx = markers.indexOfLast { entry ->
                            val cx = entry.params.x + half
                            val cy = entry.params.y + half
                            Math.abs(cx - tapX) <= half && Math.abs(cy - tapY) <= half
                        }
                        openPointSettings(if (topIdx >= 0) topIdx else markerIdx)
                    } else {
                        val newX = params.x + markerSizePx / 2
                        val newY = params.y + markerSizePx / 2
                        saveMarkerPosition(markerIdx, newX, newY)
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun openPointSettings(index: Int, pickedColor: Int? = null, triggerCheckX: Int? = null, triggerCheckY: Int? = null, dropperX: Int? = null, dropperY: Int? = null, capturedRegionPixels: IntArray? = null) {
        if (isDialogShowing) return  // 연쇄 오픈 방지
        val cfg = SequencePrefs.load(this) ?: return
        if (index >= cfg.points.size) return
        // 다이얼로그가 열린 동안 마커 터치 차단
        isDialogShowing = true
        markers.forEach { entry ->
            entry.params.flags = entry.params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            runCatching { windowManager.updateViewLayout(entry.view, entry.params) }
        }
        swipeEndMarkers.forEach { entry ->
            entry.params.flags = entry.params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            runCatching { windowManager.updateViewLayout(entry.view, entry.params) }
        }
        val point = cfg.points[index]

        val themedCtx = ContextThemeWrapper(this, R.style.Theme_AutoClicker)
        val d = DialogAddPointBinding.inflate(LayoutInflater.from(themedCtx))

        d.btnPickStart.visibility   = View.GONE
        d.btnPickEnd.visibility     = View.GONE
        d.btnPickTrigger.visibility = View.GONE

        // 순서 변경 필드
        d.tvPointOrderLabel.text = "순서 변경 (현재: ${index + 1} / 전체: ${cfg.points.size}개)"
        d.etPointOrder.setText((index + 1).toString())

        d.etDialogX.setText(point.x.toString())
        d.etDialogY.setText(point.y.toString())
        d.etDialogLabel.setText(point.label)
        if (point.delayAfterMs >= 0) d.etDialogDelayAfter.setText(point.delayAfterMs.toString())
        if (point.randomVarianceMs > 0) d.etDialogVariance.setText(point.randomVarianceMs.toString())
        if (point.coordinateVariancePx > 0) d.etDialogCoordVariance.setText(point.coordinateVariancePx.toString())
        d.etDialogPointRepeat.setText(point.pointRepeatCount.toString())

        // 반복 모드 UI 복원 (RadioGroup 버그 회피 — 개별 isChecked 직접 제어)
        if (point.pointRepeatMode == RepeatMode.DURATION) {
            d.rbPointRepeatTime.isChecked = true
            d.rbPointRepeatCount.isChecked = false
            d.layoutPointRepeatCount.visibility = View.GONE
            d.layoutPointRepeatDuration.visibility = View.VISIBLE
            val ms = point.pointRepeatDurationMs.coerceAtLeast(0L)
            val (dispVal, unitPos) = when {
                ms <= 0L -> Pair(1L, 0)
                ms % 3_600_000L == 0L -> Pair(ms / 3_600_000L, 2)
                ms % 60_000L == 0L    -> Pair(ms / 60_000L, 1)
                else                  -> Pair(ms / 1_000L, 0)
            }
            d.etDialogPointRepeatDuration.setText(dispVal.toString())
            when (unitPos) {
                1 -> { d.rbPointUnitMin.isChecked = true;  d.rbPointUnitSec.isChecked = false; d.rbPointUnitHour.isChecked = false }
                2 -> { d.rbPointUnitHour.isChecked = true; d.rbPointUnitSec.isChecked = false; d.rbPointUnitMin.isChecked  = false }
                else -> { d.rbPointUnitSec.isChecked = true; d.rbPointUnitMin.isChecked = false; d.rbPointUnitHour.isChecked = false }
            }
        } else {
            d.rbPointRepeatCount.isChecked = true
            d.rbPointRepeatTime.isChecked = false
            d.layoutPointRepeatCount.visibility = View.VISIBLE
            d.layoutPointRepeatDuration.visibility = View.GONE
        }
        d.rbPointRepeatTime.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                d.rbPointRepeatCount.isChecked = false
                d.layoutPointRepeatCount.visibility = View.GONE
                d.layoutPointRepeatDuration.visibility = View.VISIBLE
            }
        }
        d.rbPointRepeatCount.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                d.rbPointRepeatTime.isChecked = false
                d.layoutPointRepeatCount.visibility = View.VISIBLE
                d.layoutPointRepeatDuration.visibility = View.GONE
            }
        }
        d.rbPointUnitSec.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) { d.rbPointUnitMin.isChecked = false; d.rbPointUnitHour.isChecked = false }
        }
        d.rbPointUnitMin.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) { d.rbPointUnitSec.isChecked = false; d.rbPointUnitHour.isChecked = false }
        }
        d.rbPointUnitHour.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) { d.rbPointUnitSec.isChecked = false; d.rbPointUnitMin.isChecked = false }
        }

        val gesturePos = when (point.gesture) {
            GestureType.LONG_PRESS -> 1
            GestureType.SWIPE      -> 2
            else                   -> 0
        }
        fun applyGestureUi(pos: Int) {
            d.tilDialogTapDur.visibility  = if (pos == 0) View.VISIBLE else View.GONE
            d.groupSwipe.visibility       = if (pos == 2) View.VISIBLE else View.GONE
            d.tilDialogLongDur.visibility = if (pos == 1) View.VISIBLE else View.GONE
            if (pos == 2) {
                // 끝점은 오버레이 마커 드래그로 설정 — 좌표 입력 필드 숨김
                (d.etDialogEndX.parent as? View)?.visibility = View.GONE
                (d.etDialogEndY.parent as? View)?.visibility = View.GONE
            }
        }
        d.spinnerGesture.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, position: Int, id: Long) = applyGestureUi(position)
            override fun onNothingSelected(p: AdapterView<*>?) = Unit
        }
        d.spinnerGesture.setSelection(gesturePos)
        applyGestureUi(gesturePos)

        if (point.gesture == GestureType.TAP) {
            d.etDialogTapDur.setText(point.tapDurationMs.toString())
        }
        if (point.gesture == GestureType.SWIPE) {
            d.etDialogSwipeDur.setText(point.swipeDurationMs.toString())
            // 꺾임 개수 표시 (버튼 리스너는 dialog 생성 후 설정)
            d.tvSwipeWaypointCount.text = if (point.swipeExtraPoints.isEmpty()) "꺾임: 없음" else "꺾임: ${point.swipeExtraPoints.size}개"
            d.btnRemoveWaypoint.isEnabled = point.swipeExtraPoints.isNotEmpty()
            d.groupSwipeWaypoints.visibility = View.VISIBLE
        }
        if (point.gesture == GestureType.LONG_PRESS) {
            d.etDialogLongDur.setText(point.longPressDurationMs.toString())
        }

        // 트리거 모드 UI 토글 헬퍼
        fun applyTriggerModeUi(modePos: Int) {
            d.groupTriggerPixel.visibility  = if (modePos == 0) View.VISIBLE else View.GONE
            d.groupTriggerRegion.visibility = if (modePos == 1) View.VISIBLE else View.GONE
            d.groupTriggerCoords.visibility = if (modePos == 0) View.VISIBLE else View.GONE
        }

        // 현재 포인트의 트리거 값으로 채우기
        var regionPixelsForSave: IntArray? = null
        val trigger = point.trigger
        if (trigger != null) {
            d.cbTrigger.isChecked    = true
            d.groupTrigger.visibility = View.VISIBLE
            d.etTriggerX.setText(trigger.checkX.toString())
            d.etTriggerY.setText(trigger.checkY.toString())
            d.etTriggerTolerance.setText(trigger.tolerance.toString())
            d.etTriggerMaxRetries.setText(trigger.maxRetries.toString())
            d.etTriggerRetryDelay.setText(trigger.retryDelayMs.toString())
            val actPos = if (trigger.action == TriggerAction.WAIT_RETRY) 1 else 0
            d.spinnerTriggerAction.setSelection(actPos)
            d.groupTriggerRetry.visibility = if (actPos == 1) View.VISIBLE else View.GONE
            when (trigger.mode) {
                TriggerMode.REGION -> {
                    d.rgTriggerMode.check(R.id.rbModeRegion)
                    applyTriggerModeUi(1)
                    d.etRegionW.setText(trigger.regionW.toString())
                    d.etRegionH.setText(trigger.regionH.toString())
                    d.etRegionThreshold.setText((trigger.regionMatchThreshold * 100).toInt().toString())
                    if (trigger.regionPixels != null) {
                        regionPixelsForSave = trigger.regionPixels
                        d.tvRegionCaptureStatus.text = "캡처됨: (${trigger.checkX},${trigger.checkY}) ${trigger.regionW}×${trigger.regionH}px"
                    }
                }
                TriggerMode.PIXEL -> {
                    d.rgTriggerMode.check(R.id.rbModePixel)
                    applyTriggerModeUi(0)
                    d.etTriggerColor.setText("#%06X".format(trigger.targetColor and 0xFFFFFF))
                }
            }
        } else {
            applyTriggerModeUi(0)
        }

        // 색상 피커로 결과가 왔으면 색상·위치 적용
        if (pickedColor != null) {
            if (dropperX != null && dropperY != null) {
                d.etDialogX.setText(dropperX.toString())
                d.etDialogY.setText(dropperY.toString())
            }
            d.cbTrigger.isChecked     = true
            d.groupTrigger.visibility = View.VISIBLE
            d.rgTriggerMode.check(R.id.rbModePixel)
            applyTriggerModeUi(0)
            val colorHex = "#%06X".format(pickedColor and 0xFFFFFF)
            d.etTriggerColor.setText(colorHex)
            d.tvTriggerColorPreview.setBackgroundColor(pickedColor)
            d.etTriggerX.setText(triggerCheckX.toString())
            d.etTriggerY.setText(triggerCheckY.toString())
        }

        // 영역 캡처 결과가 왔으면 적용
        if (capturedRegionPixels != null) {
            regionPixelsForSave = capturedRegionPixels
            d.cbTrigger.isChecked     = true
            d.groupTrigger.visibility = View.VISIBLE
            d.rgTriggerMode.check(R.id.rbModeRegion)
            applyTriggerModeUi(1)
            d.etTriggerX.setText(pendingRegionX.toString())
            d.etTriggerY.setText(pendingRegionY.toString())
            d.etRegionW.setText(pendingRegionW.toString())
            d.etRegionH.setText(pendingRegionH.toString())
            d.etRegionThreshold.setText(pendingRegionThresholdPct.toString())
            d.spinnerTriggerAction.setSelection(pendingRegionActionPos)
            d.groupTriggerRetry.visibility = if (pendingRegionActionPos == 1) View.VISIBLE else View.GONE
            d.etTriggerTolerance.setText(pendingRegionTolerance.toString())
            d.etTriggerMaxRetries.setText(pendingRegionMaxRetries.toString())
            d.etTriggerRetryDelay.setText(pendingRegionRetryDelayMs.toString())
            d.tvRegionCaptureStatus.text = "캡처됨: (${pendingRegionX},${pendingRegionY}) ${pendingRegionW}×${pendingRegionH}px"
        }

        d.cbTrigger.setOnCheckedChangeListener { _, checked ->
            if (checked && !PremiumManager.isPremium) {
                d.cbTrigger.isChecked = false
                d.groupTrigger.visibility = View.GONE
                android.widget.Toast.makeText(this, "🔒 프리미엄 기능입니다. 설정 > 프리미엄 구매에서 이용하세요.", android.widget.Toast.LENGTH_SHORT).show()
                return@setOnCheckedChangeListener
            }
            d.groupTrigger.visibility = if (checked) View.VISIBLE else View.GONE
        }
        d.cbStopLoopOnExecute.isChecked = point.stopLoopOnExecute
        d.cbStopLoopOnExecute.setOnCheckedChangeListener { _, checked ->
            if (checked && !PremiumManager.isPremium) {
                d.cbStopLoopOnExecute.isChecked = false
                android.widget.Toast.makeText(this, "🔒 프리미엄 기능입니다. 설정 > 프리미엄 구매에서 이용하세요.", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        d.rgTriggerMode.setOnCheckedChangeListener { _, checkedId ->
            applyTriggerModeUi(if (checkedId == R.id.rbModeRegion) 1 else 0)
        }
        d.spinnerTriggerAction.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, position: Int, id: Long) {
                d.groupTriggerRetry.visibility = if (position == 1) View.VISIBLE else View.GONE
            }
            override fun onNothingSelected(p: AdapterView<*>?) = Unit
        }

        // 색상 HEX 입력 시 프리뷰 업데이트
        fun updateColorPreview(hex: String) {
            val c = TriggerCondition.parseColor(hex)
            if (c != null) d.tvTriggerColorPreview.setBackgroundColor(c)
        }
        d.etTriggerColor.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) { updateColorPreview(s?.toString() ?: "") }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        })
        updateColorPreview(d.etTriggerColor.text?.toString() ?: "#FF0000")

        val dialog = MaterialAlertDialogBuilder(themedCtx)
            .setTitle("포인트 #${index + 1} 설정")
            .setView(d.root)
            .setNeutralButton("삭제") { _, _ -> deletePointInOverlay(index, cfg) }
            .setNegativeButton("취소", null)
            .setPositiveButton("확인") { _, _ -> savePointInOverlay(index, d, point, cfg, regionPixelsForSave) }
            .create()

        // 색상 피커 버튼
        d.btnPickColor.setOnClickListener {
            pendingColorPickIndex = index
            dialog.dismiss()
            startService(Intent(this, CoordPickerService::class.java).apply {
                putExtra(CoordPickerService.EXTRA_PICK_TARGET, CoordPickerService.TARGET_OVERLAY_COLOR)
            })
        }

        // 영역 드래그 지정 버튼
        d.btnCaptureRegion.setOnClickListener {
            pendingRegionPickIndex    = index
            pendingRegionThresholdPct = d.etRegionThreshold.text?.toString()?.toIntOrNull()?.coerceIn(0, 100) ?: 90
            pendingRegionActionPos    = d.spinnerTriggerAction.selectedItemPosition
            pendingRegionTolerance    = d.etTriggerTolerance.text?.toString()?.toIntOrNull()?.coerceIn(0, 255) ?: 20
            pendingRegionMaxRetries   = d.etTriggerMaxRetries.text?.toString()?.toIntOrNull()?.coerceAtLeast(1) ?: 5
            pendingRegionRetryDelayMs = d.etTriggerRetryDelay.text?.toString()?.toLongOrNull()?.coerceAtLeast(100L) ?: 500L
            dialog.dismiss()
            startService(Intent(this, CoordPickerService::class.java).apply {
                putExtra(CoordPickerService.EXTRA_PICK_TARGET, CoordPickerService.TARGET_REGION_CAPTURE)
                putExtra(CoordPickerService.EXTRA_REGION_RESULT_TARGET, "overlay_region")
            })
        }

        // 꺾임 추가/삭제 버튼 (dialog 참조 필요하므로 생성 후 설정)
        fun updateWaypointUi(extras: List<SwipeWaypoint>) {
            d.tvSwipeWaypointCount.text = if (extras.isEmpty()) "꺾임: 없음" else "꺾임: ${extras.size}개"
            d.btnRemoveWaypoint.isEnabled = extras.isNotEmpty()
        }
        d.btnAddWaypoint.setOnClickListener {
            savePointInOverlay(index, d, point, cfg, regionPixelsForSave)
            val freshCfg = SequencePrefs.load(this) ?: return@setOnClickListener
            val freshPt = freshCfg.points.getOrNull(index) ?: return@setOnClickListener
            val lastX = freshPt.swipeExtraPoints.lastOrNull()?.x ?: freshPt.endX
            val lastY = freshPt.swipeExtraPoints.lastOrNull()?.y ?: freshPt.endY
            val newExtras = freshPt.swipeExtraPoints + SwipeWaypoint(lastX, lastY)
            val saved = freshCfg.copy(points = freshCfg.points.toMutableList().also {
                it[index] = freshPt.copy(swipeExtraPoints = newExtras)
            })
            SequencePrefs.save(this, saved)
            sequenceJson = saved.toJsonString()
            dialog.dismiss()
            refreshMarkers()
        }
        d.btnRemoveWaypoint.setOnClickListener {
            val freshCfg = SequencePrefs.load(this) ?: return@setOnClickListener
            val freshPt = freshCfg.points.getOrNull(index) ?: return@setOnClickListener
            if (freshPt.swipeExtraPoints.isEmpty()) return@setOnClickListener
            val newExtras = freshPt.swipeExtraPoints.dropLast(1)
            val saved = freshCfg.copy(points = freshCfg.points.toMutableList().also {
                it[index] = freshPt.copy(swipeExtraPoints = newExtras)
            })
            SequencePrefs.save(this, saved)
            sequenceJson = saved.toJsonString()
            updateWaypointUi(newExtras)
            refreshMarkers()
        }

        // 오버레이 윈도우 타입으로 띄워야 배경 앱 위에 표시됨
        dialog.window?.setType(overlayType())
        dialog.setOnDismissListener {
            isDialogShowing = false
            markers.forEach { updateMarkerTouchable(it) }
        }
        dialog.show()
    }

    private fun savePointInOverlay(index: Int, d: DialogAddPointBinding, original: ClickPoint, cfg: ClickSequenceConfig, regionPixels: IntArray? = null) {
        val gesturePos = d.spinnerGesture.selectedItemPosition
        val gesture = when (gesturePos) { 1 -> GestureType.LONG_PRESS; 2 -> GestureType.SWIPE; else -> GestureType.TAP }

        val x = d.etDialogX.text?.toString()?.toIntOrNull() ?: original.x
        val y = d.etDialogY.text?.toString()?.toIntOrNull() ?: original.y
        val label = d.etDialogLabel.text?.toString()?.trim().orEmpty()
        val delayAfter = d.etDialogDelayAfter.text?.toString()?.toLongOrNull() ?: -1L
        val variance = d.etDialogVariance.text?.toString()?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
        val coordVariance = d.etDialogCoordVariance.text?.toString()?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val pointRepeat = d.etDialogPointRepeat.text?.toString()?.toIntOrNull()?.coerceAtLeast(1) ?: 1

        // 반복 모드/시간 읽기 — 개별 isChecked 직접 확인
        val pointRepeatMode = if (d.rbPointRepeatTime.isChecked) RepeatMode.DURATION else RepeatMode.COUNT
        val pointRepeatDurationMs = if (pointRepeatMode == RepeatMode.DURATION) {
            val v = d.etDialogPointRepeatDuration.text?.toString()?.toLongOrNull()?.coerceAtLeast(1L) ?: 1L
            val mul = when {
                d.rbPointUnitHour.isChecked -> 3_600_000L
                d.rbPointUnitMin.isChecked  -> 60_000L
                else                        -> 1_000L
            }
            v * mul
        } else 0L

        var endX = x; var endY = y
        var tapMs = original.tapDurationMs
        var longMs = original.longPressDurationMs
        var swipeMs = original.swipeDurationMs

        when (gesture) {
            GestureType.TAP -> {
                tapMs = d.etDialogTapDur.text?.toString()?.toLongOrNull() ?: 50L
                if (tapMs < 1 || tapMs > 60_000) {
                    Toast.makeText(this, "탭 지속 시간은 1~60000ms 입니다.", Toast.LENGTH_SHORT).show(); return
                }
            }
            GestureType.SWIPE -> {
                endX  = d.etDialogEndX.text?.toString()?.toIntOrNull() ?: original.endX
                endY  = d.etDialogEndY.text?.toString()?.toIntOrNull() ?: original.endY
                swipeMs = d.etDialogSwipeDur.text?.toString()?.toLongOrNull() ?: 350L
                if (swipeMs < 50 || swipeMs > 60_000) {
                    Toast.makeText(this, "스와이프 지속 시간은 50~60000ms 입니다.", Toast.LENGTH_SHORT).show(); return
                }
            }
            GestureType.LONG_PRESS -> {
                longMs = d.etDialogLongDur.text?.toString()?.toLongOrNull() ?: 450L
                if (longMs < 100 || longMs > 60_000) {
                    Toast.makeText(this, "롱 프레스 시간은 100~60000ms 입니다.", Toast.LENGTH_SHORT).show(); return
                }
            }
            else -> Unit
        }

        val newTrigger = if (d.cbTrigger.isChecked && PremiumManager.isPremium) {
            val cx = d.etTriggerX.text?.toString()?.toIntOrNull()
            val cy = d.etTriggerY.text?.toString()?.toIntOrNull()
            if (cx == null || cy == null) original.trigger
            else {
                val tol    = d.etTriggerTolerance.text?.toString()?.toIntOrNull()?.coerceIn(0, 255) ?: 20
                val actPos = d.spinnerTriggerAction.selectedItemPosition
                val action = if (actPos == 1) TriggerAction.WAIT_RETRY else TriggerAction.SKIP
                val maxR   = d.etTriggerMaxRetries.text?.toString()?.toIntOrNull()?.coerceAtLeast(1) ?: 5
                val rDelay = d.etTriggerRetryDelay.text?.toString()?.toLongOrNull()?.coerceAtLeast(100L) ?: 500L
                val modePos = if (d.rbModeRegion.isChecked) 1 else 0
                if (modePos == 1) {
                    // 이미지 영역 모드
                    val rw  = d.etRegionW.text?.toString()?.toIntOrNull() ?: 0
                    val rh  = d.etRegionH.text?.toString()?.toIntOrNull() ?: 0
                    val thr = (d.etRegionThreshold.text?.toString()?.toIntOrNull() ?: 90).coerceIn(0, 100) / 100f
                    val px  = regionPixels ?: original.trigger?.takeIf { it.mode == TriggerMode.REGION }?.regionPixels
                    TriggerCondition(cx, cy, 0, tol, action, maxR, rDelay,
                        mode = TriggerMode.REGION, regionW = rw, regionH = rh,
                        regionPixels = px, regionMatchThreshold = thr)
                } else {
                    // 색상 픽셀 모드
                    val color = TriggerCondition.parseColor(d.etTriggerColor.text?.toString()?.trim() ?: "")
                    if (color == null) original.trigger
                    else TriggerCondition(cx, cy, color, tol, action, maxR, rDelay)
                }
            }
        } else null

        // swipeExtraPoints는 드래그/꺾임 추가·삭제로 prefs에 직접 저장되므로 항상 최신 prefs 값 사용
        val freshExtras = if (gesture == GestureType.SWIPE)
            SequencePrefs.load(this)?.points?.getOrNull(index)?.swipeExtraPoints ?: original.swipeExtraPoints
        else emptyList()
        val updated = original.copy(
            x = x, y = y, label = label, delayAfterMs = delayAfter,
            gesture = gesture, endX = endX, endY = endY,
            tapDurationMs = tapMs, longPressDurationMs = longMs, swipeDurationMs = swipeMs,
            trigger = newTrigger, randomVarianceMs = variance, coordinateVariancePx = coordVariance,
            pointRepeatCount = pointRepeat,
            pointRepeatMode = pointRepeatMode,
            pointRepeatDurationMs = pointRepeatDurationMs,
            swipeExtraPoints = freshExtras,
            stopLoopOnExecute = d.cbStopLoopOnExecute.isChecked && PremiumManager.isPremium
        )
        val inputText = d.etPointOrder.text?.toString()?.trim()
        val targetPos = (inputText?.toIntOrNull() ?: (index + 1))
            .coerceIn(1, cfg.points.size) - 1
        val newPoints = cfg.points.toMutableList().also { it[index] = updated }
        if (targetPos != index) {
            newPoints.removeAt(index)
            newPoints.add(targetPos, updated)
            Toast.makeText(this, "${index + 1}번 → ${targetPos + 1}번으로 이동", Toast.LENGTH_SHORT).show()
        }
        SequencePrefs.save(this, cfg.copy(points = newPoints))
        refreshMarkers()
    }

    private fun showOverlaySaveProfileDialog() {
        if (isDialogShowing) return
        val cfg = SequencePrefs.load(this)
        if (cfg == null || cfg.points.isEmpty()) {
            Toast.makeText(this, "저장할 포인트가 없습니다.", Toast.LENGTH_SHORT).show()
            return
        }
        if (!PremiumManager.isPremium && ProfileManager.loadAll(this).size >= 2) {
            Toast.makeText(this, "🔒 무료 버전은 프로필을 2개까지 저장할 수 있습니다. 설정 > 프리미엄 구매에서 이용하세요.", Toast.LENGTH_LONG).show()
            return
        }
        isDialogShowing = true
        val themedCtx = ContextThemeWrapper(this, R.style.Theme_AutoClicker)
        val input = android.widget.EditText(themedCtx).apply {
            hint = "프로필 이름"
            setSingleLine()
            setPadding(48, 32, 48, 16)
        }
        val dialog = MaterialAlertDialogBuilder(themedCtx)
            .setTitle("프로필 저장")
            .setView(input)
            .setNegativeButton("취소", null)
            .setPositiveButton("저장") { _, _ ->
                val name = input.text?.toString()?.trim()
                if (name.isNullOrEmpty()) {
                    Toast.makeText(this, "이름을 입력해주세요.", Toast.LENGTH_SHORT).show()
                } else {
                    ProfileManager.save(this, Profile(name = name, config = cfg))
                    sendBroadcast(Intent("com.autoclicker.ACTION_PROFILE_CHANGED").apply { setPackage(packageName) })
                    Toast.makeText(this, "\"$name\" 저장됐습니다.", Toast.LENGTH_SHORT).show()
                }
            }
            .create()
        dialog.window?.setType(overlayType())
        dialog.setOnDismissListener { isDialogShowing = false }
        dialog.show()
    }

    private fun showOverlayLoadProfileDialog() {
        if (isDialogShowing) return
        val profiles = ProfileManager.loadAll(this)
        if (profiles.isEmpty()) {
            Toast.makeText(this, "저장된 프로필이 없습니다.", Toast.LENGTH_SHORT).show()
            return
        }
        isDialogShowing = true
        val themedCtx = ContextThemeWrapper(this, R.style.Theme_AutoClicker)
        val names = profiles.map { it.name }.toTypedArray()
        val dialog = MaterialAlertDialogBuilder(themedCtx)
            .setTitle("프로필 불러오기")
            .setItems(names) { _, which ->
                val profile = profiles[which]
                if (PremiumManager.isPremium) {
                    SequencePrefs.save(this, profile.config)
                    sequenceJson = profile.config.toJsonString()
                    refreshMarkers()
                    Toast.makeText(this, "\"${profile.name}\" 불러왔습니다.", Toast.LENGTH_SHORT).show()
                } else {
                    // 광고는 Activity 컨텍스트 필요 → MainActivity 경유
                    val intent = Intent(this, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        putExtra("load_profile_id", profile.id)
                    }
                    startActivity(intent)
                }
            }
            .setNegativeButton("취소", null)
            .create()
        dialog.window?.setType(overlayType())
        dialog.setOnDismissListener { isDialogShowing = false }
        dialog.show()
    }

    private fun deletePointInOverlay(index: Int, cfg: ClickSequenceConfig) {
        val newPoints = cfg.points.toMutableList().also { it.removeAt(index) }
        SequencePrefs.save(this, cfg.copy(points = newPoints))
        refreshMarkers()
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
        swipeEndMarkers.forEach { runCatching { windowManager.removeView(it.view) } }
        swipeEndMarkers.clear()
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

// ── 스와이프 끝점 마커 커스텀 뷰 ───────────────────────────────────────────

class SwipeEndMarkerView(context: Context, private val pointNumber: Int, private val waypointNumber: Int = 1) : View(context) {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x80F85149.toInt()
        style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCCF85149.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 3f
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(8f, 4f), 0f)
    }
    private val xPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCCFFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
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

        // X 마크 (끝점 표시)
        val arm = r * 0.40f
        canvas.drawLine(cx - arm, cy - arm, cx + arm, cy + arm, xPaint)
        canvas.drawLine(cx + arm, cy - arm, cx - arm, cy + arm, xPaint)

        // "N-1" 레이블
        textPaint.textSize = height * 0.26f
        canvas.drawText("$pointNumber-$waypointNumber", cx, cy + textPaint.textSize * 0.36f, textPaint)
    }
}
