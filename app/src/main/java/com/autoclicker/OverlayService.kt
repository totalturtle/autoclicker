package com.autoclicker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.core.app.NotificationCompat

/**
 * 플로팅 컨트롤 패널. 시퀀스 JSON을 보관해 토글 시 AccessibilityService에 전달한다.
 */
class OverlayService : Service() {

    companion object {
        const val EXTRA_SEQUENCE_JSON = "extra_sequence_json"

        private const val CHANNEL_ID = "overlay_channel"
        private const val NOTIF_ID = 1001
    }

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: android.view.View
    private lateinit var layoutParams: WindowManager.LayoutParams

    private var isRunning = false
    private var sequenceJson: String? = null

    private val countReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                AutoClickAccessibilityService.ACTION_CLICK_COUNT -> {
                    val count = intent.getIntExtra(AutoClickAccessibilityService.EXTRA_COUNT, 0)
                    overlayView.findViewById<TextView>(R.id.tvOverlayStatus)?.text =
                        getString(R.string.overlay_click_fmt, count)
                }
                AutoClickAccessibilityService.ACTION_STOP -> {
                    setRunningState(false)
                }
                AutoClickAccessibilityService.ACTION_AUTO_PROFILE -> {
                    sequenceJson = SequencePrefs.load(this@OverlayService)?.toJsonString() ?: sequenceJson
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundWithNotification()
        setupOverlayView()

        val filter = IntentFilter().apply {
            addAction(AutoClickAccessibilityService.ACTION_CLICK_COUNT)
            addAction(AutoClickAccessibilityService.ACTION_STOP)
            addAction(AutoClickAccessibilityService.ACTION_AUTO_PROFILE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(countReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(countReceiver, filter)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getStringExtra(EXTRA_SEQUENCE_JSON)?.let { sequenceJson = it }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { unregisterReceiver(countReceiver) }
        runCatching { windowManager.removeView(overlayView) }
    }

    private fun setupOverlayView() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_control, null)

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 20
            y = 200
        }

        windowManager.addView(overlayView, layoutParams)

        overlayView.findViewById<Button>(R.id.btnOverlayToggle).setOnClickListener {
            if (isRunning) {
                sendStopToService()
                setRunningState(false)
            } else {
                sendStartToService()
                setRunningState(true)
            }
        }

        var dragStartX = 0f
        var dragStartY = 0f
        var initialLayoutX = 0
        var initialLayoutY = 0

        overlayView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dragStartX = event.rawX
                    dragStartY = event.rawY
                    initialLayoutX = layoutParams.x
                    initialLayoutY = layoutParams.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    layoutParams.x = initialLayoutX + (event.rawX - dragStartX).toInt()
                    layoutParams.y = initialLayoutY + (event.rawY - dragStartY).toInt()
                    windowManager.updateViewLayout(overlayView, layoutParams)
                    true
                }
                else -> false
            }
        }
    }

    private fun setRunningState(running: Boolean) {
        isRunning = running
        overlayView.findViewById<Button>(R.id.btnOverlayToggle)?.apply {
            text = if (running) "■" else "▶"
            backgroundTintList = getColorStateList(
                if (running) R.color.btn_stop else R.color.btn_start
            )
        }
        if (!running) {
            overlayView.findViewById<TextView>(R.id.tvOverlayStatus)?.text =
                getString(R.string.overlay_idle)
        }
    }

    private fun sendStartToService() {
        val json = sequenceJson ?: return
        sendBroadcast(Intent(AutoClickAccessibilityService.ACTION_START).apply {
            setPackage(packageName)
            putExtra(AutoClickAccessibilityService.EXTRA_SEQUENCE_JSON, json)
        })
    }

    private fun sendStopToService() {
        sendBroadcast(Intent(AutoClickAccessibilityService.ACTION_STOP).apply {
            setPackage(packageName)
        })
    }

    private fun startForegroundWithNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "AutoClicker 실행 중",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AutoClicker")
            .setContentText("자동 클릭 준비됨. 플로팅 버튼으로 제어하세요.")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(NOTIF_ID, notification)
    }
}
