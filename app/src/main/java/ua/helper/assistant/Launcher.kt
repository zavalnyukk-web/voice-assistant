package ua.helper.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * Запуск екранів з фонової служби. Android блокує такі запуски, якщо програма не на екрані,
 * тому: або програма видима, або дано дозвіл «Поверх інших вікон», або показуємо
 * сповіщення, яке відкриває потрібний екран (на вимкненому екрані воно відкриється саме).
 */
object Launcher {
    @Volatile
    var appVisible = false

    fun start(context: Context, intent: Intent, title: String): Boolean {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (appVisible || Settings.canDrawOverlays(context)) {
            try {
                context.startActivity(intent)
                return true
            } catch (e: Exception) {
                // переходимо до сповіщення
            }
        }
        notifyTap(context, intent, title)
        return false
    }

    private fun notifyTap(context: Context, intent: Intent, title: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel("actions", "Дії Арчіка", NotificationManager.IMPORTANCE_HIGH)
        )
        val code = (System.currentTimeMillis() % 100000).toInt()
        val pi = PendingIntent.getActivity(
            context, code, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(context, "actions")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(title)
            .setContentText("Натисніть, щоб виконати")
            .setContentIntent(pi)
            .setFullScreenIntent(pi, true)
            .setAutoCancel(true)
            .build()
        nm.notify(2000 + code % 1000, n)
    }
}
