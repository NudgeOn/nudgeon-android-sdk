package io.nudgeon.sdk

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.net.HttpURLConnection
import java.net.URL

/**
 * 공통 계약(docs-public/PUSH-CONTRACT.md)의 FCM data-only 푸시를 시스템 알림으로 표시한다.
 * data-only는 OS가 알림을 만들지 않으므로 SDK가 직접 그린다 — `NudgeOnConfig.autoDisplayNotifications`(기본 true).
 *
 * - 제목·본문, `image_url`이 있으면 큰 그림(BigPicture), 없으면 BigText.
 * - 탭 → 앱 런처 액티비티. 원본 data 맵을 extras에 그대로 실어 [NudgeOn.handleLaunchIntent]가 `$push_opened`·딥링크로 잇는다.
 * - 알림 권한이 없으면 표시하지 않는다(수신 이벤트는 이미 기록됨).
 *
 * 호출 스레드: FirebaseMessagingService.onMessageReceived의 백그라운드 스레드. 이미지는 5초 안에 못 받으면 그림 없이 표시.
 */
internal object PushNotifications {
    /** 런처 인텐트에 붙는 마커 — [NudgeOn.handleLaunchIntent]가 NudgeOn 푸시 탭임을 식별한다. */
    const val EXTRA_MARKER = "io.nudgeon.push"
    private const val IMAGE_TIMEOUT_MS = 5_000

    fun show(context: Context, config: NudgeOnConfig, payload: PushPayload, data: Map<String, String>) {
        if (!canNotify(context)) {
            NudgeOnLog.warn("알림 권한 없음 — 표시 생략(message_id=${payload.messageId})")
            return
        }
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                config.notificationChannelId,
                config.notificationChannelName,
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
        val id = payload.messageId.hashCode()
        val builder = NotificationCompat.Builder(context, config.notificationChannelId)
            .setSmallIcon(smallIcon(context, config))
            .setContentTitle(payload.title.ifBlank { appLabel(context) })
            .setContentText(payload.body)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        launchIntent(context, data)?.let { intent ->
            builder.setContentIntent(
                PendingIntent.getActivity(
                    context, id, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        }
        val image = payload.imageUrl?.let(::fetchImage)
        if (image != null) {
            builder.setLargeIcon(image)
                .setStyle(NotificationCompat.BigPictureStyle().bigPicture(image).bigLargeIcon(null as Bitmap?))
        } else if (payload.body.isNotBlank()) {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(payload.body))
        }
        manager.notify(id, builder.build())
    }

    /** 앱 런처 액티비티 + 원본 data extras (+ 마커). 런처가 없는 패키지(라이브러리 테스트)면 null. */
    private fun launchIntent(context: Context, data: Map<String, String>): Intent? {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        data.forEach { (k, v) -> intent.putExtra(k, v) }
        intent.putExtra(EXTRA_MARKER, "1")
        return intent
    }

    private fun canNotify(context: Context): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        return true
    }

    private fun smallIcon(context: Context, config: NudgeOnConfig): Int =
        if (config.notificationSmallIcon != 0) config.notificationSmallIcon
        else context.applicationInfo.icon.takeIf { it != 0 } ?: android.R.drawable.ic_dialog_info

    private fun appLabel(context: Context): String =
        context.applicationInfo.loadLabel(context.packageManager).toString()

    private fun fetchImage(url: String): Bitmap? = runCatching {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = IMAGE_TIMEOUT_MS
        conn.readTimeout = IMAGE_TIMEOUT_MS
        conn.instanceFollowRedirects = true
        try {
            if (conn.responseCode !in 200..299) return null
            conn.inputStream.use { BitmapFactory.decodeStream(it) }
        } finally {
            conn.disconnect()
        }
    }.onFailure { NudgeOnLog.warn("알림 이미지 로드 실패($url): ${it.message}") }.getOrNull()
}
