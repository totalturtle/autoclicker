package com.autoclicker

import android.content.Context
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

    private const val PREFS_NAME = "profile_prefs"
    private const val KEY_PROFILES = "profiles"

    fun loadAll(context: Context): List<Profile> {
        val json = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PROFILES, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val cfg = ClickSequenceConfig.fromJsonString(o.optString("config")) ?: continue
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
    }

    private fun saveAll(context: Context, profiles: List<Profile>) {
        val arr = JSONArray()
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
