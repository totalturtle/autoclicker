package com.autoclicker

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.AdapterView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.autoclicker.databinding.ActivityMainBinding
import com.autoclicker.databinding.DialogAddPointBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * 메인 화면.
 * - 접근성 / 오버레이 권한
 * - 다중 클릭 포인트 목록 (드래그 순서 변경)
 * - 시퀀스 반복 · 공통 딜레이
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var isRunning = false

    private val points = mutableListOf<ClickPoint>()
    private lateinit var pointAdapter: PointListAdapter
    private lateinit var itemTouchHelper: ItemTouchHelper

    private val profileManagerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val json = result.data?.getStringExtra(ProfileManagerActivity.RESULT_PROFILE_JSON)
            val config = ClickSequenceConfig.fromJsonString(json) ?: return@registerForActivityResult
            points.clear()
            points.addAll(config.points)
            binding.etDelay.setText(config.globalDelayMs.toString())
            binding.etRepeat.setText(config.repeatCount.toString())
            pointAdapter.notifyDataSetChanged()
            persistSequence()
            Toast.makeText(this, R.string.toast_profile_loaded_generic, Toast.LENGTH_SHORT).show()
        }
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                AutoClickAccessibilityService.ACTION_CLICK_COUNT -> {
                    val count = intent.getIntExtra(AutoClickAccessibilityService.EXTRA_COUNT, 0)
                    binding.tvClickCount.text = getString(R.string.click_count_fmt, count)
                }
                AutoClickAccessibilityService.ACTION_STOP -> {
                    setRunningState(false)
                }
                AutoClickAccessibilityService.ACTION_STARTED -> {
                    setRunningState(true)
                }
                AutoClickAccessibilityService.ACTION_AUTO_PROFILE -> {
                    val name = intent.getStringExtra(AutoClickAccessibilityService.EXTRA_PROFILE_NAME) ?: return
                    val cfg = SequencePrefs.load(this@MainActivity) ?: return
                    points.clear()
                    points.addAll(cfg.points)
                    binding.etDelay.setText(cfg.globalDelayMs.toString())
                    binding.etRepeat.setText(cfg.repeatCount.toString())
                    pointAdapter.notifyDataSetChanged()
                    Toast.makeText(this@MainActivity, getString(R.string.toast_auto_profile_loaded, name), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupPointList()
        setupButtons()
        loadSequence()
        registerStatusReceiver()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
    }

    override fun onPause() {
        super.onPause()
        SequencePrefs.setVolumeHotkeyEnabled(this, binding.switchVolumeHotkey.isChecked)
        persistSequence()
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { unregisterReceiver(statusReceiver) }
    }

    private fun setupPointList() {
        pointAdapter = PointListAdapter(
            points,
            onListChanged = { persistSequence() },
            onStartDrag = { vh -> itemTouchHelper.startDrag(vh) }
        )
        itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            0
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                pointAdapter.moveItem(
                    viewHolder.bindingAdapterPosition,
                    target.bindingAdapterPosition
                )
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit
        })
        binding.rvPoints.layoutManager = LinearLayoutManager(this)
        binding.rvPoints.adapter = pointAdapter
        itemTouchHelper.attachToRecyclerView(binding.rvPoints)
    }

    private fun setupButtons() {
        binding.btnEnableAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        binding.btnEnableOverlay.setOnClickListener {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }

        binding.btnAddPoint.setOnClickListener { showAddPointDialog() }

        binding.btnStats.setOnClickListener {
            startActivity(Intent(this, StatsActivity::class.java))
        }

        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.btnProfileManager.setOnClickListener {
            val json = buildConfig()?.toJsonString()
            val intent = Intent(this, ProfileManagerActivity::class.java).apply {
                putExtra(ProfileManagerActivity.EXTRA_CURRENT_CONFIG_JSON, json)
            }
            profileManagerLauncher.launch(intent)
        }

        binding.switchVolumeHotkey.setOnCheckedChangeListener { _, checked ->
            SequencePrefs.setVolumeHotkeyEnabled(this, checked)
        }

        binding.btnStart.setOnClickListener {
            if (!isAccessibilityEnabled()) {
                Toast.makeText(this, "접근성 서비스를 먼저 활성화하세요.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "오버레이 권한을 허용하세요.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val config = readConfig() ?: return@setOnClickListener
            persistSequence()
            startAutoClick(config)
            setRunningState(true)
        }

        binding.btnStop.setOnClickListener {
            stopAutoClick()
            setRunningState(false)
        }
    }

    private fun showAddPointDialog() {
        if (points.size >= 50) {
            Toast.makeText(this, R.string.toast_max_points, Toast.LENGTH_SHORT).show()
            return
        }
        val dialogBinding = DialogAddPointBinding.inflate(LayoutInflater.from(this))

        fun applyGestureUi(position: Int) {
            dialogBinding.groupSwipe.visibility = if (position == 2) View.VISIBLE else View.GONE
            dialogBinding.tilDialogLongDur.visibility = if (position == 1) View.VISIBLE else View.GONE
        }
        dialogBinding.spinnerGesture.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                applyGestureUi(position)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        applyGestureUi(0)

        dialogBinding.cbTrigger.setOnCheckedChangeListener { _, checked ->
            dialogBinding.groupTrigger.visibility = if (checked) View.VISIBLE else View.GONE
        }
        dialogBinding.spinnerTriggerAction.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                dialogBinding.groupTriggerRetry.visibility = if (position == 1) View.VISIBLE else View.GONE
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.btn_add_point)
            .setView(dialogBinding.root)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val pos = dialogBinding.spinnerGesture.selectedItemPosition
                val gesture = when (pos) {
                    1 -> GestureType.LONG_PRESS
                    2 -> GestureType.SWIPE
                    else -> GestureType.TAP
                }
                val x = dialogBinding.etDialogX.text?.toString()?.toIntOrNull()
                val y = dialogBinding.etDialogY.text?.toString()?.toIntOrNull()
                val label = dialogBinding.etDialogLabel.text?.toString()?.trim().orEmpty()
                val delayRaw = dialogBinding.etDialogDelayAfter.text?.toString()?.trim().orEmpty()
                val delayAfter = delayRaw.toLongOrNull() ?: -1L

                if (x == null || y == null) {
                    Toast.makeText(this, "X, Y 좌표를 입력하세요.", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (delayAfter >= 0 && delayAfter < 100) {
                    Toast.makeText(this, "포인트 딜레이는 비우거나 100ms 이상이어야 합니다.", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                var endX = x
                var endY = y
                var longMs = 450L
                var swipeMs = 350L

                when (gesture) {
                    GestureType.SWIPE -> {
                        val ex = dialogBinding.etDialogEndX.text?.toString()?.toIntOrNull()
                        val ey = dialogBinding.etDialogEndY.text?.toString()?.toIntOrNull()
                        if (ex == null || ey == null) {
                            Toast.makeText(this, R.string.toast_swipe_need_end, Toast.LENGTH_SHORT).show()
                            return@setPositiveButton
                        }
                        endX = ex
                        endY = ey
                        swipeMs = dialogBinding.etDialogSwipeDur.text?.toString()?.toLongOrNull() ?: 350L
                        if (swipeMs < 50 || swipeMs > 60_000) {
                            Toast.makeText(this, "스와이프 지속 시간은 50~60000ms 입니다.", Toast.LENGTH_SHORT).show()
                            return@setPositiveButton
                        }
                    }
                    GestureType.LONG_PRESS -> {
                        longMs = dialogBinding.etDialogLongDur.text?.toString()?.toLongOrNull() ?: 450L
                        if (longMs < 100 || longMs > 60_000) {
                            Toast.makeText(this, "롱 프레스 시간은 100~60000ms 입니다.", Toast.LENGTH_SHORT).show()
                            return@setPositiveButton
                        }
                    }
                    else -> Unit
                }

                val trigger = if (dialogBinding.cbTrigger.isChecked) {
                    val cx = dialogBinding.etTriggerX.text?.toString()?.toIntOrNull()
                    val cy = dialogBinding.etTriggerY.text?.toString()?.toIntOrNull()
                    val colorHex = dialogBinding.etTriggerColor.text?.toString()?.trim() ?: ""
                    val color = TriggerCondition.parseColor(colorHex)
                    if (cx == null || cy == null) {
                        Toast.makeText(this, R.string.toast_trigger_need_coords, Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                    if (color == null) {
                        Toast.makeText(this, R.string.toast_trigger_invalid_color, Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                    val tol = dialogBinding.etTriggerTolerance.text?.toString()?.toIntOrNull()?.coerceIn(0, 255) ?: 20
                    val actionPos = dialogBinding.spinnerTriggerAction.selectedItemPosition
                    val action = if (actionPos == 1) TriggerAction.WAIT_RETRY else TriggerAction.SKIP
                    val maxR = dialogBinding.etTriggerMaxRetries.text?.toString()?.toIntOrNull()?.coerceAtLeast(1) ?: 5
                    val rDelay = dialogBinding.etTriggerRetryDelay.text?.toString()?.toLongOrNull()?.coerceAtLeast(100L) ?: 500L
                    TriggerCondition(cx, cy, color, tol, action, maxR, rDelay)
                } else null

                points.add(
                    ClickPoint(
                        x = x,
                        y = y,
                        label = label,
                        delayAfterMs = delayAfter,
                        gesture = gesture,
                        endX = endX,
                        endY = endY,
                        longPressDurationMs = longMs,
                        swipeDurationMs = swipeMs,
                        trigger = trigger
                    )
                )
                pointAdapter.notifyItemInserted(points.size - 1)
                persistSequence()
            }
            .show()
    }

    private fun loadSequence() {
        val cfg = SequencePrefs.load(this)
        points.clear()
        if (cfg != null) {
            points.addAll(cfg.points)
            binding.etDelay.setText(cfg.globalDelayMs.toString())
            binding.etRepeat.setText(cfg.repeatCount.toString())
            binding.etRandomMin.setText(cfg.randomDelayMinMs.toString())
            binding.etRandomMax.setText(cfg.randomDelayMaxMs.toString())
        } else {
            binding.etDelay.setText("1000")
            binding.etRepeat.setText("0")
            binding.etRandomMin.setText("0")
            binding.etRandomMax.setText("0")
        }
        binding.switchVolumeHotkey.isChecked = SequencePrefs.isVolumeHotkeyEnabled(this)
        pointAdapter.notifyDataSetChanged()
    }

    private fun persistSequence() {
        SequencePrefs.save(this, readRawConfig())
    }

    private fun buildConfig(): ClickSequenceConfig? {
        if (points.isEmpty()) return null
        return readRawConfig()
    }

    private fun readRawConfig(): ClickSequenceConfig {
        val global = binding.etDelay.text?.toString()?.toLongOrNull() ?: 1000L
        val repeat = binding.etRepeat.text?.toString()?.toIntOrNull() ?: 0
        val rMin = binding.etRandomMin.text?.toString()?.toLongOrNull() ?: 0L
        val rMax = binding.etRandomMax.text?.toString()?.toLongOrNull() ?: 0L
        return ClickSequenceConfig(
            points = points.toList(),
            globalDelayMs = global,
            repeatCount = repeat.coerceAtLeast(0),
            randomDelayMinMs = rMin.coerceAtLeast(0L),
            randomDelayMaxMs = rMax.coerceAtLeast(0L)
        )
    }

    private fun readConfig(): ClickSequenceConfig? {
        if (points.isEmpty()) {
            Toast.makeText(this, R.string.toast_need_one_point, Toast.LENGTH_SHORT).show()
            return null
        }
        val global = binding.etDelay.text?.toString()?.toLongOrNull() ?: 1000L
        val rMin = binding.etRandomMin.text?.toString()?.toLongOrNull() ?: 0L
        val rMax = binding.etRandomMax.text?.toString()?.toLongOrNull() ?: 0L
        val repeat = binding.etRepeat.text?.toString()?.toIntOrNull() ?: 0

        if (rMin > 0L && rMax > 0L && rMin >= rMax) {
            Toast.makeText(this, "랜덤 딜레이 최대값이 최솟값보다 커야 합니다.", Toast.LENGTH_SHORT).show()
            return null
        }
        if (global < 100) {
            Toast.makeText(this, "딜레이는 최소 100ms 이상이어야 합니다.", Toast.LENGTH_SHORT).show()
            return null
        }
        for (p in points) {
            val d = if (p.delayAfterMs >= 0) p.delayAfterMs else global
            if (d < 100) {
                Toast.makeText(this, "각 포인트 딜레이(또는 공통)는 100ms 이상이어야 합니다.", Toast.LENGTH_SHORT).show()
                return null
            }
            if (p.gesture == GestureType.LONG_PRESS &&
                (p.longPressDurationMs < 100 || p.longPressDurationMs > 60_000)
            ) {
                Toast.makeText(this, "롱 프레스 시간이 올바른지 확인하세요.", Toast.LENGTH_SHORT).show()
                return null
            }
            if (p.gesture == GestureType.SWIPE &&
                (p.swipeDurationMs < 50 || p.swipeDurationMs > 60_000)
            ) {
                Toast.makeText(this, "스와이프 지속 시간이 올바른지 확인하세요.", Toast.LENGTH_SHORT).show()
                return null
            }
        }

        return ClickSequenceConfig(
            points = points.toList(),
            globalDelayMs = global,
            repeatCount = repeat.coerceAtLeast(0),
            randomDelayMinMs = rMin.coerceAtLeast(0L),
            randomDelayMaxMs = rMax.coerceAtLeast(0L)
        )
    }

    private fun startAutoClick(config: ClickSequenceConfig) {
        val json = config.toJsonString()
        val serviceIntent = Intent(this, OverlayService::class.java).apply {
            putExtra(OverlayService.EXTRA_SEQUENCE_JSON, json)
        }
        startForegroundService(serviceIntent)

        sendBroadcast(Intent(AutoClickAccessibilityService.ACTION_START).apply {
            setPackage(packageName)
            putExtra(AutoClickAccessibilityService.EXTRA_SEQUENCE_JSON, json)
        })

        binding.tvStatus.text = getString(R.string.status_running)
        binding.tvClickCount.text = getString(R.string.click_count_fmt, 0)
    }

    private fun stopAutoClick() {
        sendBroadcast(Intent(AutoClickAccessibilityService.ACTION_STOP).apply {
            setPackage(packageName)
        })
        stopService(Intent(this, OverlayService::class.java))
        binding.tvStatus.text = getString(R.string.status_idle)
    }

    private fun setRunningState(running: Boolean) {
        isRunning = running
        binding.btnStart.isEnabled = !running
        binding.btnStop.isEnabled = running
        binding.tvStatus.text = getString(
            if (running) R.string.status_running else R.string.status_idle
        )
    }

    private fun updatePermissionStatus() {
        val a11yOn = isAccessibilityEnabled()
        binding.tvAccessibilityStatus.text = getString(
            if (a11yOn) R.string.status_accessibility_on else R.string.status_accessibility_off
        )
        binding.tvAccessibilityStatus.setTextColor(
            getColor(if (a11yOn) android.R.color.holo_green_light else android.R.color.holo_orange_light)
        )
        binding.btnEnableAccessibility.isEnabled = !a11yOn

        val overlayOn = Settings.canDrawOverlays(this)
        binding.tvOverlayStatus.text = getString(
            if (overlayOn) R.string.status_overlay_on else R.string.status_overlay_off
        )
        binding.tvOverlayStatus.setTextColor(
            getColor(if (overlayOn) android.R.color.holo_green_light else android.R.color.holo_orange_light)
        )
        binding.btnEnableOverlay.isEnabled = !overlayOn
    }

    private fun isAccessibilityEnabled(): Boolean {
        val am = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        val services = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        return services.any { it.resolveInfo.serviceInfo.packageName == packageName }
    }

    private fun registerStatusReceiver() {
        val filter = IntentFilter().apply {
            addAction(AutoClickAccessibilityService.ACTION_CLICK_COUNT)
            addAction(AutoClickAccessibilityService.ACTION_STOP)
            addAction(AutoClickAccessibilityService.ACTION_STARTED)
            addAction(AutoClickAccessibilityService.ACTION_AUTO_PROFILE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(statusReceiver, filter)
        }
    }
}
