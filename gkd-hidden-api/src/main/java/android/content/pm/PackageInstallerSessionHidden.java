package android.content.pm;

import li.songe.remap.RemapType;

@RemapType(PackageInstaller.Session.class)
public class PackageInstallerSessionHidden {
    public PackageInstallerSessionHidden(IPackageInstallerSession session) {
        throw new RuntimeException();
    }
}
