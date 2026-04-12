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
    private var pendingColorPickIndex = -1
    private var sequenceJson: String? = null
    private var markersVisible        = true

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
                AutoClickAccessibilityService.ACTION_STARTED -> setRunningState(true)
                AutoClickAccessibilityService.ACTION_STOP -> setRunningState(false)
                AutoClickAccessibilityService.ACTION_AUTO_PROFILE -> {
                    sequenceJson = SequencePrefs.load(this@OverlayService)?.toJsonString() ?: sequenceJson
                    refreshMarkers()
                }
                PointSettingsActivity.ACTION_POINT_UPDATED -> refreshMarkers()
                ACTION_TOGGLE_MARKERS -> setMarkersVisible(!markersVisible)
                AutoClickAccessibilityService.ACTION_COLOR_SAMPLED -> {
                    val target = intent.getStringExtra(AutoClickAccessibilityService.EXTRA_SAMPLE_TARGET) ?: return
                    if (target != CoordPickerService.TARGET_OVERLAY_COLOR) return
                    val x = intent.getIntExtra(AutoClickAccessibilityService.EXTRA_SAMPLE_X, 0)
                    val y = intent.getIntExtra(AutoClickAccessibilityService.EXTRA_SAMPLE_Y, 0)
                    val color = intent.getIntExtra(AutoClickAccessibilityService.EXTRA_SAMPLED_COLOR, Int.MIN_VALUE)
                    val idx = pendingColorPickIndex
                    pendingColorPickIndex = -1
                    if (idx >= 0) openPointSettings(idx, pickedColor = if (color != Int.MIN_VALUE) color else null)
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
            addAction(PointSettingsActivity.ACTION_POINT_UPDATED)
            addAction(ACTION_TOGGLE_MARKERS)
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
                            val json = sequenceJson ?: SequencePrefs.load(this)?.toJsonString()
                            if (json != null) {
                                sendBroadcast(Intent(AutoClickAccessibilityService.ACTION_START).apply {
                                    setPackage(packageName)
                                    putExtra(AutoClickAccessibilityService.EXTRA_SEQUENCE_JSON, json)
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
    }

    private fun setMarkersVisible(visible: Boolean) {
        markersVisible = visible
        val v = if (markersVisible && !isRunning) View.VISIBLE else View.INVISIBLE
        markers.forEach { it.view.visibility = v }
        overlayView.findViewById<ImageButton>(R.id.btnOverlayToggleMarkers)?.alpha =
            if (markersVisible) 1f else 0.4f
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
        sequenceJson = SequencePrefs.loadRawJson(this)
        val cfg = ClickSequenceConfig.fromJsonString(sequenceJson) ?: return
        clearMarkers()
        cfg.points.forEachIndexed { idx, pt -> addMarkerView(idx, pt.x, pt.y) }
    }

    private fun refreshMarkers() {
        sequenceJson = SequencePrefs.loadRawJson(this)
        val cfg = ClickSequenceConfig.fromJsonString(sequenceJson)
        clearMarkers()
        cfg?.points?.forEachIndexed { idx, pt -> addMarkerView(idx, pt.x, pt.y) }
        if (!markersVisible || isRunning) {
            markers.forEach { it.view.visibility = View.INVISIBLE }
        }
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

    private fun openPointSettings(index: Int, pickedColor: Int? = null) {
        if (isDialogShowing) return  // 연쇄 오픈 방지
        val cfg = SequencePrefs.load(this) ?: return
        if (index >= cfg.points.size) return
        // 다이얼로그가 열린 동안 마커 터치 차단
        isDialogShowing = true
        markers.forEach { entry ->
            entry.params.flags = entry.params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            runCatching { windowManager.updateViewLayout(entry.view, entry.params) }
        }
        val point = cfg.points[index]

        val themedCtx = ContextThemeWrapper(this, R.style.Theme_AutoClicker)
        val d = DialogAddPointBinding.inflate(LayoutInflater.from(themedCtx))

        d.btnPickStart.visibility   = View.GONE
        d.btnPickEnd.visibility     = View.GONE
        d.btnPickTrigger.visibility = View.GONE

        d.etDialogX.setText(point.x.toString())
        d.etDialogY.setText(point.y.toString())
        d.etDialogLabel.setText(point.label)
        if (point.delayAfterMs >= 0) d.etDialogDelayAfter.setText(point.delayAfterMs.toString())
        if (point.randomVarianceMs > 0) d.etDialogVariance.setText(point.randomVarianceMs.toString())

        val gesturePos = when (point.gesture) {
            GestureType.LONG_PRESS -> 1
            GestureType.SWIPE      -> 2
            else                   -> 0
        }
        fun applyGestureUi(pos: Int) {
            d.groupSwipe.visibility       = if (pos == 2) View.VISIBLE else View.GONE
            d.tilDialogLongDur.visibility = if (pos == 1) View.VISIBLE else View.GONE
        }
        d.spinnerGesture.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, position: Int, id: Long) = applyGestureUi(position)
            override fun onNothingSelected(p: AdapterView<*>?) = Unit
        }
        d.spinnerGesture.setSelection(gesturePos)
        applyGestureUi(gesturePos)

        if (point.gesture == GestureType.SWIPE) {
            d.etDialogEndX.setText(point.endX.toString())
            d.etDialogEndY.setText(point.endY.toString())
            d.etDialogSwipeDur.setText(point.swipeDurationMs.toString())
        }
        if (point.gesture == GestureType.LONG_PRESS) {
            d.etDialogLongDur.setText(point.longPressDurationMs.toString())
        }

        val trigger = point.trigger
        if (trigger != null) {
            d.cbTrigger.isChecked             = true
            d.groupTrigger.visibility         = View.VISIBLE
            d.cbTriggerSameAsPoint.isChecked  = trigger.usePointCoords
            d.groupTriggerCoords.visibility   = if (!trigger.usePointCoords) View.VISIBLE else View.GONE
            d.etTriggerX.setText(trigger.checkX.toString())
            d.etTriggerY.setText(trigger.checkY.toString())
            d.etTriggerTolerance.setText(trigger.tolerance.toString())
            d.etTriggerMaxRetries.setText(trigger.maxRetries.toString())
            d.etTriggerRetryDelay.setText(trigger.retryDelayMs.toString())
            d.etTriggerColor.setText("#%06X".format(trigger.targetColor and 0xFFFFFF))
            val actPos = if (trigger.action == TriggerAction.WAIT_RETRY) 1 else 0
            d.spinnerTriggerAction.setSelection(actPos)
            d.groupTriggerRetry.visibility = if (actPos == 1) View.VISIBLE else View.GONE
        }
        // 색상 피커로 결과가 왔으면 색상값만 적용 (확인 위치 = 포인트 위치)
        if (pickedColor != null) {
            d.cbTrigger.isChecked            = true
            d.groupTrigger.visibility        = View.VISIBLE
            d.cbTriggerSameAsPoint.isChecked = true
            d.groupTriggerCoords.visibility  = View.GONE
            val colorHex = "#%06X".format(pickedColor and 0xFFFFFF)
            d.etTriggerColor.setText(colorHex)
            d.tvTriggerColorPreview.setBackgroundColor(pickedColor)
        }

        d.cbTrigger.setOnCheckedChangeListener { _, checked ->
            d.groupTrigger.visibility = if (checked) View.VISIBLE else View.GONE
        }
        d.cbTriggerSameAsPoint.setOnCheckedChangeListener { _, sameAsPoint ->
            d.groupTriggerCoords.visibility = if (!sameAsPoint) View.VISIBLE else View.GONE
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
            .setPositiveButton("확인") { _, _ -> savePointInOverlay(index, d, point, cfg) }
            .create()

        // 색상 피커 버튼
        d.btnPickColor.setOnClickListener {
            pendingColorPickIndex = index
            dialog.dismiss()
            startService(Intent(this, CoordPickerService::class.java).apply {
                putExtra(CoordPickerService.EXTRA_PICK_TARGET, CoordPickerService.TARGET_OVERLAY_COLOR)
            })
        }

        // 오버레이 윈도우 타입으로 띄워야 배경 앱 위에 표시됨
        dialog.window?.setType(overlayType())
        dialog.setOnDismissListener {
            isDialogShowing = false
            markers.forEach { updateMarkerTouchable(it) }
        }
        dialog.show()
    }

    private fun savePointInOverlay(index: Int, d: DialogAddPointBinding, original: ClickPoint, cfg: ClickSequenceConfig) {
        val gesturePos = d.spinnerGesture.selectedItemPosition
        val gesture = when (gesturePos) { 1 -> GestureType.LONG_PRESS; 2 -> GestureType.SWIPE; else -> GestureType.TAP }

        val x = d.etDialogX.text?.toString()?.toIntOrNull() ?: original.x
        val y = d.etDialogY.text?.toString()?.toIntOrNull() ?: original.y
        val label = d.etDialogLabel.text?.toString()?.trim().orEmpty()
        val delayAfter = d.etDialogDelayAfter.text?.toString()?.toLongOrNull() ?: -1L
        val variance = d.etDialogVariance.text?.toString()?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L

        var endX = x; var endY = y
        var longMs = original.longPressDurationMs
        var swipeMs = original.swipeDurationMs

        when (gesture) {
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

        val newTrigger = if (d.cbTrigger.isChecked) {
            val sameAsPoint = d.cbTriggerSameAsPoint.isChecked
            val cx    = if (sameAsPoint) x else d.etTriggerX.text?.toString()?.toIntOrNull()
            val cy    = if (sameAsPoint) y else d.etTriggerY.text?.toString()?.toIntOrNull()
            val color = TriggerCondition.parseColor(d.etTriggerColor.text?.toString()?.trim() ?: "")
            if (cx == null || cy == null || color == null) original.trigger
            else {
                val tol    = d.etTriggerTolerance.text?.toString()?.toIntOrNull()?.coerceIn(0, 255) ?: 20
                val actPos = d.spinnerTriggerAction.selectedItemPosition
                val action = if (actPos == 1) TriggerAction.WAIT_RETRY else TriggerAction.SKIP
                val maxR   = d.etTriggerMaxRetries.text?.toString()?.toIntOrNull()?.coerceAtLeast(1) ?: 5
                val rDelay = d.etTriggerRetryDelay.text?.toString()?.toLongOrNull()?.coerceAtLeast(100L) ?: 500L
                TriggerCondition(cx, cy, color, tol, action, maxR, rDelay, usePointCoords = sameAsPoint)
            }
        } else null

        val updated = original.copy(
            x = x, y = y, label = label, delayAfterMs = delayAfter,
            gesture = gesture, endX = endX, endY = endY,
            longPressDurationMs = longMs, swipeDurationMs = swipeMs,
            trigger = newTrigger, randomVarianceMs = variance
        )
        val newPoints = cfg.points.toMutableList().also { it[index] = updated }
        SequencePrefs.save(this, cfg.copy(points = newPoints))
        refreshMarkers()
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
