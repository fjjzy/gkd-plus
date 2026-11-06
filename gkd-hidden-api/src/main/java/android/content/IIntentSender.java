package android.content;

import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.IInterface;

public interface IIntentSender extends IInterface {
    abstract class Stub extends Binder implements IIntentSender {}

    void send(int code, Intent intent, String resolvedType, IBinder whitelistToken,
              IIntentReceiver finishedReceiver, String requiredPermission, Bundle options);
}
