package dev.justask.sdk

import android.content.Context

class JustAskBootPreferences(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(JustAskContract.PREFS_NAME, Context.MODE_PRIVATE)

    var startOnBoot: Boolean
        get() = prefs.getBoolean(JustAskContract.PREF_START_ON_BOOT, true)
        set(value) {
            prefs.edit().putBoolean(JustAskContract.PREF_START_ON_BOOT, value).apply()
        }
}
