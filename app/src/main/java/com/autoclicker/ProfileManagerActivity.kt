package com.realbtob.autoclicker

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.realbtob.autoclicker.databinding.ActivityProfileManagerBinding
import com.realbtob.autoclicker.databinding.DialogSaveProfileBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class ProfileManagerActivity : AppCompatActivity() {

    companion object {
        const val RESULT_PROFILE_ID = "profile_id"
    }

    private lateinit var binding: ActivityProfileManagerBinding
    private val profiles = mutableListOf<Profile>()
    private lateinit var adapter: ProfileAdapter
    private lateinit var itemTouchHelper: ItemTouchHelper

    private var pendingExportProfile: Profile? = null

    private val profileChangedReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
            refreshProfiles()
        }
    }

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
            ProfileManager.notifyChanged(this)
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
                if (PremiumManager.isPremium) {
                    finishWithProfile(p)
                } else {
                    AdManager.showInterstitial(this) { finishWithProfile(p) }
                }
            },
            onExport = { p ->
                pendingExportProfile = p
                exportLauncher.launch("${p.name}.json")
            },
            onSetApp = { p ->
                if (!PremiumManager.isPremium) {
                    MaterialAlertDialogBuilder(this)
                        .setTitle("🔒 프리미엄 기능")
                        .setMessage("앱 자동 연동은 프리미엄 버전에서 사용할 수 있습니다.\n₩4,900 일회 결제로 모든 기능을 영구적으로 이용하세요.")
                        .setPositiveButton("구매하기") { _, _ ->
                            PremiumManager.launchPurchase(this) { success ->
                                if (success) Toast.makeText(this, "프리미엄으로 업그레이드되었습니다!", Toast.LENGTH_SHORT).show()
                            }
                        }
                        .setNegativeButton("취소", null)
                        .show()
                } else {
                    showSetAppDialog(p)
                }
            },
            onDelete = { p ->
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.dialog_delete_profile_title)
                    .setMessage(getString(R.string.dialog_delete_profile_msg, p.name))
                    .setPositiveButton(R.string.btn_delete) { _, _ ->
                        ProfileManager.delete(this, p.id)
                        ProfileManager.notifyChanged(this)
                        refreshProfiles()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            },
            onStartDrag = { vh -> itemTouchHelper.startDrag(vh) }
        )

        itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                adapter.moveItem(vh.adapterPosition, target.adapterPosition)
                return true
            }
            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {}
            override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
                super.clearView(rv, vh)
                ProfileManager.saveOrder(this@ProfileManagerActivity, profiles)
                ProfileManager.notifyChanged(this@ProfileManagerActivity)
            }
        })

        binding.rvProfiles.layoutManager = LinearLayoutManager(this)
        binding.rvProfiles.adapter = adapter
        itemTouchHelper.attachToRecyclerView(binding.rvProfiles)

        binding.btnSaveCurrentProfile.setOnClickListener { showSaveDialog() }
        binding.btnImportProfile.setOnClickListener {
            importLauncher.launch(arrayOf("application/json", "*/*"))
        }

        refreshProfiles()
    }

    override fun onResume() {
        super.onResume()
        refreshProfiles()
        val filter = android.content.IntentFilter(ProfileManager.ACTION_PROFILE_CHANGED)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(profileChangedReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(profileChangedReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        runCatching { unregisterReceiver(profileChangedReceiver) }
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
        val config = SequencePrefs.load(this)
        if (config == null || config.points.isEmpty()) {
            Toast.makeText(this, R.string.toast_no_points_to_save, Toast.LENGTH_SHORT).show()
            return
        }
        val all = ProfileManager.loadAll(this)
        val current = all.firstOrNull { it.id == ProfileManager.getActiveId(this) }
        val dialogBinding = DialogSaveProfileBinding.inflate(LayoutInflater.from(this))
        // 불러온 프로필이 있으면 이름을 채워 둬서 덮어쓰기/다른 이름 저장을 바로 고를 수 있게 한다.
        current?.let { dialogBinding.etProfileName.setText(it.name) }
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
                val existing = all.firstOrNull { it.name == name }
                if (existing != null) confirmOverwrite(existing, config) else saveAsNewProfile(name, config)
            }
            .show()
    }

    /** 같은 이름이 이미 있으면 중복 생성 대신 덮어쓸지 확인한다. */
    private fun confirmOverwrite(target: Profile, config: ClickSequenceConfig) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_overwrite_profile_title)
            .setMessage(getString(R.string.dialog_overwrite_profile_msg, target.name))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.btn_save) { _, _ ->
                ProfileManager.save(this, target.copy(config = config))
                ProfileManager.setActiveId(this, target.id)
                ProfileManager.notifyChanged(this)
                refreshProfiles()
                Toast.makeText(this, getString(R.string.toast_profile_overwritten, target.name), Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    /** 새 프로필 저장. 개수 제한은 새로 만들 때만 적용한다(덮어쓰기는 항상 허용). */
    private fun saveAsNewProfile(name: String, config: ClickSequenceConfig) {
        if (!PremiumManager.isPremium && ProfileManager.loadAll(this).size >= 2) {
            MaterialAlertDialogBuilder(this)
                .setTitle("🔒 프리미엄 기능")
                .setMessage("무료 버전은 프로필을 2개까지 저장할 수 있습니다.\n₩4,900 일회 결제로 무제한 저장하세요.")
                .setPositiveButton("구매하기") { _, _ ->
                    PremiumManager.launchPurchase(this) { success ->
                        if (success) Toast.makeText(this, "프리미엄으로 업그레이드되었습니다!", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("취소", null)
                .show()
            return
        }
        val created = Profile(name = name, config = config)
        ProfileManager.save(this, created)
        ProfileManager.setActiveId(this, created.id)
        ProfileManager.notifyChanged(this)
        refreshProfiles()
        Toast.makeText(this, getString(R.string.toast_profile_saved, name), Toast.LENGTH_SHORT).show()
    }

    /** 불러오기: 현재 프로필로 기록하고 id만 돌려준다. */
    private fun finishWithProfile(profile: Profile) {
        ProfileManager.setActiveId(this, profile.id)
        setResult(Activity.RESULT_OK, Intent().putExtra(RESULT_PROFILE_ID, profile.id))
        finish()
    }

    private fun showSetAppDialog(profile: Profile) {
        val dialogBinding = DialogSaveProfileBinding.inflate(LayoutInflater.from(this))
        (dialogBinding.etProfileName.parent as? com.google.android.material.textfield.TextInputLayout)
            ?.hint = getString(R.string.hint_app_package)
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
                ProfileManager.notifyChanged(this)
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
