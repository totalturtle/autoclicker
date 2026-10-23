package com.realbtob.autoclicker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.realbtob.autoclicker.databinding.ActivitySimpleModeBinding

/**
 * 간단 모드 화면.
 *
 * 누를 위치 · 간격 · 반복만 정하고 바로 시작한다. 클릭 엔진과 저장 포맷은
 * 모든 기능 화면(MainActivity)과 똑같이 [SequencePrefs]를 쓰므로,
 * 여기서 만든 설정을 모든 기능 화면에서 그대로 열어 다듬을 수 있다.
 */
class SimpleModeActivity : AppCompatActivity() {

    companion object {
        /** 좌표 피커가 고른 좌표 — [CoordPickerService]가 채워 넣는다 */
        @Volatile var pickedCoord: Pair<Int, Int>? = null
    }

    private lateinit var binding: ActivitySimpleModeBinding

    private val points = mutableListOf<ClickPoint>()
    private var multiMode = false
    private var repeatForever = true
    private var isRunning = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                AutoClickAccessibilityService.ACTION_STARTED -> setRunningState(true)
                AutoClickAccessibilityService.ACTION_STOP -> setRunningState(false)
                RecordingService.ACTION_RECORDING_DONE -> {
                    val json = intent.getStringExtra(RecordingService.EXTRA_RECORDED_POINTS) ?: return
                    onRecordingDone(json)
                }
                SequencePrefs.ACTION_POINTS_CHANGED -> loadFromPrefs()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppPreferences.applyTheme(this)
        binding = ActivitySimpleModeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        multiMode = AppPreferences.isSimpleMulti(this)
        setupPointList()
        setupButtons()
        loadFromPrefs()
        registerBroadcasts()
        ensureOverlayServiceRunning()
    }

    override fun onResume() {
        super.onResume()
        consumePickedCoord()
        updatePermissionBanner()
        setRunningState(AutoClickAccessibilityService.isRunning)
        if (!isRunning && points.isNotEmpty()) showMarkers()
    }

    override fun onPause() {
        super.onPause()
        persist()
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { unregisterReceiver(receiver) }
    }

    // ── 화면 구성 ────────────────────────────────────────────────────────

    private fun setupButtons() {
        binding.btnModeSingle.setOnClickListener { setMultiMode(false) }
        binding.btnModeMulti.setOnClickListener { setMultiMode(true) }

        binding.cardPickSingle.setOnClickListener { pickCoordinate() }
        binding.btnAddPoint.setOnClickListener {
            if (!PremiumManager.isPremium && points.size >= PremiumManager.FREE_POINT_LIMIT) {
                Toast.makeText(this, R.string.premium_point_limit, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            pickCoordinate()
        }

        binding.btnRepeatForever.setOnClickListener { setRepeatForever(true) }
        binding.etRepeatCount.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) setRepeatForever(false)
        }
        binding.etRepeatCount.setOnClickListener { setRepeatForever(false) }

        binding.btnSimpleSave.setOnClickListener { showSaveDialog() }
        binding.btnSimpleLoad.setOnClickListener { showLoadDialog() }
        binding.btnSimpleRecord.setOnClickListener { startRecording() }
        binding.btnSimpleStart.setOnClickListener { if (isRunning) stopClicking() else startClicking() }

        binding.btnAdvancedMode.setOnClickListener { goAdvanced() }
        binding.btnSimpleSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.tvSimplePermission.setOnClickListener { openMissingPermission() }
    }

    private fun setupPointList() {
        binding.rvSimplePoints.layoutManager = LinearLayoutManager(this)
        binding.rvSimplePoints.adapter = PointAdapter()
    }

    private fun setMultiMode(multi: Boolean) {
        multiMode = multi
        AppPreferences.setSimpleMulti(this, multi)
        // 한 곳 반복으로 돌아오면 첫 위치만 남긴다
        if (!multi && points.size > 1) {
            val first = points.first()
            points.clear()
            points.add(first)
            persist()
        }
        refreshUi()
    }

    private fun setRepeatForever(forever: Boolean) {
        repeatForever = forever
        refreshRepeatUi()
    }

    private fun refreshUi() {
        // 모드 버튼
        styleAsPill(binding.btnModeSingle, selected = !multiMode)
        styleAsPill(binding.btnModeMulti, selected = multiMode)

        binding.cardPickSingle.visibility = if (multiMode) View.GONE else View.VISIBLE
        binding.groupMulti.visibility = if (multiMode) View.VISIBLE else View.GONE

        val first = points.firstOrNull()
        if (first == null) {
            binding.tvSingleTitle.setText(R.string.simple_pick_title)
            binding.tvSingleHint.setText(R.string.simple_pick_hint)
        } else {
            binding.tvSingleTitle.text = getString(R.string.simple_picked_fmt, first.x, first.y)
            binding.tvSingleHint.setText(R.string.simple_picked_hint)
        }

        binding.rvSimplePoints.adapter?.notifyDataSetChanged()
        refreshRepeatUi()
    }

    private fun refreshRepeatUi() {
        styleAsPill(binding.btnRepeatForever, selected = repeatForever)
        binding.etRepeatCount.setBackgroundResource(
            if (repeatForever) R.drawable.bg_simple_outline else R.drawable.bg_simple_field_focus
        )
        binding.etRepeatCount.setTextColor(
            ContextCompat.getColor(this, if (repeatForever) R.color.text_secondary else R.color.simple_accent)
        )
        binding.tvRepeatHint.text = if (repeatForever) {
            getString(R.string.simple_repeat_forever_hint)
        } else {
            getString(R.string.simple_repeat_count_hint_fmt, readRepeatCount())
        }
    }

    private fun styleAsPill(view: TextView, selected: Boolean) {
        view.setBackgroundResource(if (selected) R.drawable.bg_simple_pill else R.drawable.bg_simple_outline)
        view.setTextColor(
            ContextCompat.getColor(this, if (selected) R.color.simple_on_accent else R.color.text_secondary)
        )
    }

    private fun setRunningState(running: Boolean) {
        isRunning = running
        binding.btnSimpleStart.setBackgroundResource(
            if (running) R.drawable.bg_simple_danger else R.drawable.bg_simple_primary
        )
        binding.btnSimpleStart.setText(if (running) R.string.simple_running else R.string.simple_start)
        binding.btnSimpleStart.textSize = if (running) 16f else 20f
    }

    // ── 좌표 지정 ────────────────────────────────────────────────────────

    /**
     * 위치 지정은 "쓰려는 앱 위에서" 해야 정확하다.
     * 그래서 이 앱을 내리고, 화면 위에 남는 + 버튼으로 지정하도록 안내한다.
     */
    private fun pickCoordinate() {
        if (!Settings.canDrawOverlays(this)) {
            openMissingPermission()
            return
        }
        ensureOverlayServiceRunning()
        Toast.makeText(this, R.string.simple_pick_over_app, Toast.LENGTH_LONG).show()
        moveTaskToBack(true)
    }

    private fun consumePickedCoord() {
        val picked = pickedCoord ?: return
        pickedCoord = null
        val point = ClickPoint(x = picked.first, y = picked.second, gesture = GestureType.TAP)
        if (multiMode) points.add(point) else {
            points.clear()
            points.add(point)
        }
        persist()
        refreshUi()
        showMarkers()
    }

    /**
     * 모든 기능 화면으로 넘어간다.
     * 간단 모드에서 만든 위치는 지우고 간다 — 저장해 둔 설정은 그대로 남는다.
     */
    private fun goAdvanced() {
        points.clear()
        SequencePrefs.save(this, ClickSequenceConfig(emptyList(), readIntervalMs().coerceAtLeast(100L), 0))
        showMarkers()
        AppPreferences.setSimpleModeStart(this, false)
        Toast.makeText(this, R.string.simple_clear_on_switch, Toast.LENGTH_SHORT).show()
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    /** 지금 설정을 이름 붙여 저장한다. 같은 이름이면 덮어쓸지 묻는다. */
    private fun showSaveDialog() {
        if (points.isEmpty()) {
            Toast.makeText(this, R.string.simple_need_point, Toast.LENGTH_SHORT).show()
            return
        }
        val all = ProfileManager.loadSimple(this)
        val current = all.firstOrNull { it.id == ProfileManager.getActiveId(this) }
        val input = android.widget.EditText(this).apply {
            setHint(R.string.hint_profile_name)
            setSingleLine()
            setPadding(48, 32, 48, 16)
            current?.let { setText(it.name) }
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.simple_save_title)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.btn_save) { _, _ ->
                val name = input.text?.toString()?.trim()
                if (name.isNullOrEmpty()) {
                    Toast.makeText(this, R.string.toast_enter_name, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val existing = all.firstOrNull { it.name == name }
                if (existing != null) confirmOverwrite(existing) else saveAsNew(name)
            }
            .show()
    }

    private fun confirmOverwrite(target: Profile) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_overwrite_profile_title)
            .setMessage(getString(R.string.dialog_overwrite_profile_msg, target.name))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.btn_save) { _, _ ->
                ProfileManager.save(this, target.copy(config = buildConfig(), simple = true))
                ProfileManager.setActiveId(this, target.id)
                ProfileManager.notifyChanged(this)
                Toast.makeText(this, getString(R.string.toast_profile_overwritten, target.name), Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun saveAsNew(name: String) {
        if (!PremiumManager.isPremium && ProfileManager.loadSimple(this).size >= 2) {
            Toast.makeText(this, R.string.premium_profile_limit, Toast.LENGTH_LONG).show()
            return
        }
        val created = Profile(name = name, config = buildConfig(), simple = true)
        ProfileManager.save(this, created)
        ProfileManager.setActiveId(this, created.id)
        ProfileManager.notifyChanged(this)
        Toast.makeText(this, getString(R.string.toast_profile_saved, name), Toast.LENGTH_SHORT).show()
    }

    /** 저장해 둔 설정을 골라 화면에 채운다. */
    private fun showLoadDialog() {
        val profiles = ProfileManager.loadSimple(this)
        if (profiles.isEmpty()) {
            Toast.makeText(this, R.string.simple_load_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val names = profiles.map { it.name }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.simple_load_title)
            .setItems(names) { _, which ->
                val profile = profiles[which]
                applyConfig(profile.config)
                SequencePrefs.save(this, profile.config)
                ProfileManager.setActiveId(this, profile.id)
                AutoClickAccessibilityService.onUserLoadedProfile(profile)
                showMarkers()
                Toast.makeText(this, getString(R.string.simple_loaded_fmt, profile.name), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** 불러온 설정을 화면에 반영한다. */
    private fun applyConfig(cfg: ClickSequenceConfig) {
        points.clear()
        points.addAll(cfg.points)
        DelayUnit.fill(binding.etSimpleInterval, binding.spSimpleUnit, cfg.globalDelayMs)
        repeatForever = cfg.repeatCount <= 0
        if (!repeatForever) binding.etRepeatCount.setText(cfg.repeatCount.toString())
        multiMode = points.size > 1
        AppPreferences.setSimpleMulti(this, multiMode)
        refreshUi()
    }

    /** 지정한 위치를 화면에 동그라미로 보여 준다 */
    private fun showMarkers() {
        sendBroadcast(Intent(OverlayService.ACTION_SHOW_MARKERS).apply { setPackage(packageName) })
    }

    // ── 녹화 ────────────────────────────────────────────────────────────

    private fun startRecording() {
        if (!Settings.canDrawOverlays(this)) {
            openMissingPermission()
            return
        }
        if (!PremiumManager.isPremium) {
            Toast.makeText(
                this,
                getString(R.string.toast_record_free_notice_fmt, PremiumManager.FREE_POINT_LIMIT),
                Toast.LENGTH_LONG
            ).show()
        }
        startService(Intent(this, RecordingService::class.java))
        moveTaskToBack(true)
    }

    private fun onRecordingDone(json: String) {
        val arr = runCatching { org.json.JSONArray(json) }.getOrNull() ?: return
        val recorded = (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val gesture = runCatching { GestureType.valueOf(o.getString("gesture")) }.getOrDefault(GestureType.TAP)
            ClickPoint(
                x = o.getInt("x"),
                y = o.getInt("y"),
                gesture = gesture,
                endX = o.optInt("endX", o.getInt("x")),
                endY = o.optInt("endY", o.getInt("y")),
                longPressDurationMs = o.optLong("longPressDurationMs", 450L),
                swipeDurationMs = o.optLong("swipeDurationMs", 350L),
                delayAfterMs = o.optLong("delayAfterMs", -1L)
            )
        }
        if (recorded.isEmpty()) return
        val accepted = if (PremiumManager.isPremium) recorded
                       else recorded.take(PremiumManager.FREE_POINT_LIMIT)
        points.clear()
        points.addAll(accepted)
        if (points.size > 1 && !multiMode) setMultiMode(true)
        persist()
        refreshUi()
        showMarkers()
        if (accepted.size < recorded.size) {
            Toast.makeText(
                this,
                getString(R.string.toast_record_limited_fmt, accepted.size, PremiumManager.FREE_POINT_LIMIT),
                Toast.LENGTH_LONG
            ).show()
        } else {
            Toast.makeText(this, "${accepted.size}개 동작을 녹화했습니다.", Toast.LENGTH_SHORT).show()
        }
    }

    // ── 시작 / 정지 ──────────────────────────────────────────────────────

    private fun startClicking() {
        if (points.isEmpty()) {
            Toast.makeText(this, R.string.simple_need_point, Toast.LENGTH_SHORT).show()
            return
        }
        if (AutoClickAccessibilityService.instance == null) {
            openAccessibilitySettings()
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            openMissingPermission()
            return
        }
        val interval = readIntervalMs()
        if (interval < 100L) {
            Toast.makeText(this, R.string.simple_interval_min, Toast.LENGTH_SHORT).show()
            return
        }
        val config = buildConfig()
        SequencePrefs.save(this, config)
        val json = config.toJsonString()
        ContextCompat.startForegroundService(
            this,
            Intent(this, OverlayService::class.java).apply {
                putExtra(OverlayService.EXTRA_SEQUENCE_JSON, json)
            }
        )
        sendBroadcast(Intent(AutoClickAccessibilityService.ACTION_START).apply {
            setPackage(packageName)
            putExtra(AutoClickAccessibilityService.EXTRA_SEQUENCE_JSON, json)
        })
        setRunningState(true)
        Toast.makeText(this, R.string.simple_started, Toast.LENGTH_SHORT).show()
        moveTaskToBack(true)
    }

    private fun stopClicking() {
        AutoClickAccessibilityService.instance?.requestStop()
            ?: sendBroadcast(Intent(AutoClickAccessibilityService.ACTION_STOP).apply {
                setPackage(packageName)
            })
        setRunningState(false)
    }

    // ── 설정 읽기/쓰기 ───────────────────────────────────────────────────

    private fun readIntervalMs(): Long =
        DelayUnit.readMs(binding.etSimpleInterval, binding.spSimpleUnit, 1000L)

    private fun readRepeatCount(): Int =
        binding.etRepeatCount.text?.toString()?.toIntOrNull()?.coerceAtLeast(1) ?: 1

    private fun buildConfig(): ClickSequenceConfig = ClickSequenceConfig(
        points = points.toList(),
        globalDelayMs = readIntervalMs().coerceAtLeast(100L),
        repeatCount = if (repeatForever) 0 else readRepeatCount(),
        repeatMode = RepeatMode.COUNT,
        repeatDurationMs = 0L
    )

    /** 위치를 모두 지운 경우에도 저장해야 화면의 마커가 같이 사라진다 */
    private fun persist() {
        SequencePrefs.save(this, buildConfig())
    }

    private fun loadFromPrefs() {
        val cfg = SequencePrefs.load(this)
        points.clear()
        if (cfg != null) {
            points.addAll(cfg.points)
            DelayUnit.fill(binding.etSimpleInterval, binding.spSimpleUnit, cfg.globalDelayMs)
            repeatForever = cfg.repeatCount <= 0
            if (!repeatForever) binding.etRepeatCount.setText(cfg.repeatCount.toString())
            if (points.size > 1) multiMode = true
        }
        refreshUi()
    }

    // ── 권한 ────────────────────────────────────────────────────────────

    private fun updatePermissionBanner() {
        val missing = AutoClickAccessibilityService.instance == null || !Settings.canDrawOverlays(this)
        binding.tvSimplePermission.visibility = if (missing) View.VISIBLE else View.GONE
    }

    private fun openMissingPermission() {
        if (!Settings.canDrawOverlays(this)) {
            runCatching {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:$packageName")
                    )
                )
            }
            return
        }
        openAccessibilitySettings()
    }

    private fun openAccessibilitySettings() {
        Toast.makeText(this, R.string.toast_enable_accessibility, Toast.LENGTH_LONG).show()
        runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
    }

    private fun ensureOverlayServiceRunning() {
        if (!Settings.canDrawOverlays(this)) return
        ContextCompat.startForegroundService(this, Intent(this, OverlayService::class.java))
    }

    private fun registerBroadcasts() {
        val filter = IntentFilter().apply {
            addAction(AutoClickAccessibilityService.ACTION_STARTED)
            addAction(AutoClickAccessibilityService.ACTION_STOP)
            addAction(RecordingService.ACTION_RECORDING_DONE)
            addAction(SequencePrefs.ACTION_POINTS_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
    }

    // ── 위치 목록 ────────────────────────────────────────────────────────

    private inner class PointAdapter : RecyclerView.Adapter<PointAdapter.VH>() {

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val index: TextView = view.findViewById(R.id.tvSimpleIndex)
            val coord: TextView = view.findViewById(R.id.tvSimpleCoord)
            val gesture: TextView = view.findViewById(R.id.tvSimpleGesture)
            val remove: ImageView = view.findViewById(R.id.btnSimpleRemove)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(layoutInflater.inflate(R.layout.item_simple_point, parent, false))

        override fun getItemCount(): Int = points.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val point = points[position]
            holder.index.text = (position + 1).toString()
            holder.coord.text = getString(R.string.simple_picked_fmt, point.x, point.y)
            holder.gesture.setText(
                when (point.gesture) {
                    GestureType.LONG_PRESS -> R.string.gesture_long_simple
                    GestureType.SWIPE -> R.string.gesture_swipe_simple
                    else -> R.string.gesture_tap_simple
                }
            )
            holder.remove.setOnClickListener {
                val index = holder.bindingAdapterPosition
                if (index !in points.indices) return@setOnClickListener
                points.removeAt(index)
                persist()
                refreshUi()
                showMarkers()
            }
        }
    }
}
