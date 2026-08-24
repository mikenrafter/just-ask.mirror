package dev.justask.sdk

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

/**
 * Base class for provider apps implementing a permission / consent trampoline.
 *
 * Subclasses implement [onTrampolineStart] to request permissions and promote a foreground
 * service, and [onTrampolineStop] to tear down. Do not [finish] until [signalReady] is
 * called or [readyTimeoutMs] elapses — Reverb's mic trampoline keeps the Activity in the
 * foreground until the service promotion succeeds.
 */
abstract class JustAskProviderActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private var waitStartMs = 0L

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        if (results.values.all { it }) {
            onPermissionsGranted(callerPackage())
        } else {
            onPermissionsDenied()
            finishSafely()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (trampolineMode() == JustAskContract.TRAMPOLINE_MODE_STOP) {
            onTrampolineStop()
            finishSafely()
            return
        }

        if (isAlreadyReady()) {
            onAlreadyReady(callerPackage())
            finishSafely()
            return
        }

        val missing = missingRuntimePermissions()
        if (missing.isEmpty()) {
            onTrampolineStart(callerPackage())
            waitUntilReady()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    /** Runtime permissions this trampoline should request before [onTrampolineStart]. */
    protected open fun requiredRuntimePermissions(): Array<String> = emptyArray()

    /** Return true when the provider is already prepared (skip start path). */
    protected open fun isAlreadyReady(): Boolean = false

    /** Called when permissions are already granted or just granted by the user. */
    protected abstract fun onTrampolineStart(callerPackage: String?)

    /** Called for [JustAskContract.TRAMPOLINE_MODE_STOP]. */
    protected open fun onTrampolineStop() {}

    /** Called when [isAlreadyReady] is true — e.g. broadcast readiness to caller. */
    protected open fun onAlreadyReady(callerPackage: String?) {}

    /** Poll until true or [readyTimeoutMs]. Default checks [isAlreadyReady]. */
    protected open fun isReady(): Boolean = isAlreadyReady()

    /** Milliseconds to keep the Activity alive while waiting for readiness. */
    protected open val readyTimeoutMs: Long = 5_000L

    protected open val readyPollIntervalMs: Long = 50L

    protected fun signalReady() {
        finishSafely()
    }

    protected fun callerPackage(): String? {
        return intent?.getStringExtra(JustAskContract.EXTRA_CALLER_PACKAGE)
    }

    protected fun sessionToken(): String? {
        return intent?.getStringExtra(JustAskContract.EXTRA_SESSION_TOKEN)
    }

    private fun missingRuntimePermissions(): List<String> {
        return requiredRuntimePermissions().filter {
            checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    private fun onPermissionsGranted(caller: String?) {
        onTrampolineStart(caller)
        waitUntilReady()
    }

    protected open fun onPermissionsDenied() {}

    private fun waitUntilReady() {
        waitStartMs = System.currentTimeMillis()
        handler.postDelayed(::pollReady, readyPollIntervalMs)
    }

    private fun pollReady() {
        if (isReady() || System.currentTimeMillis() - waitStartMs >= readyTimeoutMs) {
            finishSafely()
            return
        }
        handler.postDelayed(::pollReady, readyPollIntervalMs)
    }

    private fun trampolineMode(): String {
        return intent?.getStringExtra(JustAskContract.EXTRA_TRAMPOLINE_MODE)
            ?: JustAskContract.TRAMPOLINE_MODE_START
    }

    private fun finishSafely() {
        if (!isFinishing) finish()
    }
}
