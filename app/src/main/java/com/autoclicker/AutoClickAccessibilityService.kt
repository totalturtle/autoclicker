package com.autoclicker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.os.SystemClock
import android.view.Display
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import kotlin.coroutines.resume
import kotlinx.coroutines.*

/**
 * 접근성 제스처로 탭/롱프레스/스와이프 실행.
 * 브로드캐스트·Quick Tile·볼륨(설정 시)으로 시작/정지.
 */
class AutoClickAccessibilityService : AccessibilityService() {

    companion object {
        const val ACTION_START = "com.autoclicker.ACTION_START"
        const val ACTION_STOP = "com.autoclicker.ACTION_STOP"
        const val ACTION_TOGGLE = "com.autoclicker.ACTION_TOGGLE"
        const val ACTION_CLICK_COUNT = "com.autoclicker.ACTION_CLICK_COUNT"
        const val ACTION_STARTED = "com.autoclicker.ACTION_STARTED"
        const val ACTION_AUTO_PROFILE = "com.autoclicker.ACTION_AUTO_PROFILE"
        const val ACTION_REQUEST_COLOR_SAMPLE = "com.autoclicker.REQUEST_COLOR_SAMPLE"
        const val ACTION_COLOR_SAMPLED = "com.autoclicker.COLOR_SAMPLED"

        const val EXTRA_SEQUENCE_JSON = "extra_sequence_json"
        const val EXTRA_COUNT = "extra_count"
        const val EXTRA_PROFILE_NAME = "extra_profile_name"
        const val EXTRA_SAMPLE_X = "sample_x"
        const val EXTRA_SAMPLE_Y = "sample_y"
        const val EXTRA_SAMPLE_TARGET = "sample_target"
        const val EXTRA_SAMPLED_COLOR = "sampled_color"
        const val EXTRA_MOVE_TO_DROPPER = "move_to_dropper"

        var instance: AutoClickAccessibilityService? = null
            private set

        val isRunning: Boolean
            get() = instance?.isClickJobActive == true
    }

    val isClickJobActive: Boolean get() = clickJob?.isActive == true

    private var lastDetectedPackage = ""

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var clickJob: Job? = null
    private var lastVolumeToggleUptime = 0L

