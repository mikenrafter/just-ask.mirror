package dev.justask.sdk

import android.content.ComponentName
import android.content.Intent
import org.json.JSONObject

/**
 * Describes one activity the orchestrator should launch from an eligible Activity context.
 *
 * Supports explicit components (typical provider trampolines) and arbitrary intents
 * (for fixing apps that expose a deep link or settings activity).
 */
data class JustAskTarget(
    val id: String,
    val label: String,
    val enabled: Boolean,
    val componentPackage: String?,
    val componentClass: String?,
    val intentAction: String?,
    val intentData: String?,
    val intentType: String?,
    val intentFlags: Int,
) {
    val displayLabel: String
        get() = label.ifBlank {
            componentClass?.substringAfterLast('.') ?: intentAction ?: id
        }

    fun toLaunchIntent(callerPackage: String, sessionToken: String? = null): Intent {
        val intent = when {
            componentPackage != null && componentClass != null -> Intent().apply {
                setClassName(componentPackage, componentClass)
            }
            intentAction != null -> Intent(intentAction).apply {
                intentData?.let { data = android.net.Uri.parse(it) }
                intentType?.let { type = it }
            }
            else -> throw IllegalStateException("Target $id has no component or action")
        }
        intent.putExtra(JustAskContract.EXTRA_TRAMPOLINE_MODE, JustAskContract.TRAMPOLINE_MODE_START)
        intent.putExtra(JustAskContract.EXTRA_CALLER_PACKAGE, callerPackage)
        sessionToken?.let { intent.putExtra(JustAskContract.EXTRA_SESSION_TOKEN, it) }
        if (intentFlags != 0) {
            intent.addFlags(intentFlags)
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return intent
    }

    fun componentName(): ComponentName? {
        if (componentPackage == null || componentClass == null) return null
        return ComponentName(componentPackage, componentClass)
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("label", label)
        put("enabled", enabled)
        put("componentPackage", componentPackage)
        put("componentClass", componentClass)
        put("intentAction", intentAction)
        put("intentData", intentData)
        put("intentType", intentType)
        put("intentFlags", intentFlags)
    }

    companion object {
        fun fromJson(json: JSONObject): JustAskTarget = JustAskTarget(
            id = json.getString("id"),
            label = json.optString("label", ""),
            enabled = json.optBoolean("enabled", true),
            componentPackage = json.optString("componentPackage").ifBlank { null },
            componentClass = json.optString("componentClass").ifBlank { null },
            intentAction = json.optString("intentAction").ifBlank { null },
            intentData = json.optString("intentData").ifBlank { null },
            intentType = json.optString("intentType").ifBlank { null },
            intentFlags = json.optInt("intentFlags", 0),
        )

        fun component(
            id: String,
            packageName: String,
            activityClass: String,
            label: String = "",
            enabled: Boolean = true,
        ): JustAskTarget = JustAskTarget(
            id = id,
            label = label,
            enabled = enabled,
            componentPackage = packageName,
            componentClass = activityClass,
            intentAction = null,
            intentData = null,
            intentType = null,
            intentFlags = 0,
        )
    }
}
