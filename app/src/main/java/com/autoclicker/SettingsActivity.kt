package com.autoclicker

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.autoclicker.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = getString(R.string.title_settings)
        }

        setupThemeSelector()
        setupInfoSection()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    private fun setupThemeSelector() {
        when (AppPreferences.getTheme(this)) {
            AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM -> binding.rbThemeSystem.isChecked = true
            AppCompatDelegate.MODE_NIGHT_NO            -> binding.rbThemeLight.isChecked  = true
            AppCompatDelegate.MODE_NIGHT_YES           -> binding.rbThemeDark.isChecked   = true
        }

        binding.rgTheme.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.rbThemeLight  -> AppCompatDelegate.MODE_NIGHT_NO
                R.id.rbThemeDark   -> AppCompatDelegate.MODE_NIGHT_YES
                else               -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
            AppPreferences.setTheme(this, mode)
        }
    }

    private fun setupInfoSection() {
        val versionName = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrDefault("—")
        binding.tvVersion.text = getString(R.string.label_version, versionName)

        binding.btnGoAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }
}
