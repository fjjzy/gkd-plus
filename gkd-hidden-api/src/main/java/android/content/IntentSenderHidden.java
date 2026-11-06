package android.content;

import li.songe.remap.RemapType;

@RemapType(IntentSender.class)
public class IntentSenderHidden {
    public IntentSenderHidden(IIntentSender target) {
        throw new RuntimeException();
    }
}
