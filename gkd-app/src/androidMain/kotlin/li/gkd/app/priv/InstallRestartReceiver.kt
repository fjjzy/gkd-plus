@file:Suppress("CAST_NEVER_SUCCEEDS")

package li.gkd.app.priv

import android.app.IActivityManager
import android.content.Context
import android.content.IIntentReceiver
import android.content.IIntentSender
import android.content.Intent
import android.content.IntentSender
import android.content.IntentSenderHidden
import android.content.pm.PackageInstaller
import android.os.Binder
import android.os.Bundle
import android.os.DeadObjectException
import android.os.IBinder
import android.os.ServiceManager
import android.util.Log
import li.gkd.aidl.IInstallResultListener

/** Held by PackageInstaller independently of the app's UserService binding. Runs in the server. */
class InstallRestartReceiver(
    private val launchIntent: Intent,
    private val callingPackage: String,
    private val userId: Int,
    private val listener: IInstallResultListener,
) : IIntentSender.Stub() {
    private val activityManager =
        IActivityManager.Stub.asInterface(
            requireNotNull(ServiceManager.getService(Context.ACTIVITY_SERVICE)),
        )

    fun intentSender(): IntentSender = IntentSenderHidden(this) as IntentSender

    override fun asBinder(): IBinder = this

    override fun send(
        code: Int,
        intent: Intent?,
        resolvedType: String?,
        whitelistToken: IBinder?,
        finishedReceiver: IIntentReceiver?,
        requiredPermission: String?,
        options: Bundle?,
    ) {
        if (intent == null) return
        val identity = Binder.clearCallingIdentity()
        try {
            val status =
                intent.getIntExtra(
                    PackageInstaller.EXTRA_STATUS,
                    PackageInstaller.STATUS_FAILURE,
                )
            if (status == PackageInstaller.STATUS_SUCCESS) {
                try {
                    val result =
                        activityManager.startActivityAsUser(
                            null,
                            callingPackage,
                            launchIntent,
                            null,
                            null,
                            null,
                            0,
                            0,
                            null,
                            null,
                            userId,
                        )
                    check(result >= 0) { "startActivityAsUser failed: $result" }
                } catch (e: Throwable) {
                    // Installation has succeeded; a launch failure must not report install failure.
                    Log.e("GkdInstall", "Unable to reopen app after installation", e)
                }
            }
            try {
                listener.onResult(intent)
            } catch (_: DeadObjectException) {
                // Replacing the APK normally terminates the old app process.
            }
        } catch (e: Throwable) {
            Log.e("GkdInstall", "Unable to deliver installation result", e)
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }
}
