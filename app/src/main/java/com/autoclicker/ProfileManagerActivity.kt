package com.autoclicker

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.autoclicker.databinding.ActivityProfileManagerBinding
import com.autoclicker.databinding.DialogSaveProfileBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class ProfileManagerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CURRENT_CONFIG_JSON = "current_config_json"
        const val RESULT_PROFILE_JSON = "profile_json"
    }

    private lateinit var binding: ActivityProfileManagerBinding
    private val profiles = mutableListOf<Profile>()
    private lateinit var adapter: ProfileAdapter

    private var pendingExportProfile: Profile? = null

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        val p = pendingExportProfile ?: return@registerForActivityResult
        runCatching {
            contentResolver.openOutputStream(uri)?.use { out ->
                out.write(ProfileManager.toExportJson(p).toByteArray(Charsets.UTF_8))
            }
            Toast.makeText(this, getString(R.string.toast_export_done, p.name), Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(this, R.string.toast_export_fail, Toast.LENGTH_SHORT).show()
        }
    }

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        runCatching {
            val json = contentResolver.openInputStream(uri)?.use {
                it.readBytes().toString(Charsets.UTF_8)
            } ?: return@registerForActivityResult
            val profile = ProfileManager.fromImportJson(json)
            if (profile == null) {
                Toast.makeText(this, R.string.toast_import_invalid, Toast.LENGTH_SHORT).show()
                return@registerForActivityResult
            }
            ProfileManager.save(this, profile)
            refreshProfiles()
            Toast.makeText(this, getString(R.string.toast_import_done, profile.name), Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(this, R.string.toast_import_fail, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProfileManagerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = getString(R.string.title_profile_manager)
        }

        adapter = ProfileAdapter(
            profiles,
            onLoad = { p ->
                setResult(Activity.RESULT_OK, Intent().putExtra(RESULT_PROFILE_JSON, p.config.toJsonString()))
                finish()
            },
            onExport = { p ->
                pendingExportProfile = p
                exportLauncher.launch("${p.name}.json")
            },
            onSetApp = { p -> showSetAppDialog(p) },
            onDelete = { p ->
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.dialog_delete_profile_title)
                    .setMessage(getString(R.string.dialog_delete_profile_msg, p.name))
                    .setPositiveButton(R.string.btn_delete) { _, _ ->
                        ProfileManager.delete(this, p.id)
                        refreshProfiles()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        )

        binding.rvProfiles.layoutManager = LinearLayoutManager(this)
        binding.rvProfiles.adapter = adapter

        binding.btnSaveCurrentProfile.setOnClickListener { showSaveDialog() }
        binding.btnImportProfile.setOnClickListener {
            importLauncher.launch(arrayOf("application/json", "*/*"))
        }

        refreshProfiles()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    private fun refreshProfiles() {
        profiles.clear()
        profiles.addAll(ProfileManager.loadAll(this))
        adapter.notifyDataSetChanged()
        binding.tvEmptyProfiles.visibility =
            if (profiles.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun showSaveDialog() {
        val currentJson = intent.getStringExtra(EXTRA_CURRENT_CONFIG_JSON)
        val config = ClickSequenceConfig.fromJsonString(currentJson)
        if (config == null || config.points.isEmpty()) {
            Toast.makeText(this, R.string.toast_no_points_to_save, Toast.LENGTH_SHORT).show()
            return
        }
        val dialogBinding = DialogSaveProfileBinding.inflate(LayoutInflater.from(this))
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_save_profile_title)
            .setView(dialogBinding.root)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.btn_save) { _, _ ->
                val name = dialogBinding.etProfileName.text?.toString()?.trim()
                if (name.isNullOrEmpty()) {
                    Toast.makeText(this, R.string.toast_enter_name, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                ProfileManager.save(this, Profile(name = name, config = config))
                refreshProfiles()
                Toast.makeText(this, getString(R.string.toast_profile_saved, name), Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun showSetAppDialog(profile: Profile) {
        val dialogBinding = DialogSaveProfileBinding.inflate(LayoutInflater.from(this))
        dialogBinding.etProfileName.hint = getString(R.string.hint_app_package)
        dialogBinding.etProfileName.setText(profile.linkedAppPackage)
        dialogBinding.etProfileName.inputType = android.text.InputType.TYPE_CLASS_TEXT
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_set_app_title)
            .setMessage(R.string.dialog_set_app_msg)
            .setView(dialogBinding.root)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.btn_set) { _, _ ->
                val pkg = dialogBinding.etProfileName.text?.toString()?.trim() ?: ""
                ProfileManager.save(this, profile.copy(linkedAppPackage = pkg))
                refreshProfiles()
                Toast.makeText(
                    this,
                    if (pkg.isEmpty()) getString(R.string.toast_app_link_removed)
                    else getString(R.string.toast_app_linked, pkg),
                    Toast.LENGTH_SHORT
                ).show()
            }
            .show()
    }
}
