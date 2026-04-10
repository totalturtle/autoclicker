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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val index = intent.getIntExtra(EXTRA_POINT_INDEX, -1)
        if (index < 0) { finish(); return }

        val cfg = SequencePrefs.load(this)
        if (cfg == null || index >= cfg.points.size) { finish(); return }

        showSettingsDialog(index, cfg.points[index], cfg)
    }

    private fun showSettingsDialog(index: Int, point: ClickPoint, cfg: ClickSequenceConfig) {
        val d = DialogAddPointBinding.inflate(LayoutInflater.from(this))

        // 픽 버튼은 오버레이 환경에서 불필요 — 숨김
        d.btnPickStart.visibility   = View.GONE
        d.btnPickEnd.visibility     = View.GONE
        d.btnPickTrigger.visibility = View.GONE
        d.btnPickColor.visibility   = View.GONE

        // 현재 포인트 값으로 채우기
        d.etDialogX.setText(point.x.toString())
        d.etDialogY.setText(point.y.toString())
        d.etDialogLabel.setText(point.label)
        if (point.delayAfterMs >= 0) d.etDialogDelayAfter.setText(point.delayAfterMs.toString())

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

        val trigger = point.trigger
        if (trigger != null) {
            d.cbTrigger.isChecked        = true
            d.groupTrigger.visibility    = View.VISIBLE
            d.etTriggerX.setText(trigger.checkX.toString())
            d.etTriggerY.setText(trigger.checkY.toString())
            d.etTriggerTolerance.setText(trigger.tolerance.toString())
            d.etTriggerMaxRetries.setText(trigger.maxRetries.toString())
            d.etTriggerRetryDelay.setText(trigger.retryDelayMs.toString())
            val colorHex = "#%06X".format(trigger.targetColor and 0xFFFFFF)
            d.etTriggerColor.setText(colorHex)
            val actionPos = if (trigger.action == TriggerAction.WAIT_RETRY) 1 else 0
            d.spinnerTriggerAction.setSelection(actionPos)
            d.groupTriggerRetry.visibility = if (actionPos == 1) View.VISIBLE else View.GONE
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
        d.spinnerTriggerAction.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                d.groupTriggerRetry.visibility = if (position == 1) View.VISIBLE else View.GONE
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("포인트 #${index + 1} 설정")
            .setView(d.root)
            .setNeutralButton("삭제") { _, _ -> deletePoint(index, cfg) }
            .setNegativeButton("취소", null)
            .setPositiveButton("확인") { _, _ -> savePoint(index, d, point, cfg) }
            .setOnDismissListener { finish() }
            .show()
    }

    private fun savePoint(index: Int, d: DialogAddPointBinding, original: ClickPoint, cfg: ClickSequenceConfig) {
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
            val cx     = d.etTriggerX.text?.toString()?.toIntOrNull()
            val cy     = d.etTriggerY.text?.toString()?.toIntOrNull()
            val color  = TriggerCondition.parseColor(d.etTriggerColor.text?.toString()?.trim() ?: "")
            if (cx == null || cy == null || color == null) { original.trigger }
            else {
                val tol    = d.etTriggerTolerance.text?.toString()?.toIntOrNull()?.coerceIn(0, 255) ?: 20
                val actPos = d.spinnerTriggerAction.selectedItemPosition
                val action = if (actPos == 1) TriggerAction.WAIT_RETRY else TriggerAction.SKIP
                val maxR   = d.etTriggerMaxRetries.text?.toString()?.toIntOrNull()?.coerceAtLeast(1) ?: 5
                val rDelay = d.etTriggerRetryDelay.text?.toString()?.toLongOrNull()?.coerceAtLeast(100L) ?: 500L
                TriggerCondition(cx, cy, color, tol, action, maxR, rDelay)
            }
        } else null

        val updated = original.copy(
            x = x, y = y, label = label, delayAfterMs = delayAfter,
            gesture = gesture, endX = endX, endY = endY,
            longPressDurationMs = longMs, swipeDurationMs = swipeMs,
            trigger = newTrigger
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
