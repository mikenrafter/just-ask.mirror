package dev.justask.app

import android.content.Context
import dev.justask.sdk.JustAskContract
import dev.justask.sdk.JustAskTarget
import org.json.JSONArray

class TargetStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(JustAskContract.PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): List<JustAskTarget> {
        val raw = prefs.getString(JustAskContract.PREF_TARGETS_JSON, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    add(JustAskTarget.fromJson(array.getJSONObject(i)))
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun save(targets: List<JustAskTarget>) {
        val array = JSONArray()
        targets.forEach { array.put(it.toJson()) }
        prefs.edit().putString(JustAskContract.PREF_TARGETS_JSON, array.toString()).apply()
    }

    fun upsert(target: JustAskTarget) {
        val updated = load().filterNot { it.id == target.id } + target
        save(updated)
    }

    fun remove(id: String) {
        save(load().filterNot { it.id == id })
    }

    fun enabledTargets(): List<JustAskTarget> = load().filter { it.enabled }
}
