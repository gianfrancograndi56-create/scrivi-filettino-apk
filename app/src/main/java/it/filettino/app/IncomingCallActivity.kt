package it.filettino.app

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class IncomingCallActivity : AppCompatActivity() {

    private var callId: String = ""
    private var callType: String = "audio"
    private var callerId: String = ""
    private var callerName: String = "Iscritto Filettino"
    private var callerEmail: String = ""

    private val callEndedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            finishAndRemoveTask()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContentView(R.layout.activity_incoming_call)
        readExtras(intent)
        bindUi()

        ContextCompat.registerReceiver(
            this,
            callEndedReceiver,
            IntentFilter(CallSignalConstants.ACTION_CALL_ENDED_BROADCAST),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readExtras(intent)
        bindUi()
    }

    private fun readExtras(intent: Intent?) {
        callId = intent?.getStringExtra(CallSignalConstants.EXTRA_CALL_ID) ?: ""
        callType = intent?.getStringExtra(CallSignalConstants.EXTRA_CALL_TYPE) ?: "audio"
        callerId = intent?.getStringExtra(CallSignalConstants.EXTRA_CALLER_ID) ?: ""
        callerName = intent?.getStringExtra(CallSignalConstants.EXTRA_CALLER_NAME) ?: "Iscritto Filettino"
        callerEmail = intent?.getStringExtra(CallSignalConstants.EXTRA_CALLER_EMAIL) ?: ""
    }

    private fun bindUi() {
        val tvBadge = findViewById<TextView>(R.id.tvCallBadge)
        val tvInitials = findViewById<TextView>(R.id.tvCallerInitials)
        val tvCallerName = findViewById<TextView>(R.id.tvCallerName)
        val btnAccept = findViewById<ImageButton>(R.id.btnAccept)
        val btnDecline = findViewById<ImageButton>(R.id.btnDecline)

        tvBadge.text = if (callType == "video") "VIDEOCHIAMATA IN ARRIVO" else "CHIAMATA VOCALE IN ARRIVO"
        tvCallerName.text = callerName
        val initials = callerName.trim().take(2).uppercase().ifEmpty { "FT" }
        tvInitials.text = initials

        btnAccept.setOnClickListener {
            RingtoneForegroundService.stopRinging(this)
            CallSignalConstants.savePendingCallAction(
                this,
                callId,
                "accept",
                callType,
                callerId,
                callerName,
                callerEmail
            )

            val launchMain = {
                val mainIntent = Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra(CallSignalConstants.EXTRA_CALL_ID, callId)
                    putExtra(CallSignalConstants.EXTRA_CALL_ACTION, "accept")
                    putExtra(CallSignalConstants.EXTRA_CALL_TYPE, callType)
                    putExtra(CallSignalConstants.EXTRA_CALLER_ID, callerId)
                    putExtra(CallSignalConstants.EXTRA_CALLER_NAME, callerName)
                    putExtra(CallSignalConstants.EXTRA_CALLER_EMAIL, callerEmail)
                }
                startActivity(mainIntent)
                finishAndRemoveTask()
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val km = getSystemService(KeyguardManager::class.java)
                if (km.isKeyguardLocked) {
                    km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                        override fun onDismissSucceeded() {
                            launchMain()
                        }

                        override fun onDismissError() {
                            launchMain()
                        }

                        override fun onDismissCancelled() {
                            launchMain()
                        }
                    })
                } else {
                    launchMain()
                }
            } else {
                launchMain()
            }
        }

        btnDecline.setOnClickListener {
            RingtoneForegroundService.stopRinging(this)
            val targetId = callId
            val appCtx = applicationContext
            Thread {
                CallSignalConstants.patchCallStatus(targetId, "declined", appCtx)
            }.start()
            finishAndRemoveTask()
        }
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(callEndedReceiver)
        } catch (_: Exception) {
        }
        super.onDestroy()
    }
}
