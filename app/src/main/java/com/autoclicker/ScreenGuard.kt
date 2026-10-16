package com.realbtob.autoclicker

import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager

/**
 * 다른 화면 방향(가로/세로)에서 만든 프로필을 불러왔는지 확인한다.
 * 프로필에는 녹화 당시 화면 크기가 없으므로, 포인트가 지금 화면 밖인지로 판단한다.
 * 화면 밖 좌표는 마커도 안 보이고 탭도 먹지 않는다.
 */
object ScreenGuard {

    /** 실제 디스플레이 크기(px). 탭 좌표는 앱 영역이 아니라 화면 전체 기준이다. */
    fun screenSize(context: Context): Pair<Int, Int> {
        val wm = context.getSystemService(WindowManager::class.java)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.currentWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            dm.widthPixels to dm.heightPixels
        }
    }

    /** 현재 화면 밖으로 나간 포인트 개수 */
    fun offScreenCount(context: Context, cfg: ClickSequenceConfig): Int {
        val (w, h) = screenSize(context)
        if (w <= 0 || h <= 0) return 0
        return cfg.points.count { p ->
            p.x !in 0..w || p.y !in 0..h ||
                (p.gesture == GestureType.SWIPE && (p.endX !in 0..w || p.endY !in 0..h))
        }
    }

    /** 경고가 필요하면 문구, 아니면 null */
    fun warning(context: Context, cfg: ClickSequenceConfig): String? {
        val count = offScreenCount(context, cfg)
        if (count == 0) return null
        val (w, h) = screenSize(context)
        return "포인트 ${count}개가 현재 화면(${w}×${h}) 밖에 있습니다.\n" +
            "다른 화면 방향(가로/세로)에서 만든 프로필로 보입니다. " +
            "화면을 돌린 뒤 다시 불러오세요. 지금 상태로 실행하면 해당 포인트는 눌리지 않습니다."
    }
}
