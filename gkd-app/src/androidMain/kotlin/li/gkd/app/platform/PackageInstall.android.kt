@file:Suppress("CAST_NEVER_SUCCEEDS")

package li.gkd.app.platform

import android.Manifest
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.IPackageInstaller
import android.content.pm.IPackageInstallerSession
import android.content.pm.IPackageManager
import android.content.pm.PackageInstaller
import android.content.pm.PackageInstallerSessionHidden
import android.content.pm.PackageInstallerSessionParamsHidden
import android.content.pm.PackageManager
import android.content.pm.PackageManagerHidden
import android.os.Process
import androidx.core.content.IntentCompat
import androidx.core.content.pm.PackageInfoCompat
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import li.gkd.aidl.IInstallResultListener
import li.gkd.app.META
import li.gkd.app.app
import li.gkd.app.priv.PrivilegeOwnerLifecycle
import li.gkd.app.priv.currentUserId
import li.gkd.app.priv.privilegeContextFlow
import li.gkd.app.resources.*
import li.gkd.app.ui.platform.UiHost
import li.gkd.app.util.AndroidTarget
import org.jetbrains.compose.resources.getString
import priv.kit.core.Privilege
import priv.kit.core.binder.PrivilegeBinderWrapper

actual fun privilegedPackageInstallCapability(): Flow<PackageInstallCapability> =
    privilegeContextFlow
        .map { context ->
            try {
                if (context != null && hasInstallPermission()) PackageInstallCapability.Available
                else PackageInstallCapability.Unavailable
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                PackageInstallCapability.Failure(e)
            }
        }
        .flowOn(Dispatchers.IO)

private fun hasInstallPermission() =
    Privilege.checkServerPermission(Manifest.permission.INSTALL_PACKAGES) ==
        PackageManager.PERMISSION_GRANTED

private fun systemAppMarketPackage(): String? {
    val manager = app.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MARKET)
    val candidates =
        manager
            .queryIntentActivities(intent, 0)
            .mapNotNull { result ->
                val activity = result.activityInfo
                val info = activity.applicationInfo
                activity.packageName.takeIf {
                    activity.enabled &&
                        activity.exported &&
                        info.enabled &&
                        info.flags and
                            (ApplicationInfo.FLAG_SYSTEM or
                                ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                }
            }
            .toSet()
    if (candidates.size <= 1) return candidates.singleOrNull()
    // Resolve the user's default only if it is one of the preinstalled market candidates.
    return manager
        .resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        ?.activityInfo
        ?.packageName
        ?.takeIf { it in candidates }
}

actual suspend fun installPackagePrivileged(
    file: File,
    versionCode: Int,
    host: UiHost,
): PlatformResult<Unit> =
    withContext(Dispatchers.IO) {
        val context =
            privilegeContextFlow.value
                ?: throw IOException(
                    getString(Res.string.update_install_failed),
                    IllegalStateException("Privilege service is not connected"),
                )
        if (!hasInstallPermission()) {
            throw IOException(
                getString(Res.string.update_install_failed),
                SecurityException("Privilege service lacks INSTALL_PACKAGES permission"),
            )
        }
        val archive = app.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
        require(
            archive?.packageName == META.appId &&
                PackageInfoCompat.getLongVersionCode(archive) == versionCode.toLong(),
        ) {
            getString(Res.string.update_install_invalid_apk)
        }
        val uid = context.serverInfo.uid
        val isRootOrShell = uid == 0 || uid == Process.SHELL_UID
        val marketPackage = systemAppMarketPackage()
        val installerPackage =
            if (isRootOrShell) {
                "com.android.shell"
            } else {
                requireNotNull(app.packageManager.getPackagesForUid(uid)?.firstOrNull())
            }
        // Each returned Binder needs its own wrapper: replies are not recursively proxied.
        val packageManager =
            IPackageManager.Stub.asInterface(
                requireNotNull(PrivilegeBinderWrapper.fromSystemService("package")),
            )
        val installer =
            IPackageInstaller.Stub.asInterface(
                PrivilegeBinderWrapper.fromBinder(
                    packageManager.packageInstaller.asBinder(),
                ),
            )
        val params =
            PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(META.appId)
                setSize(file.length())
                val hidden = this as PackageInstallerSessionParamsHidden
                hidden.installFlags =
                    hidden.installFlags or PackageManagerHidden.INSTALL_REPLACE_EXISTING
                if (AndroidTarget.P) hidden.installerPackageName = marketPackage
                if (AndroidTarget.S)
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        // Android 8 has no separate installer field; only root/shell may supply another package.
        val sessionInstallerPackage =
            if (!AndroidTarget.P && isRootOrShell) {
                marketPackage ?: installerPackage
            } else {
                installerPackage
            }
        val sessionId =
            if (AndroidTarget.S) {
                installer.createSession(params, sessionInstallerPackage, null, currentUserId)
            } else {
                installer.createSession(params, sessionInstallerPackage, currentUserId)
            }
        var committed = false
        var restartPrepared = false
        val results = Channel<Intent>(Channel.UNLIMITED)
        try {
            val sessionBinder =
                IPackageInstallerSession.Stub.asInterface(
                    PrivilegeBinderWrapper.fromBinder(installer.openSession(sessionId).asBinder()),
                )
            (PackageInstallerSessionHidden(sessionBinder) as PackageInstaller.Session).use { session
                ->
                val size = file.length()
                session.openWrite("base.apk", 0, size).use { output ->
                    file.inputStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            ensureActive()
                            output.write(buffer, 0, count)
                        }
                    }
                    ensureActive()
                    session.fsync(output)
                }
                ensureActive()
                PrivilegeOwnerLifecycle.setUpdateInstallationActive(true)
                restartPrepared = true
                val receiver =
                    context.createInstallResultSender(
                        installerPackage,
                        object : IInstallResultListener.Stub() {
                            override fun onResult(result: Intent) {
                                results.trySend(result)
                            }
                        },
                    )
                session.commit(receiver)
                committed = true
            }
            while (true) {
                val result = results.receive()
                val status =
                    result.getIntExtra(
                        PackageInstaller.EXTRA_STATUS,
                        PackageInstaller.STATUS_FAILURE,
                    )
                when (status) {
                    PackageInstaller.STATUS_SUCCESS ->
                        return@withContext PlatformResult.Success(Unit)
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        val intent =
                            requireNotNull(
                                IntentCompat.getParcelableExtra(
                                    result,
                                    Intent.EXTRA_INTENT,
                                    Intent::class.java,
                                ),
                            )
                        withContext(Dispatchers.Main) {
                            host.startActivity(intent)
                        }
                    }
                    else ->
                        throw IOException(
                            result.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)?.takeIf {
                                it.isNotBlank()
                            }
                                ?: getString(
                                    Res.string.update_install_failed_status,
                                    status.toString(),
                                ),
                        )
                }
            }
            @Suppress("UNREACHABLE_CODE") error("Installation result loop exited")
        } catch (e: Throwable) {
            if (!committed) {
                try {
                    installer.abandonSession(sessionId)
                } catch (cleanup: Exception) {
                    e.addSuppressed(cleanup)
                }
            }
            throw e
        } finally {
            results.close()
            if (restartPrepared) PrivilegeOwnerLifecycle.setUpdateInstallationActive(false)
        }
    }
