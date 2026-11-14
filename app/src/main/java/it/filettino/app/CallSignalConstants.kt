package it.filettino.app

import android.content.Context
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object CallSignalConstants {
    const val APP_URL = "https://filettino-app.pages.dev"
    const val FIRESTORE_BASE =
        "https://firestore.googleapis.com/v1/projects/mythic-influence-mtvkm/databases/ai-studio-filettino-e06cb800-c2bc-4daa-b72f-877e6d2d580d/documents"

    const val PREFS_NAME = "filettino_native_prefs"
    const val KEY_FCM_TOKEN = "fcm_token"
    const val KEY_USER_ID = "user_id"
    const val KEY_PENDING_CALL_JSON = "pending_call_json"

    const val CHANNEL_INCOMING_CALLS = "filettino_incoming_calls_v1"
    const val NOTIF_ID_INCOMING_CALL = 3302

    const val ACTION_STOP_RINGING = "it.filettino.app.ACTION_STOP_RINGING"
    const val ACTION_DECLINE_CALL = "it.filettino.app.ACTION_DECLINE_CALL"
    const val ACTION_CALL_ENDED_BROADCAST = "it.filettino.app.ACTION_CALL_ENDED"

    const val EXTRA_CALL_ID = "extra_call_id"
    const val EXTRA_CALL_TYPE = "extra_call_type"
    const val EXTRA_CALLER_ID = "extra_caller_id"
    const val EXTRA_CALLER_NAME = "extra_caller_name"
    const val EXTRA_CALLER_EMAIL = "extra_caller_email"
    const val EXTRA_CALL_ACTION = "extra_call_action"

    fun savePendingCallAction(
        context: Context,
        callId: String,
        action: String,
        callType: String,
        callerId: String,
        callerName: String,
        callerEmail: String
    ) {
        val json = JSONObject().apply {
            put("callId", callId)
            put("action", action)
            put("callType", callType)
            put("callerId", callerId)
            put("callerName", callerName)
            put("callerEmail", callerEmail)
            put("savedAt", System.currentTimeMillis())
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PENDING_CALL_JSON, json.toString())
            .apply()
    }

    fun isCallStillRinging(callId: String): Boolean {
        if (callId.isBlank() || callId.startsWith("push_")) return true
        return try {
            val encoded = URLEncoder.encode(callId, "UTF-8")
            val url = URL("$FIRESTORE_BASE/chiamate/$encoded")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 4000
                readTimeout = 4000
                requestMethod = "GET"
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                conn.disconnect()
                return true
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            val root = JSONObject(body)
            val fields = root.optJSONObject("fields") ?: return true
            val statusObj = fields.optJSONObject("status") ?: return true
            val status = statusObj.optString("stringValue", "ringing")
            status == "ringing"
        } catch (_: Exception) {
            true
        }
    }

    fun patchCallStatus(callId: String, status: String) {
        if (callId.isBlank() || callId.startsWith("push_")) return
        try {
            val encoded = URLEncoder.encode(callId, "UTF-8")
            val url = URL("$FIRESTORE_BASE/chiamate/$encoded?updateMask.fieldPaths=status")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 5000
                requestMethod = "PATCH"
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                doOutput = true
            }
            val payload = JSONObject().apply {
                put(
                    "fields",
                    JSONObject().put("status", JSONObject().put("stringValue", status))
                )
            }
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(payload.toString())
            }
            conn.responseCode
            conn.disconnect()
        } catch (_: Exception) {
        }
    }

    fun patchUserFcmToken(userId: String, fcmToken: String) {
        if (userId.isBlank() || fcmToken.isBlank()) return
        try {
            val encoded = URLEncoder.encode(userId, "UTF-8")
            val url = URL("$FIRESTORE_BASE/utenti/$encoded?updateMask.fieldPaths=fcmToken&updateMask.fieldPaths=fcmUpdatedAt")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 5000
                requestMethod = "PATCH"
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                doOutput = true
            }
            val nowIso = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }.format(java.util.Date())
            val fields = JSONObject().apply {
                put("fcmToken", JSONObject().put("stringValue", fcmToken))
                put("fcmUpdatedAt", JSONObject().put("stringValue", nowIso))
            }
            val payload = JSONObject().put("fields", fields)
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(payload.toString())
            }
            conn.responseCode
            conn.disconnect()
        } catch (_: Exception) {
        }
    }
}
