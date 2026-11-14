package it.filettino.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class RingtoneForegroundService : Service() {

    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private var watchTask: ScheduledFuture<*>? = null
    private var currentCallId: String = ""

    companion object {
        fun startRinging(
            context: Context,
            callId: String,
            callType: String,
            callerId: String,
            callerName: String,
            callerEmail: String
        ) {
            val intent = Intent(context, RingtoneForegroundService::class.java).apply {
                putExtra(CallSignalConstants.EXTRA_CALL_ID, callId)
                putExtra(CallSignalConstants.EXTRA_CALL_TYPE, callType)
                putExtra(CallSignalConstants.EXTRA_CALLER_ID, callerId)
                putExtra(CallSignalConstants.EXTRA_CALLER_NAME, callerName)
                putExtra(CallSignalConstants.EXTRA_CALLER_EMAIL, callerEmail)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopRinging(context: Context) {
            val intent = Intent(context, RingtoneForegroundService::class.java).apply {
                action = CallSignalConstants.ACTION_STOP_RINGING
            }
            try {
                context.startService(intent)
            } catch (_: Exception) {
                try {
                    context.stopService(Intent(context, RingtoneForegroundService::class.java))
                } catch (_: Exception) {
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == CallSignalConstants.ACTION_STOP_RINGING) {
            stopAndFinish()
            return START_NOT_STICKY
        }
        if (action == CallSignalConstants.ACTION_DECLINE_CALL) {
            val callId = intent.getStringExtra(CallSignalConstants.EXTRA_CALL_ID) ?: currentCallId
            Thread { CallSignalConstants.patchCallStatus(callId, "declined") }.start()
            stopAndFinish()
            return START_NOT_STICKY
        }

        val callId = intent?.getStringExtra(CallSignalConstants.EXTRA_CALL_ID) ?: ""
        val callType = intent?.getStringExtra(CallSignalConstants.EXTRA_CALL_TYPE) ?: "audio"
        val callerId = intent?.getStringExtra(CallSignalConstants.EXTRA_CALLER_ID) ?: ""
        val callerName = intent?.getStringExtra(CallSignalConstants.EXTRA_CALLER_NAME) ?: "Iscritto Filettino"
        val callerEmail = intent?.getStringExtra(CallSignalConstants.EXTRA_CALLER_EMAIL) ?: ""
        currentCallId = callId

        ensureCallChannel()
        val notification = buildIncomingCallNotification(
            callId,
            callType,
            callerId,
            callerName,
            callerEmail
        )

        val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        } else {
            0
        }
        ServiceCompat.startForeground(this, CallSignalConstants.NOTIF_ID_INCOMING_CALL, notification, fgsType)

        startContinuousRingtone()
        startContinuousVibration()
        launchFullScreenCallActivity(callId, callType, callerId, callerName, callerEmail)
        startStatusPolling(callId)

        return START_NOT_STICKY
    }

    private fun ensureCallChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val existing = manager.getNotificationChannel(CallSignalConstants.CHANNEL_INCOMING_CALLS)
            if (existing == null) {
                val channel = NotificationChannel(
                    CallSignalConstants.CHANNEL_INCOMING_CALLS,
                    "Chiamate in arrivo Filettino",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Squillo continuo e schermata di chiamata in arrivo sopra a tutto"
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                    setBypassDnd(true)
                    enableVibration(true)
                    setSound(null, null)
                }
                manager.createNotificationChannel(channel)
            }
        }
    }

    private fun buildIncomingCallNotification(
        callId: String,
        callType: String,
        callerId: String,
        callerName: String,
        callerEmail: String
    ): Notification {
        val fullScreenIntent = Intent(this, IncomingCallActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(CallSignalConstants.EXTRA_CALL_ID, callId)
            putExtra(CallSignalConstants.EXTRA_CALL_TYPE, callType)
            putExtra(CallSignalConstants.EXTRA_CALLER_ID, callerId)
            putExtra(CallSignalConstants.EXTRA_CALLER_NAME, callerName)
            putExtra(CallSignalConstants.EXTRA_CALLER_EMAIL, callerEmail)
        }
        val fullScreenPendingIntent = PendingIntent.getActivity(
            this,
            100,
            fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val acceptIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(CallSignalConstants.EXTRA_CALL_ID, callId)
            putExtra(CallSignalConstants.EXTRA_CALL_TYPE, callType)
            putExtra(CallSignalConstants.EXTRA_CALLER_ID, callerId)
            putExtra(CallSignalConstants.EXTRA_CALLER_NAME, callerName)
            putExtra(CallSignalConstants.EXTRA_CALLER_EMAIL, callerEmail)
            putExtra(CallSignalConstants.EXTRA_CALL_ACTION, "accept")
        }
        val acceptPendingIntent = PendingIntent.getActivity(
            this,
            101,
            acceptIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val declineIntent = Intent(this, RingtoneForegroundService::class.java).apply {
            action = CallSignalConstants.ACTION_DECLINE_CALL
            putExtra(CallSignalConstants.EXTRA_CALL_ID, callId)
        }
        val declinePendingIntent = PendingIntent.getService(
            this,
            102,
            declineIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val label = if (callType == "video") "Videochiamata in arrivo" else "Chiamata vocale in arrivo"

        return NotificationCompat.Builder(this, CallSignalConstants.CHANNEL_INCOMING_CALLS)
            .setSmallIcon(R.drawable.ic_call_notif)
            .setContentTitle(callerName)
            .setContentText("$label • Comunità di Filettino")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setContentIntent(fullScreenPendingIntent)
            .addAction(R.drawable.ic_call_notif, "Rispondi", acceptPendingIntent)
            .addAction(R.drawable.ic_call_end, "Rifiuta", declinePendingIntent)
            .build()
    }

    private fun launchFullScreenCallActivity(
        callId: String,
        callType: String,
        callerId: String,
        callerName: String,
        callerEmail: String
    ) {
        try {
            val intent = Intent(this, IncomingCallActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(CallSignalConstants.EXTRA_CALL_ID, callId)
                putExtra(CallSignalConstants.EXTRA_CALL_TYPE, callType)
                putExtra(CallSignalConstants.EXTRA_CALLER_ID, callerId)
                putExtra(CallSignalConstants.EXTRA_CALLER_NAME, callerName)
                putExtra(CallSignalConstants.EXTRA_CALLER_EMAIL, callerEmail)
            }
            startActivity(intent)
        } catch (_: Exception) {
        }
    }

    private fun startContinuousRingtone() {
        if (mediaPlayer != null) return
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val player = MediaPlayer()
            player.setDataSource(this, uri)
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            player.isLooping = true
            player.prepare()
            player.start()
            mediaPlayer = player
        } catch (_: Exception) {
        }
    }

    private fun startContinuousVibration() {
        try {
            val vib = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(VibratorManager::class.java)
                vm.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            vibrator = vib
            val pattern = longArrayOf(0, 900, 400, 900, 1200)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vib.vibrate(VibrationEffect.createWaveform(pattern, 0))
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(pattern, 0)
            }
        } catch (_: Exception) {
        }
    }

    private fun startStatusPolling(callId: String) {
        watchTask?.cancel(true)
        var ticks = 0
        watchTask = scheduler.scheduleAtFixedRate({
            ticks++
            if (ticks > 20 || !CallSignalConstants.isCallStillRinging(callId)) {
                stopAndFinish()
            }
        }, 3, 2, TimeUnit.SECONDS)
    }

    private fun stopAndFinish() {
        watchTask?.cancel(true)
        watchTask = null

        try {
            mediaPlayer?.stop()
        } catch (_: Exception) {
        }
        try {
            mediaPlayer?.release()
        } catch (_: Exception) {
        }
        mediaPlayer = null

        try {
            vibrator?.cancel()
        } catch (_: Exception) {
        }
        vibrator = null

        val endedIntent = Intent(CallSignalConstants.ACTION_CALL_ENDED_BROADCAST).apply {
            setPackage(packageName)
        }
        sendBroadcast(endedIntent)

        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        try {
            watchTask?.cancel(true)
            mediaPlayer?.release()
            vibrator?.cancel()
            scheduler.shutdownNow()
        } catch (_: Exception) {
        }
        super.onDestroy()
    }
}
