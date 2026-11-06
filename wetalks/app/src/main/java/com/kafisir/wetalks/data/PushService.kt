package com.kafisir.wetalks.data

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Receives the silent "wake up" push sent by the Cloud Functions (functions/index.js).
 * The push contains NO message text and no names - only a type (msg / call) and a chat id.
 * Waking up just re-attaches the normal listeners; the message is then read and decrypted on the phone, as always.
 */
class PushService : FirebaseMessagingService() {
    override fun onNewToken(token: String) { Repo.registerPush(token) }

    override fun onMessageReceived(m: RemoteMessage) {
        if (Repo.uid.isEmpty()) return
        try {
            ConnectService.sync(applicationContext)
            Calls.listen(); Repo.watchBlocks(); Repo.watchPrivacy(); Notifier.start(applicationContext)
        } catch (_: Exception) {}
    }
}
