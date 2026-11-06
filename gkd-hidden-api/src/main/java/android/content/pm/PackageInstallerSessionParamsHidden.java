package android.content.pm;

import li.songe.remap.RemapType;

@RemapType(PackageInstaller.SessionParams.class)
public class PackageInstallerSessionParamsHidden {
    public int installFlags;

    @androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.P)
    public String installerPackageName;
}
