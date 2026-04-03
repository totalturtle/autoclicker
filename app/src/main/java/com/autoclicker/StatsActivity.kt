package com.autoclicker

import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.autoclicker.databinding.ActivityStatsBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class StatsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStatsBinding

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        runCatching {
            contentResolver.openOutputStream(uri)?.use { out ->
                out.write(SessionLogger.toLogText().toByteArray(Charsets.UTF_8))
            }
            Toast.makeText(this, R.string.toast_log_exported, Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(this, R.string.toast_log_export_fail, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStatsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = getString(R.string.title_stats)
        }

        refreshStats()

        binding.btnExportLog.setOnClickListener {
            if (SessionLogger.entries.isEmpty()) {
                Toast.makeText(this, R.string.hint_no_logs, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val now = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            exportLauncher.launch("autoclicker_log_$now.txt")
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    private fun refreshStats() {
        val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val startStr = if (SessionLogger.sessionStartMs == 0L) "-"
                       else timeFmt.format(Date(SessionLogger.sessionStartMs))

        binding.tvStatStart.text   = getString(R.string.stat_start_fmt, startStr)
        binding.tvStatElapsed.text = getString(R.string.stat_elapsed_fmt, SessionLogger.elapsedSeconds)
        binding.tvStatClicks.text  = getString(R.string.stat_clicks_fmt, SessionLogger.totalClicks)
        binding.tvStatSkips.text   = getString(R.string.stat_skips_fmt, SessionLogger.skippedClicks)

        val logText = SessionLogger.toLogText()
        binding.tvLog.text = logText.ifEmpty { getString(R.string.hint_no_logs) }
    }
}
