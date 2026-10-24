package ua.helper.assistant

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

/** alarm = true для будильників і таймерів (дзвонять як будильник), false для звичайних нагадувань. */
data class Reminder(val id: Int, val time: Long, val text: String, val alarm: Boolean = false)

class Storage(context: Context) {
    private val prefs =
        context.applicationContext.getSharedPreferences("assistant", Context.MODE_PRIVATE)

    var apiKey: String
        get() = prefs.getString("api_key", "") ?: ""
        set(v) {
            prefs.edit().putString("api_key", v.trim()).apply()
        }

    var wakeEnabled: Boolean
        get() = prefs.getBoolean("wake_enabled", true)
        set(v) {
            prefs.edit().putBoolean("wake_enabled", v).apply()
        }

    var wakeWord: String
        get() = prefs.getString("wake_word", "арчік") ?: "арчік"
        set(v) {
            val word = v.trim().lowercase()
            prefs.edit().putString("wake_word", if (word.isEmpty()) "арчік" else word).apply()
        }

    var ttsLang: String
        get() = prefs.getString("tts_lang", "uk-UA") ?: "uk-UA"
        set(v) {
            prefs.edit().putString("tts_lang", v).apply()
        }

    var recogLang: String
        get() = prefs.getString("recog_lang", "uk-UA") ?: "uk-UA"
        set(v) {
            prefs.edit().putString("recog_lang", v).apply()
        }

    var ttsVoice: String
        get() = prefs.getString("tts_voice", "") ?: ""
        set(v) {
            prefs.edit().putString("tts_voice", v).apply()
        }

    var speechRate: Float
        get() = prefs.getFloat("speech_rate", 1.0f)
        set(v) {
            prefs.edit().putFloat("speech_rate", v).apply()
        }

    private fun parseArray(key: String): JSONArray =
        try {
            JSONArray(prefs.getString(key, "[]"))
        } catch (e: Exception) {
            JSONArray()
        }

    fun getList(name: String): MutableList<String> {
        val arr = parseArray("list_$name")
        val out = mutableListOf<String>()
        for (i in 0 until arr.length()) out.add(arr.getString(i))
        return out
    }

    fun saveList(name: String, items: List<String>) {
        prefs.edit().putString("list_$name", JSONArray(items).toString()).apply()
    }

    fun getReminders(): MutableList<Reminder> {
        val arr = parseArray("reminders")
        val out = mutableListOf<Reminder>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(
                Reminder(o.getInt("id"), o.getLong("time"), o.getString("text"), o.optBoolean("alarm", false))
            )
        }
        return out
    }

    private fun saveReminders(list: List<Reminder>) {
        val arr = JSONArray()
        for (r in list) {
            arr.put(
                JSONObject().put("id", r.id).put("time", r.time).put("text", r.text).put("alarm", r.alarm)
            )
        }
        prefs.edit().putString("reminders", arr.toString()).apply()
    }

    fun addReminder(time: Long, text: String, alarm: Boolean = false): Reminder {
        val id = prefs.getInt("next_id", 1)
        prefs.edit().putInt("next_id", id + 1).apply()
        val r = Reminder(id, time, text, alarm)
        val list = getReminders()
        list.add(r)
        saveReminders(list)
        return r
    }

    fun removeReminder(id: Int) {
        saveReminders(getReminders().filter { it.id != id })
    }
}

object Reminders {
    fun schedule(context: Context, r: Reminder) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ReminderReceiver::class.java)
            .putExtra("id", r.id)
            .putExtra("text", r.text)
            .putExtra("alarm", r.alarm)
        val pi = PendingIntent.getBroadcast(
            context, r.id, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        if (exact) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.time, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.time, pi)
        }
    }

    fun cancel(context: Context, r: Reminder) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(
            context, r.id, Intent(context, ReminderReceiver::class.java),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pi != null) {
            am.cancel(pi)
            pi.cancel()
        }
    }

    fun rescheduleAll(context: Context) {
        val storage = Storage(context)
        val now = System.currentTimeMillis()
        for (r in storage.getReminders()) {
            if (r.time > now) schedule(context, r) else storage.removeReminder(r.id)
        }
    }
}
