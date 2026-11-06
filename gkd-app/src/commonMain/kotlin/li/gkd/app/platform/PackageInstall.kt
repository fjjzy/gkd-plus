package li.gkd.app.platform

import java.io.File
import kotlinx.coroutines.flow.Flow
import li.gkd.app.ui.platform.UiHost

sealed interface PackageInstallCapability {
    data object Checking : PackageInstallCapability

    data object Available : PackageInstallCapability

    data object Unavailable : PackageInstallCapability

    data class Failure(val exception: Exception) : PackageInstallCapability
}

expect fun privilegedPackageInstallCapability(): Flow<PackageInstallCapability>

/** Success means the system reported installation success, including any required confirmation. */
expect suspend fun installPackagePrivileged(
    file: File,
    versionCode: Int,
    host: UiHost,
): PlatformResult<Unit>
