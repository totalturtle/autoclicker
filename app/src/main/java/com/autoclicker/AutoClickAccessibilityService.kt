package com.realbtob.autoclicker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.widget.Toast
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.os.SystemClock
import android.view.Display
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
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
        const val ACTION_REQUEST_REGION_CAPTURE = "com.autoclicker.REQUEST_REGION_CAPTURE"
        const val ACTION_REGION_CAPTURED = "com.autoclicker.REGION_CAPTURED"

        const val EXTRA_SEQUENCE_JSON = "extra_sequence_json"
        const val EXTRA_COUNT = "extra_count"
        const val EXTRA_PROFILE_NAME = "extra_profile_name"
        const val EXTRA_SAMPLE_X = "sample_x"
        const val EXTRA_SAMPLE_Y = "sample_y"
        const val EXTRA_SAMPLE_TARGET = "sample_target"
        const val EXTRA_SAMPLED_COLOR = "sampled_color"
        const val EXTRA_MOVE_TO_DROPPER = "move_to_dropper"
        const val EXTRA_REGION_X = "region_x"
        const val EXTRA_REGION_Y = "region_y"
        const val EXTRA_REGION_W = "region_w"
        const val EXTRA_REGION_H = "region_h"
        const val EXTRA_REGION_TARGET = "region_target"
        const val EXTRA_REGION_PIXELS = "region_pixels"

        private const val TAG_AUTO = "AutoProfile"

        /**
         * 사용자가 직접 설정을 바꾼 앱으로 돌아왔을 때, 이 시간 안이면 "잠깐 나갔다 온 것"으로 보고
         * 연동 프로필을 다시 적용하지 않는다. 이보다 오래 다른 일을 하다 돌아오면 새 세션으로 본다.
         */
        private const val RESUME_GRACE_MS = 60_000L

        /** 사용자가 직접 설정을 바꾼 세션은 이만큼 더 오래 보호한다 */
        private const val USER_CHOICE_GRACE_MS = 30 * 60_000L

        private const val SYSTEM_UI_PACKAGE = "com.android.systemui"

        var instance: AutoClickAccessibilityService? = null
            private set

        /**
         * 사용자가 직접 설정을 바꿨을 때(프로필 수동 로드, 포인트 추가·수정·삭제) 호출한다.
         * 지금 쓰고 있는 앱에 대해서는 연동 프로필 자동 적용을 멈춘다.
         */
        fun onUserChangedSequence() {
            instance?.pauseAutoLinkForCurrentApp()
        }

        /**
         * 사용자가 프로필을 직접 불러왔을 때 호출한다.
         * 지금 쓰는 앱에 연동된 바로 그 프로필을 고른 경우에는 자동 적용을 되살리고,
         * 다른 프로필을 고른 경우에는 멈춘다.
         */
        fun onUserLoadedProfile(profile: Profile) {
            val inst = instance ?: return
            if (profile.linkedAppPackage.isNotEmpty() && profile.linkedAppPackage == inst.sessionPkg) {
                inst.userTouchedSession = false   // 연동 프로필을 직접 고름 → 보호할 것이 없다
            } else {
                inst.pauseAutoLinkForCurrentApp()
            }
        }

        val isRunning: Boolean
            get() = instance?.isClickJobActive == true
    }

    val isClickJobActive: Boolean get() = clickJob?.isActive == true

    /** 자동 연동을 마지막으로 적용한(또는 적용을 시도한) 포그라운드 앱 */
    @Volatile private var lastAutoPackage = ""

    /** 지금 "사용 중"으로 보고 있는 앱 — 이 앱에 대해서는 연동 프로필을 이미 적용했다 */
    @Volatile private var sessionPkg = ""

    /** 그 앱을 떠난 시각 — 잠깐 나갔다 온 것인지, 한참 뒤 다시 실행한 것인지 가른다 */
    @Volatile private var sessionLeftAtMs = 0L

    /** 이번 세션에서 사용자가 직접 프로필을 고르거나 포인트를 바꿨는가 */
    @Volatile private var userTouchedSession = false

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var clickJob: Job? = null
    private var pollingJob: Job? = null

    private val commandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_START -> {
                    val config = SequencePrefs.load(this@AutoClickAccessibilityService) ?: return
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
                ACTION_REQUEST_REGION_CAPTURE -> {
                    val rx = intent.getIntExtra(EXTRA_REGION_X, 0)
                    val ry = intent.getIntExtra(EXTRA_REGION_Y, 0)
                    val rw = intent.getIntExtra(EXTRA_REGION_W, 0)
                    val rh = intent.getIntExtra(EXTRA_REGION_H, 0)
                    val target = intent.getStringExtra(EXTRA_REGION_TARGET) ?: return
                    serviceScope.launch { captureRegion(rx, ry, rw, rh, target) }
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        startAppPolling()

        val filter = IntentFilter().apply {
            addAction(ACTION_START)
            addAction(ACTION_STOP)
            addAction(ACTION_TOGGLE)
            addAction(ACTION_REQUEST_COLOR_SAMPLE)
            addAction(ACTION_REQUEST_REGION_CAPTURE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(commandReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(commandReceiver, filter)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg: String = when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val p = event.packageName?.toString() ?: return
                // 키보드·시스템 UI·오버레이가 뜨는 것은 앱 전환이 아니다.
                // (키보드를 앱 전환으로 보면 입력 후 닫힐 때 연동 프로필이 다시 적용된다)
                if (!isAppWindow(event.windowId, p)) return
                p
            }
            AccessibilityEvent.TYPE_WINDOWS_CHANGED ->
                windows?.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                    ?.maxByOrNull { it.layer }
                    ?.root?.packageName?.toString() ?: return
            else -> return
        }
        applyAutoProfile(pkg)
    }

    /**
     * 포그라운드 앱(pkg)에 연결된 프로필이 있으면 적용한다.
     *
     * 적용 시점은 "다른 앱에서 그 앱으로 전환될 때" 한 번뿐이다.
     * 우리 앱 화면(메인/설정)과 오버레이는 lastAutoPackage를 건드리지 않으므로,
     * 연동된 앱을 쓰는 도중에 사용자가 직접 다른 프로필을 불러왔더라도
     * 오버레이를 조작했다는 이유로 연동 프로필이 다시 덮어쓰이지 않는다.
     * onAccessibilityEvent와 폴링이 같은 상태(lastAutoPackage)를 공유해 이중 트리거도 막는다.
     */
    private fun applyAutoProfile(pkg: String) {
        if (!AppPreferences.isAutoLinkEnabled(this)) return
        if (pkg == packageName || pkg == lastAutoPackage) return
        // 폴링 경로에서도 키보드·시스템 UI는 앱 전환으로 보지 않는다
        if (pkg == SYSTEM_UI_PACKAGE || pkg == currentImePackage()) return
        val previous = lastAutoPackage
        lastAutoPackage = pkg
        val now = System.currentTimeMillis()

        // 쓰던 앱을 떠났다 — 언제 떠났는지 기록해 둔다
        if (previous.isNotEmpty() && previous == sessionPkg) sessionLeftAtMs = now

        if (pkg == sessionPkg) {
            // 이미 이 앱 세션 중이다. 엣지 패널·게임 부스터·키보드·우리 앱 화면처럼
            // 잠깐 다른 창을 거쳐 돌아온 것이라면 "다시 실행"이 아니므로 그대로 둔다.
            val away = now - sessionLeftAtMs
            val grace = if (userTouchedSession) USER_CHOICE_GRACE_MS else RESUME_GRACE_MS
            if (away < grace) {
                if (BuildConfig.DEBUG) Log.d(TAG_AUTO, "skip(return after ${away}ms): $pkg")
                return
            }
        }

        // 연동된 프로필이 있는 앱만 세션 주인이 된다.
        // 엣지 패널·게임 부스터처럼 잠깐 뜨는 창은 지나가는 것일 뿐이므로
        // 세션을 빼앗지 않고, 원래 앱으로 돌아올 때 "복귀"로 인식되게 둔다.
        val profile = ProfileManager.findByApp(this, pkg) ?: return

        sessionPkg = pkg
        userTouchedSession = false
        if (BuildConfig.DEBUG) Log.d(TAG_AUTO, "apply: ${profile.name} for $pkg")
        ProfileManager.setActiveId(this, profile.id)
        ProfileManager.notifyChanged(this)
        SequencePrefs.save(this, profile.config, byUser = false)
        ContextCompat.startForegroundService(
            this,
            Intent(this, OverlayService::class.java).apply { action = OverlayService.ACTION_AUTO_SHOW }
        )
        sendBroadcast(Intent(ACTION_AUTO_PROFILE).apply {
            setPackage(packageName)
            putExtra(EXTRA_PROFILE_NAME, profile.name)
        })
    }

    /**
     * 그 창이 "앱 화면"인지 판단한다.
     * 창 목록에서 찾으면 타입으로 보고(키보드=TYPE_INPUT_METHOD, 상태바·최근앱=TYPE_SYSTEM),
     * 못 찾으면 현재 키보드 앱과 시스템 UI 패키지명으로 거른다.
     */
    private fun isAppWindow(windowId: Int, pkg: String): Boolean {
        val win = windows?.firstOrNull { it.id == windowId }
        if (win != null) return win.type == AccessibilityWindowInfo.TYPE_APPLICATION
        return pkg != currentImePackage() && pkg != SYSTEM_UI_PACKAGE
    }

    /** 현재 선택된 키보드(IME)의 패키지명 */
    private fun currentImePackage(): String? =
        runCatching {
            Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
                ?.substringBefore('/')
                ?.takeIf { it.isNotEmpty() }
        }.getOrNull()

    /** 사용자가 직접 프로필을 고르거나 포인트를 바꿨다 → 지금 앱에 대한 자동 적용을 멈춘다. */
    private fun pauseAutoLinkForCurrentApp() {
        if (sessionPkg.isNotEmpty() && !userTouchedSession) {
            userTouchedSession = true
            if (BuildConfig.DEBUG) Log.d(TAG_AUTO, "user changed settings in session: $sessionPkg")
        }
    }

    override fun onInterrupt() {
        stopClicking()
    }


    override fun onDestroy() {
        super.onDestroy()
        instance = null
        stopClicking()
        pollingJob?.cancel()
        runCatching { unregisterReceiver(commandReceiver) }
        serviceScope.cancel()
    }

    private fun startAppPolling() {
        pollingJob?.cancel()
        pollingJob = serviceScope.launch {
            while (isActive) {
                delay(2000L)
                val pkg = getForegroundPackage() ?: continue
                applyAutoProfile(pkg)
            }
        }
    }

    private fun getForegroundPackage(): String? {
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return null
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(now - 5_000L, now)
        var lastPkg: String? = null
        var lastTime = 0L
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND && event.timeStamp > lastTime) {
                lastTime = event.timeStamp
                lastPkg = event.packageName
            }
        }
        return lastPkg
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
            val globalInfinite = config.repeatMode == RepeatMode.COUNT && config.repeatCount == 0
            val globalDeadline = if (config.repeatMode == RepeatMode.DURATION)
                System.currentTimeMillis() + config.repeatDurationMs else Long.MAX_VALUE
            var round = 0
            var totalClicks = 0
            var stopLoopRequested = false
            // 무료 사용자의 연속 실행 한도(광고 이용권이 있으면 24시간, 프리미엄은 무제한)
            val runLimitMs = FreePass.runLimitMs(this@AutoClickAccessibilityService)
            val runDeadline = if (runLimitMs > 0L) System.currentTimeMillis() + runLimitMs else Long.MAX_VALUE
            var hitRunLimit = false
            // 딜레이는 "다음 동작 직전"에 적용한다(첫 동작 제외).
            // 이렇게 하면 동작 사이 간격은 그대로 유지하되 마지막 동작 뒤 불필요한 대기를 없앤다.
            var prevDelayPoint: ClickPoint? = null

            fun shouldContinueGlobal(): Boolean {
                if (stopLoopRequested) return false
                if (System.currentTimeMillis() >= runDeadline) {
                    hitRunLimit = true
                    return false
                }
                return when (config.repeatMode) {
                    RepeatMode.COUNT    -> globalInfinite || round < config.repeatCount
                    RepeatMode.DURATION -> System.currentTimeMillis() < globalDeadline
                }
            }

            try {
                outer@ while (shouldContinueGlobal()) {
                    for (point in config.points) {
                        ensureActive()
                        if (!shouldContinueGlobal()) break

                        val pointDeadline = if (point.pointRepeatMode == RepeatMode.DURATION)
                            System.currentTimeMillis() + point.pointRepeatDurationMs else Long.MAX_VALUE
                        val repeatTimes = point.pointRepeatCount.coerceAtLeast(1)
                        var pr = 0

                        fun shouldContinuePoint() = when (point.pointRepeatMode) {
                            RepeatMode.COUNT    -> pr < repeatTimes
                            RepeatMode.DURATION -> System.currentTimeMillis() < pointDeadline
                        }

                        while (shouldContinuePoint()) {
                            ensureActive()
                            // pr > 0 이면 직전 동작이 "같은 포인트의 반복"이므로 반복 간 딜레이를 쓴다.
                            prevDelayPoint?.let { prev ->
                                delay(if (pr > 0) config.repeatDelayAfter(prev) else config.delayAfter(prev))
                            }
                            ensureActive()
                            val clicked = performPoint(point)
                            if (clicked) {
                                totalClicks++
                                broadcastCount(totalClicks)
                                SessionLogger.logClick(point.label, point.x, point.y)
                                if (point.stopLoopOnExecute) {
                                    stopLoopRequested = true
                                    break@outer
                                }
                            } else {
                                SessionLogger.logSkip(point.label, point.x, point.y)
                            }
                            prevDelayPoint = point
                            pr++
                        }
                    }
                    round++
                }
                if (hitRunLimit) {
                    // 한도에 걸려 멈췄다는 것을 알려 준다
                    val hours = runLimitMs / 3_600_000L
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@AutoClickAccessibilityService,
                            getString(R.string.toast_run_limit_reached, "${hours}시간"),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    broadcastStop()
                } else if (!globalInfinite || stopLoopRequested) {
                    broadcastStop()
                }
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
            val matched = checkTrigger(trigger)
            if (!matched) {
                when (trigger.action) {
                    TriggerAction.SKIP -> return false
                    TriggerAction.WAIT_RETRY -> {
                        var found = false
                        for (i in 0 until trigger.maxRetries) {
                            delay(trigger.retryDelayMs)
                            if (checkTrigger(trigger)) { found = true; break }
                        }
                        if (!found) return false
                    }
                }
            }
        }
        // 좌표 오차 적용: 반경 내 균등 분포 (원형)
        fun jitter(base: Int): Int {
            val r = p.coordinateVariancePx
            if (r <= 0) return base
            val angle = Math.random() * 2 * Math.PI
            val dist = Math.sqrt(Math.random()) * r
            return base + (dist * Math.cos(angle)).toInt()
        }
        val jx = jitter(p.x); val jy = jitter(p.y)

        when (p.gesture) {
            GestureType.TAP -> dispatchStroke(jx, jy, jx, jy, p.tapDurationMs.coerceIn(1L, 60_000L))
            GestureType.LONG_PRESS -> dispatchStroke(jx, jy, jx, jy, p.longPressDurationMs.coerceIn(100L, 60_000L))
            GestureType.SWIPE -> {
                val dur = p.swipeDurationMs.coerceIn(50L, 60_000L)
                if (p.swipeExtraPoints.isEmpty()) {
                    dispatchStroke(jx, jy, jitter(p.endX), jitter(p.endY), dur)
                } else {
                    val waypoints = buildList {
                        add(jx to jy)
                        add(jitter(p.endX) to jitter(p.endY))
                        p.swipeExtraPoints.forEach { add(jitter(it.x) to jitter(it.y)) }
                    }
                    dispatchPathSwipe(waypoints, dur)
                }
            }
        }
        return true
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun checkTrigger(trigger: TriggerCondition): Boolean =
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
                                    if (swBmp == null) return@runCatching false
                                    when (trigger.mode) {
                                        TriggerMode.REGION -> trigger.regionMatches(swBmp)
                                        TriggerMode.PIXEL -> {
                                            val x = trigger.checkX.coerceIn(0, swBmp.width - 1)
                                            val y = trigger.checkY.coerceIn(0, swBmp.height - 1)
                                            val pixel = swBmp.getPixel(x, y)
                                            TriggerCondition.colorMatches(pixel, trigger.targetColor, trigger.tolerance)
                                        }
                                    }
                                } finally {
                                    swBmp?.recycle()
                                }
                            } finally {
                                hwBmp?.recycle()
                                result.hardwareBuffer.close()
                            }
                        }.fold(
                            onSuccess = { match -> if (cont.isActive) cont.resume(match) },
                            onFailure = { if (cont.isActive) cont.resume(false) }
                        )
                    }
                    override fun onFailure(errorCode: Int) {
                        if (cont.isActive) cont.resume(false)
                    }
                }
            )
        }

    private suspend fun captureRegion(x: Int, y: Int, w: Int, h: Int, target: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            sendBroadcast(Intent(ACTION_REGION_CAPTURED).apply {
                setPackage(packageName)
                putExtra(EXTRA_REGION_TARGET, target)
                putExtra(EXTRA_REGION_PIXELS, IntArray(0))
                putExtra(EXTRA_REGION_W, 0)
                putExtra(EXTRA_REGION_H, 0)
            })
            return
        }
        delay(600L) // 다이얼로그가 완전히 닫힌 후 캡처
        @Suppress("NewApi")
        suspendCancellableCoroutine<Unit> { cont ->
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                ContextCompat.getMainExecutor(this@AutoClickAccessibilityService),
                object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        val (pixels, rw, rh) = runCatching {
                            val hwBmp = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                            val swBmp = hwBmp?.copy(Bitmap.Config.ARGB_8888, false)
                            hwBmp?.recycle()
                            result.hardwareBuffer.close()
                            val bw = swBmp?.width ?: 0
                            val bh = swBmp?.height ?: 0
                            val cx = x.coerceIn(0, (bw - 1).coerceAtLeast(0))
                            val cy = y.coerceIn(0, (bh - 1).coerceAtLeast(0))
                            val cw = w.coerceAtMost(bw - cx).coerceAtLeast(1)
                            val ch = h.coerceAtMost(bh - cy).coerceAtLeast(1)
                            val arr = IntArray(cw * ch)
                            swBmp?.getPixels(arr, 0, cw, cx, cy, cw, ch)
                            swBmp?.recycle()
                            Triple(arr, cw, ch)
                        }.getOrDefault(Triple(IntArray(0), 0, 0))
                        sendBroadcast(Intent(ACTION_REGION_CAPTURED).apply {
                            setPackage(packageName)
                            putExtra(EXTRA_REGION_TARGET, target)
                            putExtra(EXTRA_REGION_X, x)
                            putExtra(EXTRA_REGION_Y, y)
                            putExtra(EXTRA_REGION_W, rw)
                            putExtra(EXTRA_REGION_H, rh)
                            putExtra(EXTRA_REGION_PIXELS, pixels)
                        })
                        if (cont.isActive) cont.resume(Unit)
                    }
                    override fun onFailure(errorCode: Int) {
                        sendBroadcast(Intent(ACTION_REGION_CAPTURED).apply {
                            setPackage(packageName)
                            putExtra(EXTRA_REGION_TARGET, target)
                            putExtra(EXTRA_REGION_PIXELS, IntArray(0))
                            putExtra(EXTRA_REGION_W, 0)
                            putExtra(EXTRA_REGION_H, 0)
                        })
                        if (cont.isActive) cont.resume(Unit)
                    }
                }
            )
        }
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
