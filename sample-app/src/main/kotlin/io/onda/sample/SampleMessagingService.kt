package io.onda.sample

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import io.onda.sdk.Onda

class SampleMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        Onda.setPushToken(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = PushIntentData.from(message.data)
        if (!Onda.handleRemoteMessage(data)) {
            super.onMessageReceived(message)
            return
        }
        showNotification(data)
    }

    private fun showNotification(data: Map<String, String>) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val canNotify = manager.areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < 33 ||
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
        if (!canNotify) {
            Log.w(TAG, "알림 권한이 없어 수신 이벤트만 전달하고 알림 UI는 표시하지 않습니다")
            return
        }
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.push_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )

        val messageId = data.getValue("message_id")
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            data.forEach { (key, value) -> putExtra(key, value) }
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            messageId.hashCode(),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(data["title"].orEmpty().ifBlank { getString(R.string.app_name) })
            .setContentText(data["body"].orEmpty())
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        manager.notify(messageId.hashCode(), notification)
    }

    private companion object {
        const val CHANNEL_ID = "onda_sample_push"
        const val TAG = "OndaSamplePush"
    }
}
