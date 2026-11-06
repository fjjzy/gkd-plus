package li.gkd.aidl;

import android.content.Intent;

oneway interface IInstallResultListener {
    void onResult(in Intent result);
}
