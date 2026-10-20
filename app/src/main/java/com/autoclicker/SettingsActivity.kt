package com.realbtob.autoclicker

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.realbtob.autoclicker.databinding.ActivitySettingsBinding

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
        setupStartScreenToggle()
        setupOverlayButtonToggle()
        setupAutoLinkToggle()
        setupInfoSection()
        setupPremiumSection()
        setupDebugPremiumToggle()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    private fun setupStartScreenToggle() {
        binding.switchStartSimple.isChecked = AppPreferences.startInSimpleMode(this)
        binding.switchStartSimple.setOnCheckedChangeListener { _, isChecked ->
            AppPreferences.setSimpleModeStart(this, isChecked)
            Toast.makeText(
                this,
                if (isChecked) R.string.simple_switched_to_simple else R.string.simple_switched_to_advanced,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun setupOverlayButtonToggle() {
        binding.switchOverlayButton.isChecked = AppPreferences.isOverlayButtonEnabled(this)
        binding.switchOverlayButton.setOnCheckedChangeListener { _, isChecked ->
            AppPreferences.setOverlayButtonEnabled(this, isChecked)
            // 서비스에 바로 반영
            runCatching {
                androidx.core.content.ContextCompat.startForegroundService(
                    this, Intent(this, OverlayService::class.java)
                )
            }
            Toast.makeText(
                this,
                if (isChecked) R.string.toast_overlay_button_on else R.string.toast_overlay_button_off,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun setupAutoLinkToggle() {
        binding.switchAutoLink.isChecked = AppPreferences.isAutoLinkEnabled(this)
        binding.switchAutoLink.setOnCheckedChangeListener { _, isChecked ->
            AppPreferences.setAutoLinkEnabled(this, isChecked)
            Toast.makeText(
                this,
                if (isChecked) R.string.toast_auto_link_on else R.string.toast_auto_link_off,
                Toast.LENGTH_SHORT
            ).show()
        }
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

    private fun setupPremiumSection() {
        if (PremiumManager.isPremium) {
            binding.tvPremiumStatus.text = "✅ 프리미엄"
            binding.tvPremiumDesc.text = "모든 기능이 잠금 해제되어 있습니다."
            binding.btnPurchasePremium.text = "구매 완료"
            binding.btnPurchasePremium.isEnabled = false
            binding.btnPurchasePremium.backgroundTintList =
                android.content.res.ColorStateList.valueOf(0xFF4CAF50.toInt())
        } else {
            binding.btnPurchasePremium.setOnClickListener {
                PremiumManager.launchPurchase(this) { success ->
                    if (success) {
                        Toast.makeText(this, "프리미엄으로 업그레이드되었습니다!", Toast.LENGTH_SHORT).show()
                        setupPremiumSection()
                    }
                }
            }
        }
    }

    private fun setupDebugPremiumToggle() {
        if (!BuildConfig.DEBUG) {
            binding.cardDebugPremium.visibility = View.GONE
            return
        }
        binding.cardDebugPremium.visibility = View.VISIBLE
        binding.switchDebugPremium.setOnCheckedChangeListener(null)
        binding.switchDebugPremium.isChecked = PremiumManager.isDebugPremiumOverride
        binding.switchDebugPremium.setOnCheckedChangeListener { _, isChecked ->
            PremiumManager.setDebugPremiumOverride(this, isChecked)
            Toast.makeText(
                this,
                if (isChecked) "프리미엄 강제 ON (디버그)" else "프리미엄 강제 OFF (디버그)",
                Toast.LENGTH_SHORT
            ).show()
            recreate()
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