    private val commandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_START -> {
                    val json = intent.getStringExtra(EXTRA_SEQUENCE_JSON)
                    val config = ClickSequenceConfig.fromJsonString(json) ?: return
                    startClicking(config)
                }
                ACTION_STOP -> stopClicking()
                ACTION_TOGGLE -> toggleRun()
                ACTION_REQUEST_COLOR_SAMPLE -> {
                    val x = intent.getIntExtra(EXTRA_SAMPLE_X, 0)
                    val y = intent.getIntExtra(EXTRA_SAMPLE_Y, 0)
                    val target = intent.getStringExtra(EXTRA_SAMPLE_TARGET) ?: return
                    val move = intent.getBooleanExtra(EXTRA_MOVE_TO_DROPPER, false)
                    serviceScope.launch { sampleColor(x, y, target, move) }
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this

        val filter = IntentFilter().apply {
            addAction(ACTION_START)
            addAction(ACTION_STOP)
            addAction(ACTION_TOGGLE)
            addAction(ACTION_REQUEST_COLOR_SAMPLE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(commandReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(commandReceiver, filter)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (event.eventType != android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName || pkg == lastDetectedPackage) return
        lastDetectedPackage = pkg

        val profile = ProfileManager.findByApp(this, pkg) ?: return
        SequencePrefs.save(this, profile.config)
        sendBroadcast(Intent(ACTION_AUTO_PROFILE).apply {
            setPackage(packageName)
            putExtra(EXTRA_PROFILE_NAME, profile.name)
        })
    }

    override fun onInterrupt() {
        stopClicking()
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) {
            return super.onKeyEvent(event)
        }
        if (!SequencePrefs.isVolumeHotkeyEnabled(this)) {
            return super.onKeyEvent(event)
        }
        if (event.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) {
            return super.onKeyEvent(event)
        }
        val now = SystemClock.uptimeMillis()
        if (now - lastVolumeToggleUptime < 450L) {
            return true
        }
        lastVolumeToggleUptime = now
        toggleRun()
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        stopClicking()
        runCatching { unregisterReceiver(commandReceiver) }
        serviceScope.cancel()
    }

    private fun toggleRun() {
        if (clickJob?.isActive == true) {
            stopClicking()
            broadcastStop()
        } else {
            val cfg = SequencePrefs.load(this) ?: return
            if (cfg.points.isEmpty()) return
            startClicking(cfg)
        }
    }

    private fun startClicking(config: ClickSequenceConfig) {
        stopClicking()
        if (config.points.isEmpty()) return

        broadcastStarted()

        clickJob = serviceScope.launch {
            delay(300L) // 재생 버튼 터치가 완전히 처리된 후 첫 제스처 시작
            SessionLogger.startSession()
            val infinite = config.repeatCount == 0
            var round = 0
            var totalClicks = 0

            try {
                while (infinite || round < config.repeatCount) {
                    for (point in config.points) {
                        val repeatTimes = point.pointRepeatCount.coerceAtLeast(1)
                        for (r in 0 until repeatTimes) {
                            ensureActive()
                            val clicked = performPoint(point)
                            if (clicked) {
                                totalClicks++
                                broadcastCount(totalClicks)
                                SessionLogger.logClick(point.label, point.x, point.y)
                            } else {
                                SessionLogger.logSkip(point.label, point.x, point.y)
                            }
                            delay(config.delayAfter(point))
                        }
                    }
                    round++
                }
                if (!infinite) broadcastStop()
            } finally {
                SessionLogger.endSession()
            }
        }
    }

    /** OverlayService 등 외부에서 즉시 정지할 때 사용 */
    fun requestStop() {
        stopClicking()
        broadcastStop()
    }

    private fun stopClicking() {
        clickJob?.cancel()
        clickJob = null
    }

    private suspend fun performPoint(p: ClickPoint): Boolean {
        val trigger = p.trigger
        if (trigger != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val effectiveTrigger = if (trigger.usePointCoords) trigger.copy(checkX = p.x, checkY = p.y) else trigger
            val matched = checkColorTrigger(effectiveTrigger)
            if (!matched) {
                when (trigger.action) {
                    TriggerAction.SKIP -> return false
                    TriggerAction.WAIT_RETRY -> {
                        var found = false
                        for (i in 0 until effectiveTrigger.maxRetries) {
                            delay(effectiveTrigger.retryDelayMs)
                            if (checkColorTrigger(effectiveTrigger)) { found = true; break }
                        }
                        if (!found) return false
                    }
                }
            }
        }
        when (p.gesture) {
            GestureType.TAP -> dispatchStroke(p.x, p.y, p.x, p.y, 50L)
            GestureType.LONG_PRESS -> dispatchStroke(p.x, p.y, p.x, p.y, p.longPressDurationMs.coerceIn(100L, 60_000L))
            GestureType.SWIPE -> {
                val dur = p.swipeDurationMs.coerceIn(50L, 60_000L)
                if (p.swipeExtraPoints.isEmpty()) {
                    dispatchStroke(p.x, p.y, p.endX, p.endY, dur)
                } else {
                    val waypoints = buildList {
                        add(p.x to p.y)
                        add(p.endX to p.endY)
                        p.swipeExtraPoints.forEach { add(it.x to it.y) }
                    }
                    dispatchPathSwipe(waypoints, dur)
                }
            }
        }
        return true
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun checkColorTrigger(trigger: TriggerCondition): Boolean =
        suspendCancellableCoroutine { cont ->
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                ContextCompat.getMainExecutor(this@AutoClickAccessibilityService),
                object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        runCatching {
                            val hwBmp = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                            try {
                                val swBmp = hwBmp?.copy(Bitmap.Config.ARGB_8888, false)
                                try {
                                    val x = trigger.checkX.coerceIn(0, (swBmp?.width ?: 1) - 1)
                                    val y = trigger.checkY.coerceIn(0, (swBmp?.height ?: 1) - 1)
                                    val pixel = swBmp?.getPixel(x, y) ?: android.graphics.Color.TRANSPARENT
                                    TriggerCondition.colorMatches(pixel, trigger.targetColor, trigger.tolerance)
                                } finally {
                                    swBmp?.recycle()
                                }
                            } finally {
                                hwBmp?.recycle()
                                result.hardwareBuffer.close()
                            }
                        }.fold(
                            onSuccess = { match -> if (cont.isActive) cont.resume(match) },
                            onFailure = { if (cont.isActive) cont.resume(true) }
                        )
                    }
                    override fun onFailure(errorCode: Int) {
                        if (cont.isActive) cont.resume(true)
                    }
                }
            )
        }

