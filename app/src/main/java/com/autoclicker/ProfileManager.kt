package com.realbtob.autoclicker

import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class Profile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val config: ClickSequenceConfig,
    val linkedAppPackage: String = ""
)

object ProfileManager {

    /** 프로필 목록/현재 프로필이 바뀌었음을 알리는 브로드캐스트 */
    const val ACTION_PROFILE_CHANGED = "com.autoclicker.ACTION_PROFILE_CHANGED"

    private const val PREFS_NAME = "profile_prefs"
    private const val KEY_PROFILES = "profiles"
    private const val KEY_ACTIVE_ID = "active_profile_id"

    /** 현재 불러와 있는 프로필 id. 화면·오버레이·알림이 공유하며 재시작 후에도 유지된다. */
    fun getActiveId(context: Context): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_ACTIVE_ID, null)

    fun setActiveId(context: Context, id: String?) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .apply { if (id == null) remove(KEY_ACTIVE_ID) else putString(KEY_ACTIVE_ID, id) }
            .apply()
    }

    /** 프로필 변경을 앱 전체(오버레이·관리 화면)에 알린다. */
    fun notifyChanged(context: Context) {
        context.sendBroadcast(Intent(ACTION_PROFILE_CHANGED).apply { setPackage(context.packageName) })
    }

    /**
     * 읽을 수 없었던 항목의 원본 JSON. 목록에는 못 띄우지만 저장할 때 그대로 되돌려 써서
     * 깨진 프로필 하나 때문에 다음 저장에서 영구히 지워지는 일을 막는다.
     */
    private var unreadableRaw: List<String> = emptyList()

    fun loadAll(context: Context): List<Profile> {
        val json = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PROFILES, null)
            ?: run { unreadableRaw = emptyList(); return emptyList() }
        val unreadable = mutableListOf<String>()
        val result = runCatching {
            val arr = JSONArray(json)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val cfg = ClickSequenceConfig.fromJsonString(o.optString("config"))
                    if (cfg == null) {
                        unreadable += o.toString()
                        continue
                    }
                    add(
                        Profile(
                            id = o.optString("id", UUID.randomUUID().toString()),
                            name = o.optString("name", "Profile"),
                            config = cfg,
                            linkedAppPackage = o.optString("app", "")
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
        unreadableRaw = unreadable
        return result
    }

    fun saveOrder(context: Context, profiles: List<Profile>) = saveAll(context, profiles)

    /** 항상 loadAll로 현재 목록을 읽은 뒤 호출해야 unreadableRaw가 최신 상태로 보존된다. */
    private fun saveAll(context: Context, profiles: List<Profile>) {
        val arr = JSONArray()
        for (raw in unreadableRaw) {
            runCatching { arr.put(JSONObject(raw)) }
        }
        for (p in profiles) {
            arr.put(JSONObject().apply {
                put("id", p.id)
                put("name", p.name)
                put("config", p.config.toJsonString())
                put("app", p.linkedAppPackage)
            })
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_PROFILES, arr.toString())
            .apply()
    }

    fun save(context: Context, profile: Profile) {
        val all = loadAll(context).toMutableList()
        val idx = all.indexOfFirst { it.id == profile.id }
        if (idx >= 0) all[idx] = profile else all.add(profile)
        saveAll(context, all)
    }

    fun delete(context: Context, id: String) {
        saveAll(context, loadAll(context).filter { it.id != id })
        if (getActiveId(context) == id) setActiveId(context, null)
    }

    fun findByApp(context: Context, packageName: String): Profile? =
        loadAll(context).firstOrNull { it.linkedAppPackage == packageName }

    fun toExportJson(profile: Profile): String = JSONObject().apply {
        put("id", profile.id)
        put("name", profile.name)
        put("config", profile.config.toJsonString())
        put("app", profile.linkedAppPackage)
    }.toString(2)

    fun fromImportJson(json: String): Profile? = runCatching {
        val o = JSONObject(json)
        val cfg = ClickSequenceConfig.fromJsonString(o.optString("config")) ?: return null
        Profile(
            id = UUID.randomUUID().toString(),
            name = o.optString("name", "Imported"),
            config = cfg,
            linkedAppPackage = o.optString("app", "")
        )
    }.getOrNull()
}
