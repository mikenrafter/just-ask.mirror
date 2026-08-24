package dev.justask.sdk

/**
 * Wire protocol between a Just Ask orchestrator and provider trampoline activities.
 *
 * The pattern mirrors Reverb's boot flow:
 * 1. Boot receiver starts an idle foreground service with a tap-to-enable notification.
 * 2. User tap launches [JustAskEnableActivity] from an Activity context (required for
 *    while-in-use eligibility on API 35+).
 * 3. The enable activity launches every configured provider trampoline, which gathers
 *    runtime permissions / MediaProjection consent and promotes its own foreground service.
 */
object JustAskContract {

    /** Intent action for PackageManager discovery of exported trampoline activities. */
    const val ACTION_TRAMPOLINE_PROVIDER = "dev.justask.action.TRAMPOLINE_PROVIDER"

    /** Service action: boot start — stay idle until the user taps the notification. */
    const val ACTION_BOOT = "dev.justask.action.BOOT"

    /** Service action: user tapped notification — launch configured targets. */
    const val ACTION_ENABLE = "dev.justask.action.ENABLE"

    /** Extra on trampoline intents: [TRAMPOLINE_MODE_START] or [TRAMPOLINE_MODE_STOP]. */
    const val EXTRA_TRAMPOLINE_MODE = "dev.justask.extra.TRAMPOLINE_MODE"
    const val TRAMPOLINE_MODE_START = "start"
    const val TRAMPOLINE_MODE_STOP = "stop"

    /** Orchestrator package that launched the trampoline. */
    const val EXTRA_CALLER_PACKAGE = "dev.justask.extra.CALLER_PACKAGE"

    /** Optional opaque token forwarded to providers for correlation. */
    const val EXTRA_SESSION_TOKEN = "dev.justask.extra.SESSION_TOKEN"

    /** SharedPreferences name used by [JustAskBootPreferences]. */
    const val PREFS_NAME = "dev.justask.boot"

    /** Boolean pref: start idle orchestrator on [android.content.Intent.ACTION_BOOT_COMPLETED]. */
    const val PREF_START_ON_BOOT = "start_on_boot"

    /** StringSet pref: JSON-encoded [JustAskTarget] entries (app module helper). */
    const val PREF_TARGETS_JSON = "targets_json"

    /**
     * Application `<meta-data>` name whose value is the host
     * [JustAskBootForegroundService] subclass (relative `.Class` or FQCN).
     */
    const val META_BOOT_SERVICE = "dev.justask.BOOT_SERVICE"
}