    private suspend fun sampleColor(x: Int, y: Int, target: String, moveToDropper: Boolean = false) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            // API 30 미만은 takeScreenshot 미지원 — 앱으로 복귀만
            withContext(Dispatchers.Main) {
                sendBroadcast(Intent(ACTION_COLOR_SAMPLED).apply {
                    setPackage(packageName)
                    putExtra(EXTRA_SAMPLE_X, x)
                    putExtra(EXTRA_SAMPLE_Y, y)
                    putExtra(EXTRA_SAMPLED_COLOR, Int.MIN_VALUE)
                    putExtra(EXTRA_SAMPLE_TARGET, target)
                })
            }
            return
        }
        @Suppress("NewApi")
        suspendCancellableCoroutine<Unit> { cont ->
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                ContextCompat.getMainExecutor(this@AutoClickAccessibilityService),
                object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        val color = runCatching {
                            val hwBmp = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                            val swBmp = hwBmp?.copy(Bitmap.Config.ARGB_8888, false)
                            hwBmp?.recycle()
                            result.hardwareBuffer.close()
                            val sx = x.coerceIn(0, (swBmp?.width ?: 1) - 1)
                            val sy = y.coerceIn(0, (swBmp?.height ?: 1) - 1)
                            val pixel = swBmp?.getPixel(sx, sy) ?: android.graphics.Color.BLACK
                            swBmp?.recycle()
                            pixel
                        }.getOrDefault(android.graphics.Color.BLACK)
                        sendBroadcast(Intent(ACTION_COLOR_SAMPLED).apply {
                            setPackage(packageName)
                            putExtra(EXTRA_SAMPLE_X, x)
                            putExtra(EXTRA_SAMPLE_Y, y)
                            putExtra(EXTRA_SAMPLED_COLOR, color)
                            putExtra(EXTRA_SAMPLE_TARGET, target)
                            putExtra(EXTRA_MOVE_TO_DROPPER, moveToDropper)
                        })
                        if (cont.isActive) cont.resume(Unit)
                    }
                    override fun onFailure(errorCode: Int) {
                        sendBroadcast(Intent(ACTION_COLOR_SAMPLED).apply {
                            setPackage(packageName)
                            putExtra(EXTRA_SAMPLE_X, x)
                            putExtra(EXTRA_SAMPLE_Y, y)
                            putExtra(EXTRA_SAMPLED_COLOR, Int.MIN_VALUE)
                            putExtra(EXTRA_SAMPLE_TARGET, target)
                            putExtra(EXTRA_MOVE_TO_DROPPER, false)
                        })
                        if (cont.isActive) cont.resume(Unit)
                    }
                }
            )
        }
    }

    private suspend fun dispatchPathSwipe(points: List<Pair<Int, Int>>, durationMs: Long) {
        if (points.size < 2) return
        val path = Path().apply {
            moveTo(points[0].first.toFloat(), points[0].second.toFloat())
            for (i in 1 until points.size) lineTo(points[i].first.toFloat(), points[i].second.toFloat())
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs.coerceAtLeast(1L))
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        suspendCancellableCoroutine<Unit> { cont ->
            val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(g: GestureDescription) { if (cont.isActive) cont.resume(Unit) }
                override fun onCancelled(g: GestureDescription) { if (cont.isActive) cont.resume(Unit) }
            }, null)
            if (!dispatched && cont.isActive) cont.resume(Unit)
        }
    }

    private suspend fun dispatchStroke(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long) {
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        val dur = durationMs.coerceAtLeast(1L)
        val stroke = GestureDescription.StrokeDescription(path, 0L, dur)
        val gesture = GestureDescription.Builder()
            .addStroke(stroke)
            .build()
        suspendCancellableCoroutine<Unit> { cont ->
            val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription) {
                    if (cont.isActive) cont.resume(Unit)
                }
                override fun onCancelled(gestureDescription: GestureDescription) {
                    if (cont.isActive) cont.resume(Unit)
                }
            }, null)
            if (!dispatched && cont.isActive) cont.resume(Unit)
        }
    }

    private fun broadcastStarted() {
        sendBroadcast(Intent(ACTION_STARTED).apply { setPackage(packageName) })
    }

    private fun broadcastCount(count: Int) {
        sendBroadcast(Intent(ACTION_CLICK_COUNT).apply {
            putExtra(EXTRA_COUNT, count)
            setPackage(packageName)
        })
    }

    private fun broadcastStop() {
        sendBroadcast(Intent(ACTION_STOP).apply { setPackage(packageName) })
    }
}
