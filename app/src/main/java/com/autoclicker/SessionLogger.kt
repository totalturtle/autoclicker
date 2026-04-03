package com.autoclicker

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object SessionLogger {

    data class Entry(val timestamp: Long, val message: String)

    private val _entries = mutableListOf<Entry>()

    var sessionStartMs: Long = 0L
        private set
    var totalClicks: Int = 0
        private set
    var skippedClicks: Int = 0
        private set

    val entries: List<Entry> get() = _entries.toList()
    val elapsedSeconds: Long
        get() = if (sessionStartMs == 0L) 0L
                else (System.currentTimeMillis() - sessionStartMs) / 1000

    fun startSession() {
        _entries.clear()
        sessionStartMs = System.currentTimeMillis()
        totalClicks = 0
        skippedClicks = 0
        addEntry("세션 시작")
    }

    fun logClick(label: String, x: Int, y: Int) {
        totalClicks++
        addEntry("클릭: ${label.ifEmpty { "($x,$y)" }}")
    }

    fun logSkip(label: String, x: Int, y: Int) {
        skippedClicks++
        addEntry("스킵(조건불일치): ${label.ifEmpty { "($x,$y)" }}")
    }

    fun endSession() {
        addEntry("세션 종료 — ${totalClicks}클릭 / ${skippedClicks}스킵 / ${elapsedSeconds}초")
    }

    fun toLogText(): String {
        val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
        return _entries.joinToString("\n") { "[${fmt.format(Date(it.timestamp))}] ${it.message}" }
    }

    private fun addEntry(msg: String) {
        _entries.add(Entry(System.currentTimeMillis(), msg))
    }
}
