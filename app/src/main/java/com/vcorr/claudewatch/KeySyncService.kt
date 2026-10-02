package com.vcorr.claudewatch

import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService

/**
 * Receives the API key from the phone app and replies whether it was saved. The Data Layer only
 * delivers messages between apps with the same package name and signing key.
 */
class KeySyncService : WearableListenerService() {

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != KeySync.PATH_KEY) return
        // Called on a background thread, so the blocking write is fine here.
        val saved = ApiKeyStore.write(this, event.data.toString(Charsets.UTF_8))
        Wearable.getMessageClient(this).sendMessage(
            event.sourceNodeId,
            if (saved) KeySync.PATH_SAVED else KeySync.PATH_FAILED,
            ByteArray(0)
        )
    }
}
