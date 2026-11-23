package it.filettino.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class FilettinoMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        val prefs = getSharedPreferences(CallSignalConstants.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(CallSignalConstants.KEY_FCM_TOKEN, token).apply()

        val savedUserId = prefs.getString(CallSignalConstants.KEY_USER_ID, "") ?: ""
        if (savedUserId.isNotBlank()) {
            val appCtx = applicationContext
            Thread {
                CallSignalConstants.patchUserFcmToken(savedUserId, token, appCtx)
            }.start()
        }
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        val data = remoteMessage.data
        val type = data["type"] ?: return

        if (type == "direct_message" || type == "broadcast_message") {
            val nm = getSystemService(NotificationManager::class.java)
            val channelId = "filettino_messages_v1"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                nm.getNotificationChannel(channelId) == null
            ) {
                val ch = NotificationChannel(
                    channelId,
                    "Messaggi Filettino App",
                    NotificationManager.IMPORTANCE_HIGH
                )
                ch.enableVibration(true)
                nm.createNotificationChannel(ch)
            }

            val title = data["msgTitle"]?.takeIf { it.isNotBlank() }
                ?: if (type == "broadcast_message") "Avviso da Filettino App" else "Nuovo messaggio"
            val body = data["msgBody"]?.takeIf { it.isNotBlank() }
                ?: "Apri Filettino App per leggere il messaggio."

            val openIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                this,
                (System.currentTimeMillis() % 100000).toInt(),
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notif = NotificationCompat.Builder(this, channelId)
                .setSmallIcon(R.drawable.ic_call_notif)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()

            val notifId = (data["msgId"]?.hashCode() ?: System.currentTimeMillis().toInt())
            nm.notify(notifId, notif)
            return
        }

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
