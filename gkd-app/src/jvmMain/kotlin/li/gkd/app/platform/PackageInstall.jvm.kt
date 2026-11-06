package li.gkd.app.platform

import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import li.gkd.app.ui.platform.UiHost

actual fun privilegedPackageInstallCapability(): Flow<PackageInstallCapability> =
    flowOf(PackageInstallCapability.Unavailable)

actual suspend fun installPackagePrivileged(
    file: File,
    versionCode: Int,
    host: UiHost,
): PlatformResult<Unit> = PlatformResult.Unsupported
