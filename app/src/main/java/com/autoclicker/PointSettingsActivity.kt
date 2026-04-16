package com.autoclicker

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.AdapterView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.autoclicker.databinding.DialogAddPointBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * 오버레이 마커를 탭했을 때 나타나는 포인트 세부 설정 Activity.
 * 투명 배경 + MaterialAlertDialog로 구성되며, 확인 시 SequencePrefs 업데이트 후 종료.
 */
class PointSettingsActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_POINT_INDEX = "point_index"
        /** OverlayService 가 수신하여 마커를 갱신 */
        const val ACTION_POINT_UPDATED = "com.autoclicker.POINT_UPDATED"
    }

    private var capturingRegion = false
    private var capturedRegionPixels: IntArray? = null
    private var capturedRegionX = 0
    private var capturedRegionY = 0
    private var capturedRegionW = 0
    private var capturedRegionH = 0
    private var capturingRegionIndex = -1

    private val regionCaptureReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
            if (intent.action != AutoClickAccessibilityService.ACTION_REGION_CAPTURED) return
            val target = intent.getStringExtra(AutoClickAccessibilityService.EXTRA_REGION_TARGET) ?: return
            if (target != "settings_region") return
            val pixels = intent.getIntArrayExtra(AutoClickAccessibilityService.EXTRA_REGION_PIXELS) ?: return
            capturedRegionPixels = if (pixels.isNotEmpty()) pixels else null
            capturedRegionX = intent.getIntExtra(AutoClickAccessibilityService.EXTRA_REGION_X, 0)
            capturedRegionY = intent.getIntExtra(AutoClickAccessibilityService.EXTRA_REGION_Y, 0)
            capturedRegionW = intent.getIntExtra(AutoClickAccessibilityService.EXTRA_REGION_W, 0)
            capturedRegionH = intent.getIntExtra(AutoClickAccessibilityService.EXTRA_REGION_H, 0)
            capturingRegion = false
            // 다이얼로그 재오픈
            val idx = capturingRegionIndex
            if (idx < 0) return
            val cfg = SequencePrefs.load(this@PointSettingsActivity) ?: return
            if (idx >= cfg.points.size) return
            showSettingsDialog(idx, cfg.points[idx], cfg)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val index = intent.getIntExtra(EXTRA_POINT_INDEX, -1)
        if (index < 0) { finish(); return }

        val cfg = SequencePrefs.load(this)
        if (cfg == null || index >= cfg.points.size) { finish(); return }

        val filter = android.content.IntentFilter(AutoClickAccessibilityService.ACTION_REGION_CAPTURED)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(regionCaptureReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(regionCaptureReceiver, filter)
        }

        showSettingsDialog(index, cfg.points[index], cfg)
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { unregisterReceiver(regionCaptureReceiver) }
    }

    private fun showSettingsDialog(index: Int, point: ClickPoint, cfg: ClickSequenceConfig) {
        val d = DialogAddPointBinding.inflate(LayoutInflater.from(this))

        // 픽 버튼은 오버레이 환경에서 불필요 — 숨김
        d.btnPickStart.visibility   = View.GONE
        d.btnPickEnd.visibility     = View.GONE
        d.btnPickTrigger.visibility = View.GONE
        d.btnPickColor.visibility   = View.GONE

        // 트리거 모드 UI 토글
        fun applyTriggerModeUi(modePos: Int) {
            d.groupTriggerPixel.visibility  = if (modePos == 0) View.VISIBLE else View.GONE
            d.groupTriggerRegion.visibility = if (modePos == 1) View.VISIBLE else View.GONE
            d.groupTriggerCoords.visibility = if (modePos == 0) View.VISIBLE else View.GONE
        }

        // 현재 포인트 값으로 채우기
        d.etDialogX.setText(point.x.toString())
        d.etDialogY.setText(point.y.toString())
        d.etDialogLabel.setText(point.label)
        if (point.delayAfterMs >= 0) d.etDialogDelayAfter.setText(point.delayAfterMs.toString())
        if (point.randomVarianceMs > 0) d.etDialogVariance.setText(point.randomVarianceMs.toString())
        d.etDialogPointRepeat.setText(point.pointRepeatCount.toString())

        val gesturePos = when (point.gesture) {
            GestureType.LONG_PRESS -> 1
            GestureType.SWIPE      -> 2
            else                   -> 0
        }

        fun applyGestureUi(pos: Int) {
            d.groupSwipe.visibility      = if (pos == 2) View.VISIBLE else View.GONE
            d.tilDialogLongDur.visibility = if (pos == 1) View.VISIBLE else View.GONE
        }

        d.spinnerGesture.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) =
                applyGestureUi(position)
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
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

        // 현재 포인트 트리거값 + 캡처된 픽셀 데이터
        var regionPixelsForSave: IntArray? = capturedRegionPixels
        val trigger = point.trigger
        if (trigger != null) {
            d.cbTrigger.isChecked    = true
            d.groupTrigger.visibility = View.VISIBLE
            d.etTriggerX.setText(trigger.checkX.toString())
            d.etTriggerY.setText(trigger.checkY.toString())
            d.etTriggerTolerance.setText(trigger.tolerance.toString())
            d.etTriggerMaxRetries.setText(trigger.maxRetries.toString())
            d.etTriggerRetryDelay.setText(trigger.retryDelayMs.toString())
            val actionPos = if (trigger.action == TriggerAction.WAIT_RETRY) 1 else 0
            d.spinnerTriggerAction.setSelection(actionPos)
            d.groupTriggerRetry.visibility = if (actionPos == 1) View.VISIBLE else View.GONE
            when (trigger.mode) {
                TriggerMode.REGION -> {
                    d.rgTriggerMode.check(R.id.rbModeRegion)
                    applyTriggerModeUi(1)
                    d.etRegionW.setText(trigger.regionW.toString())
                    d.etRegionH.setText(trigger.regionH.toString())
                    d.etRegionThreshold.setText((trigger.regionMatchThreshold * 100).toInt().toString())
                    val existingPixels = trigger.regionPixels
                    if (regionPixelsForSave == null) regionPixelsForSave = existingPixels
                    d.tvRegionCaptureStatus.text = if (regionPixelsForSave != null) {
                        val cx = capturedRegionX.takeIf { capturedRegionW > 0 } ?: trigger.checkX
                        val cy = capturedRegionY.takeIf { capturedRegionW > 0 } ?: trigger.checkY
                        val rw = capturedRegionW.takeIf { it > 0 } ?: trigger.regionW
                        val rh = capturedRegionH.takeIf { it > 0 } ?: trigger.regionH
                        "캡처됨: ($cx,$cy) ${rw}×${rh}px"
                    } else "캡처 없음"
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
        // 캡처 결과가 새로 왔으면 영역 모드로 전환
        if (capturedRegionPixels != null) {
            d.cbTrigger.isChecked = true
            d.groupTrigger.visibility = View.VISIBLE
            d.rgTriggerMode.check(R.id.rbModeRegion)
            applyTriggerModeUi(1)
            // 드래그 선택 결과로 좌표/크기 자동 설정
            d.etTriggerX.setText(capturedRegionX.toString())
            d.etTriggerY.setText(capturedRegionY.toString())
            d.etRegionW.setText(capturedRegionW.toString())
            d.etRegionH.setText(capturedRegionH.toString())
            d.tvRegionCaptureStatus.text = "캡처됨: (${capturedRegionX},${capturedRegionY}) ${capturedRegionW}×${capturedRegionH}px"
            capturedRegionPixels = null  // 소비 완료
        }

        // 기존 색상값 프리뷰 표시
        val initColor = TriggerCondition.parseColor(d.etTriggerColor.text?.toString() ?: "")
        if (initColor != null) d.tvTriggerColorPreview.setBackgroundColor(initColor)
        d.etTriggerColor.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                val c = TriggerCondition.parseColor(s?.toString() ?: "") ?: return
                d.tvTriggerColorPreview.setBackgroundColor(c)
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        })

        d.cbTrigger.setOnCheckedChangeListener { _, checked ->
            d.groupTrigger.visibility = if (checked) View.VISIBLE else View.GONE
        }
        d.rgTriggerMode.setOnCheckedChangeListener { _, checkedId ->
            applyTriggerModeUi(if (checkedId == R.id.rbModeRegion) 1 else 0)
        }
        d.spinnerTriggerAction.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                d.groupTriggerRetry.visibility = if (position == 1) View.VISIBLE else View.GONE
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("포인트 #${index + 1} 설정")
            .setView(d.root)
            .setNeutralButton("삭제") { _, _ -> deletePoint(index, cfg) }
            .setNegativeButton("취소", null)
            .setPositiveButton("확인") { _, _ -> savePoint(index, d, point, cfg, regionPixelsForSave) }
            .create()

        // 영역 드래그 지정 버튼
        d.btnCaptureRegion.setOnClickListener {
            capturingRegion = true
            capturingRegionIndex = index
            dialog.dismiss()
            startService(Intent(this, CoordPickerService::class.java).apply {
                putExtra(CoordPickerService.EXTRA_PICK_TARGET, CoordPickerService.TARGET_REGION_CAPTURE)
                putExtra(CoordPickerService.EXTRA_REGION_RESULT_TARGET, "settings_region")
            })
        }

        dialog.setOnDismissListener { if (!capturingRegion) finish() }
        dialog.show()
    }

    private fun savePoint(index: Int, d: DialogAddPointBinding, original: ClickPoint, cfg: ClickSequenceConfig, regionPixels: IntArray? = null) {
        val gesturePos = d.spinnerGesture.selectedItemPosition
        val gesture = when (gesturePos) {
            1    -> GestureType.LONG_PRESS
            2    -> GestureType.SWIPE
            else -> GestureType.TAP
        }

        val x = d.etDialogX.text?.toString()?.toIntOrNull() ?: original.x
        val y = d.etDialogY.text?.toString()?.toIntOrNull() ?: original.y
        val label = d.etDialogLabel.text?.toString()?.trim().orEmpty()
        val delayAfter = d.etDialogDelayAfter.text?.toString()?.toLongOrNull() ?: -1L
        val pointRepeat = d.etDialogPointRepeat.text?.toString()?.toIntOrNull()?.coerceAtLeast(1) ?: 1

        var endX = x; var endY = y
        var longMs = original.longPressDurationMs
        var swipeMs = original.swipeDurationMs

        when (gesture) {
            GestureType.SWIPE -> {
                endX   = d.etDialogEndX.text?.toString()?.toIntOrNull() ?: original.endX
                endY   = d.etDialogEndY.text?.toString()?.toIntOrNull() ?: original.endY
                swipeMs = d.etDialogSwipeDur.text?.toString()?.toLongOrNull() ?: 350L
                if (swipeMs < 50 || swipeMs > 60_000) {
                    Toast.makeText(this, "스와이프 지속 시간은 50~60000ms 입니다.", Toast.LENGTH_SHORT).show()
                    return
                }
            }
            GestureType.LONG_PRESS -> {
                longMs = d.etDialogLongDur.text?.toString()?.toLongOrNull() ?: 450L
                if (longMs < 100 || longMs > 60_000) {
                    Toast.makeText(this, "롱 프레스 시간은 100~60000ms 입니다.", Toast.LENGTH_SHORT).show()
                    return
                }
            }
            else -> Unit
        }

        val newTrigger = if (d.cbTrigger.isChecked) {
            val cx = d.etTriggerX.text?.toString()?.toIntOrNull()
            val cy = d.etTriggerY.text?.toString()?.toIntOrNull()
            if (cx == null || cy == null) { original.trigger }
            else {
                val tol    = d.etTriggerTolerance.text?.toString()?.toIntOrNull()?.coerceIn(0, 255) ?: 20
                val actPos = d.spinnerTriggerAction.selectedItemPosition
                val action = if (actPos == 1) TriggerAction.WAIT_RETRY else TriggerAction.SKIP
                val maxR   = d.etTriggerMaxRetries.text?.toString()?.toIntOrNull()?.coerceAtLeast(1) ?: 5
                val rDelay = d.etTriggerRetryDelay.text?.toString()?.toLongOrNull()?.coerceAtLeast(100L) ?: 500L
                val modePos = if (d.rbModeRegion.isChecked) 1 else 0
                if (modePos == 1) {
                    val rw  = d.etRegionW.text?.toString()?.toIntOrNull() ?: 0
                    val rh  = d.etRegionH.text?.toString()?.toIntOrNull() ?: 0
                    val thr = (d.etRegionThreshold.text?.toString()?.toIntOrNull() ?: 90).coerceIn(0, 100) / 100f
                    val px  = regionPixels ?: original.trigger?.takeIf { it.mode == TriggerMode.REGION }?.regionPixels
                    TriggerCondition(cx, cy, 0, tol, action, maxR, rDelay,
                        mode = TriggerMode.REGION, regionW = rw, regionH = rh,
                        regionPixels = px, regionMatchThreshold = thr)
                } else {
                    val color = TriggerCondition.parseColor(d.etTriggerColor.text?.toString()?.trim() ?: "")
                    if (color == null) original.trigger
                    else TriggerCondition(cx, cy, color, tol, action, maxR, rDelay)
                }
            }
        } else null

        val updated = original.copy(
            x = x, y = y, label = label, delayAfterMs = delayAfter,
            gesture = gesture, endX = endX, endY = endY,
            longPressDurationMs = longMs, swipeDurationMs = swipeMs,
            trigger = newTrigger, pointRepeatCount = pointRepeat,
            swipeExtraPoints = if (gesture == GestureType.SWIPE) original.swipeExtraPoints else emptyList()
        )

        val newPoints = cfg.points.toMutableList().also { it[index] = updated }
        SequencePrefs.save(this, cfg.copy(points = newPoints))
        notifyUpdated()
    }

    private fun deletePoint(index: Int, cfg: ClickSequenceConfig) {
        val newPoints = cfg.points.toMutableList().also { it.removeAt(index) }
        SequencePrefs.save(this, cfg.copy(points = newPoints))
        notifyUpdated()
    }

    private fun notifyUpdated() {
        sendBroadcast(Intent(ACTION_POINT_UPDATED).apply { setPackage(packageName) })
    }
}
