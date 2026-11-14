package it.filettino.app

import android.content.Context
import android.os.PowerManager
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.core.app.NotificationCompat

class FilettinoMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        val prefs = getSharedPreferences(CallSignalConstants.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(CallSignalConstants.KEY_FCM_TOKEN, token).apply()

        val savedUserId = prefs.getString(CallSignalConstants.KEY_USER_ID, "") ?: ""
        if (savedUserId.isNotBlank()) {
            Thread {
                CallSignalConstants.patchUserFcmToken(savedUserId, token)
            }.start()
        }
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        val data = remoteMessage.data
        val type = data["type"] ?: return

        if (type == "test_push") {
            val nm = getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                nm.getNotificationChannel(CallSignalConstants.CHANNEL_INCOMING_CALLS) == null
            ) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CallSignalConstants.CHANNEL_INCOMING_CALLS,
                        "Chiamate in arrivo Filettino",
                        NotificationManager.IMPORTANCE_HIGH
                    )
                )
            }
            val notif = NotificationCompat.Builder(this, CallSignalConstants.CHANNEL_INCOMING_CALLS)
                .setSmallIcon(R.drawable.ic_call_notif)
                .setContentTitle("APK Filettino Attivo ✓")
                .setContentText("Il telefono è collegato per squillare sopra a tutto.")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()
            nm.notify(3303, notif)
            return
        }

        if (type == "cancel_call") {
            RingtoneForegroundService.stopRinging(this)
            return
        }

        if (type == "incoming_call") {
            val callId = data["callId"] ?: return
            val callType = data["callType"] ?: "audio"
            val callerId = data["callerId"] ?: ""
            val callerName = data["callerName"] ?: "Iscritto Filettino"
            val callerEmail = data["callerEmail"] ?: ""

            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            val wakeLock = pm.newWakeLock(
                PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                "filettino:IncomingCallWakeLock"
            )
            try {
                wakeLock.acquire(15_000L)
            } catch (_: Exception) {
            }

            RingtoneForegroundService.startRinging(
                this,
                callId,
                callType,
                callerId,
                callerName,
                callerEmail
            )
        }
    }
}
