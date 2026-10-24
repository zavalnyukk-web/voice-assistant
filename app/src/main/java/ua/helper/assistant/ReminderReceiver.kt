package ua.helper.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra("id", 0)
        val text = intent.getStringExtra("text") ?: return
        val alarm = intent.getBooleanExtra("alarm", false)
        Storage(context).removeReminder(id)

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE
        )

        val builder: Notification.Builder
        if (alarm) {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val channel = NotificationChannel("alarms", "Будильники й таймери", NotificationManager.IMPORTANCE_HIGH)
            channel.setSound(
                uri,
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            channel.enableVibration(true)
            channel.vibrationPattern = longArrayOf(0, 800, 400, 800, 400, 800)
            nm.createNotificationChannel(channel)
            builder = Notification.Builder(context, "alarms")
                .setCategory(Notification.CATEGORY_ALARM)
                .setFullScreenIntent(open, true)
                .setTimeoutAfter(120_000)
                .setContentTitle(text)
                .setContentText("Натисніть, щоб відкрити Арчіка")
        } else {
            nm.createNotificationChannel(
                NotificationChannel("reminders", "Нагадування", NotificationManager.IMPORTANCE_HIGH)
            )
            builder = Notification.Builder(context, "reminders")
                .setContentTitle("Нагадування")
                .setContentText(text)
        }

        val n = builder
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        if (alarm) n.flags = n.flags or Notification.FLAG_INSISTENT
        nm.notify(1000 + id, n)

        if (!alarm && AssistantService.running) {
            try {
                context.startService(
                    Intent(context, AssistantService::class.java)
                        .setAction(AssistantService.ACTION_SPEAK)
                        .putExtra("text", "Нагадування: $text")
                )
            } catch (e: Exception) {
                // сповіщення вже показано
            }
        }
    }
}
