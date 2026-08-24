# Just Ask

General-purpose Android SDK extracted from [Reverb](https://github.com/)'s boot permission orchestration pattern.

After reboot, Android blocks apps from promoting while-in-use foreground services (microphone, camera, location, media projection) unless the user has recently interacted with the app. Reverb solves this with:

1. **Boot receiver** → starts an idle **non-WIU** foreground service with a persistent notification
2. **User taps notification** → transparent **Enable activity** (Activity context carries the user-action token)
3. **Enable activity** → launches each provider's **Connect trampoline** activity
4. **Trampoline** → requests runtime permissions / MediaProjection, promotes the real FGS, then finishes

Just Ask packages that flow as a reusable SDK plus a configurable launcher app.

## Modules

| Module | Role |
|--------|------|
| `:sdk` | Library for orchestrators and provider apps |
| `:app` | General-purpose config UI — add targets, enable boot, tap-to-launch-all |

## Quick start (general app)

1. Install `:app`.
2. Add targets — package + activity class (e.g. Reverb's `app.reverb.track.mic.ConnectTrampolineActivity` in `app.reverb.track.mic`), or use **Discover** for apps that advertise `dev.justask.action.TRAMPOLINE_PROVIDER`.
3. Enable **Start on boot**.
4. After reboot, tap the notification once — **all enabled targets launch** from an eligible Activity context.

Use **Launch enabled targets now** to test without rebooting.

## Provider integration (your app requests its own permissions)

Subclass `JustAskProviderActivity` and declare an exported trampoline:

```kotlin
class MyPermissionTrampoline : JustAskProviderActivity() {
    override fun requiredRuntimePermissions() =
        arrayOf(Manifest.permission.RECORD_AUDIO)

    override fun isAlreadyReady() = MyService.isCaptureReady()

    override fun isReady() = MyService.isCaptureReady()

    override fun onTrampolineStart(callerPackage: String?) {
        val intent = Intent(this, MyService::class.java)
        callerPackage?.let { intent.putExtra(JustAskContract.EXTRA_CALLER_PACKAGE, it) }
        ContextCompat.startForegroundService(this, intent)
    }

    override fun onTrampolineStop() {
        stopService(Intent(this, MyService::class.java))
    }
}
```

```xml
<activity
    android:name=".MyPermissionTrampoline"
    android:exported="true"
    android:excludeFromRecents="true"
    android:theme="@style/JustAsk.Trampoline">
    <intent-filter>
        <action android:name="dev.justask.action.TRAMPOLINE_PROVIDER" />
    </intent-filter>
</activity>
```

Other apps (or the Just Ask launcher) start your trampoline with:

```kotlin
JustAskLauncher.launchOne(
    activity,
    JustAskTarget.component(
        id = "my-app",
        packageName = "com.example.app",
        activityClass = "com.example.app.MyPermissionTrampoline",
    ),
)
```

## Orchestrator integration (embed boot flow in your app)

1. Depend on `:sdk`.
2. Subclass `JustAskBootForegroundService` and `JustAskEnableActivity` (see `AppBootForegroundService` / `AppEnableActivity` in `:app`).
3. Declare service, enable activity, and `JustAskBootReceiver` in your manifest (copy from `app/src/main/AndroidManifest.xml`).
4. When the user opts in: `JustAsk.setBootReceiverEnabled(context, true)` and `JustAskBootPreferences(context).startOnBoot = true`.

On boot the service stays idle. When the user taps the notification, your `JustAskEnableActivity` loads targets and `JustAsk.enableFromActivity()` launches them all.

## Fixing poorly coded apps

If an app already exposes a transparent permission activity (like Reverb's `ConnectTrampolineActivity`) but never calls it on boot, add it as a **component target** in the Just Ask app. No changes to the broken app required — you supply the missing user-action Activity launch after reboot.

For apps with only a settings deep link, add an **intent action** target instead of package/class.

## Contract extras

| Extra | Purpose |
|-------|---------|
| `dev.justask.extra.TRAMPOLINE_MODE` | `start` or `stop` |
| `dev.justask.extra.CALLER_PACKAGE` | Orchestrator package name |
| `dev.justask.extra.SESSION_TOKEN` | Optional correlation token |

## Build

```bash
./gradlew :app:assembleDebug
```

Requires Android SDK 35, JDK 17+, minSdk 31 (aligned with Reverb's API 35 while-in-use requirements).

```bash
nix develop -c just-ask-fhs ./gradlew :app:assembleDebug
```

Declare the host boot service in the application manifest so the SDK receiver can start it:

```xml
<meta-data
    android:name="dev.justask.BOOT_SERVICE"
    android:value=".AppBootForegroundService" />
```

## Reverb mapping

| Reverb | Just Ask |
|--------|----------|
| `BootCompletedReceiver` | `JustAskBootReceiver` |
| `RecordingForegroundService` (idle on boot) | `JustAskBootForegroundService` |
| `EnableRecordingTrampolineActivity` | `JustAskEnableActivity` |
| `TrackMediator.launchStartTrampolines()` | `JustAskLauncher.launchAll()` |
| `ConnectTrampolineActivity` | `JustAskProviderActivity` |
