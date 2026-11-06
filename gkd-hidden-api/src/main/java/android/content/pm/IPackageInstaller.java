package android.content.pm;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;

public interface IPackageInstaller extends IInterface {
    abstract class Stub extends Binder implements IPackageInstaller {
        public static IPackageInstaller asInterface(IBinder binder) {
            throw new RuntimeException();
        }
    }

    int createSession(PackageInstaller.SessionParams params, String installerPackageName, int userId);
    int createSession(PackageInstaller.SessionParams params, String installerPackageName, String attributionTag, int userId);
    IPackageInstallerSession openSession(int sessionId);
    void abandonSession(int sessionId);
}
