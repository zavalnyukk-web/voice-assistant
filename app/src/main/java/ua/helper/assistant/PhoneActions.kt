package ua.helper.assistant

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.provider.ContactsContract
import android.provider.Settings
import android.view.KeyEvent
import java.util.Locale

/** Керування телефоном: програми, дзвінки, повідомлення, ліхтарик, гучність, музика. */
class PhoneActions(private val context: Context) {

    private val uk = Locale("uk", "UA")

    private val aliases: Map<String, List<String>> = mapOf(
        "вайбер" to listOf("viber"),
        "ютуб" to listOf("youtube"),
        "телеграм" to listOf("telegram"),
        "ватсап" to listOf("whatsapp"),
        "вотсап" to listOf("whatsapp"),
        "інстаграм" to listOf("instagram"),
        "фейсбук" to listOf("facebook"),
        "тікток" to listOf("tiktok"),
        "карти" to listOf("maps"),
        "мапи" to listOf("maps"),
        "камера" to listOf("camera"),
        "галерея" to listOf("photos", "gallery"),
        "фото" to listOf("photos", "gallery"),
        "хром" to listOf("chrome"),
        "браузер" to listOf("chrome", "browser"),
        "пошта" to listOf("gmail", "mail"),
        "календар" to listOf("calendar"),
        "годинник" to listOf("clock"),
        "калькулятор" to listOf("calculator"),
        "налаштування" to listOf("settings"),
        "спотіфай" to listOf("spotify"),
        "музика" to listOf("music", "spotify"),
        "телефон" to listOf("dialer", "phone"),
        "контакти" to listOf("contacts"),
        "повідомлення" to listOf("messaging", "messages"),
        "диск" to listOf("drive"),
        "приват" to listOf("privat"),
        "моно" to listOf("mono")
    )

    private fun hasPerm(p: String) =
        context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun cleanup(t: String, junk: List<String>): String {
        var s = " $t "
        for (w in junk) s = s.replace(" $w ", " ")
        return s.trim().replace(Regex("\\s+"), " ")
    }

    // ---------- програми ----------

    fun openApp(t: String): String {
        val name = cleanup(
            t,
            listOf("відкрий", "відкрити", "запусти", "запустити", "програму", "додаток", "застосунок", "мені", "будь ласка")
        )
        if (name.isEmpty()) return "Яку програму відкрити?"

        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val apps = pm.queryIntentActivities(launcher, 0)

        val keys = mutableListOf(name)
        for ((k, v) in aliases) {
            if (name == k || name.startsWith(k.dropLast(1))) keys.addAll(v)
        }
        val hit = apps.firstOrNull { ri ->
            val label = ri.loadLabel(pm).toString().lowercase(uk)
            val pkg = ri.activityInfo.packageName.lowercase(uk)
            keys.any { label.contains(it) || pkg.contains(it) }
        } ?: return "Не знайшов програму «$name»."

        val label = hit.loadLabel(pm).toString()
        val intent = pm.getLaunchIntentForPackage(hit.activityInfo.packageName)
            ?: return "Не вдалося відкрити «$label»."
        Launcher.start(context, intent, "Відкрити $label")
        return "Відкриваю $label."
    }

    // ---------- контакти ----------

    private class Contact(val name: String, val number: String)

    private fun commonPrefix(a: String, b: String): Int {
        var i = 0
        while (i < a.length && i < b.length && a[i] == b[i]) i++
        return i
    }

    /** Порівнюємо слова за початком, щоб «мамі», «мамо» знаходили контакт «Мама». */
    private fun score(spokenWords: List<String>, name: String): Int {
        var best = 0
        for (nw in name.split(" ", "-", ".")) {
            if (nw.length < 3) continue
            for (sw in spokenWords) {
                val p = commonPrefix(sw, nw)
                if (p >= maxOf(3, minOf(sw.length, nw.length) - 2) && p > best) best = p
            }
        }
        return best
    }

    private fun findContact(spoken: String): Contact? {
        val words = spoken.lowercase(uk).split(" ").filter { it.length >= 3 }
        if (words.isEmpty()) return null
        var best: Contact? = null
        var bestScore = 0
        val cursor = context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            null, null, null
        ) ?: return null
        cursor.use {
            while (it.moveToNext()) {
                val name = it.getString(0) ?: continue
                val number = it.getString(1) ?: continue
                val s = score(words, name.lowercase(uk))
                if (s > bestScore) {
                    bestScore = s
                    best = Contact(name, number)
                }
            }
        }
        return best
    }

    private val needContacts =
        "Щоб працювати з контактами, відкрийте Арчіка й натисніть «Дозволити контакти й дзвінки»."

    fun call(t: String): String {
        val s = cleanup(
            t,
            listOf(
                "подзвони", "подзвонити", "зателефонуй", "зателефонувати", "телефонуй",
                "набери", "мені", "будь ласка", "на номер"
            )
        )
        if (s.isEmpty()) return "Кому подзвонити?"

        val number: String
        val display: String
        val digits = s.filter { it.isDigit() }
        if (digits.length >= 7) {
            number = digits
            display = digits
        } else {
            if (!hasPerm(Manifest.permission.READ_CONTACTS)) return needContacts
            val c = findContact(s) ?: return "Не знайшов у контактах «$s»."
            number = c.number
            display = c.name
        }
        val action = if (hasPerm(Manifest.permission.CALL_PHONE)) Intent.ACTION_CALL else Intent.ACTION_DIAL
        Launcher.start(context, Intent(action, Uri.parse("tel:" + Uri.encode(number))), "Подзвонити: $display")
        return "Дзвоню: $display."
    }

    fun message(t: String): String {
        val viber = t.contains("вайбер")
        val s = cleanup(
            t,
            listOf(
                "напиши", "написати", "відправ", "відправити", "надішли", "надіслати",
                "повідомлення", "смс", "sms", "есемес", "в вайбері", "у вайбері",
                "через вайбер", "в вайбер", "у вайбер", "вайбері", "вайбер",
                "мені", "будь ласка", "листа"
            )
        )
        if (s.isEmpty()) return "Кому написати?"
        if (!hasPerm(Manifest.permission.READ_CONTACTS)) return needContacts

        val words = s.split(" ")
        var contact: Contact? = null
        var used = 0
        for (n in 1..minOf(2, words.size)) {
            val c = findContact(words.take(n).joinToString(" "))
            if (c != null) {
                contact = c
                used = n
                break
            }
        }
        val c = contact ?: return "Не знайшов у контактах «${words[0]}»."

        var text = words.drop(used).joinToString(" ").trim()
            .replace(Regex("^(що|текст)\\s+"), "")
        text = text.replaceFirstChar { it.uppercase() }

        val digits = c.number.filter { it.isDigit() }
        val intent = if (viber) {
            val intl = if (digits.length == 10 && digits.startsWith("0")) "38$digits" else digits
            var uri = "viber://chat?number=$intl"
            if (text.isNotEmpty()) uri += "&draft=" + Uri.encode(text)
            Intent(Intent.ACTION_VIEW, Uri.parse(uri))
        } else {
            Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(c.number)))
                .putExtra("sms_body", text)
        }
        Launcher.start(context, intent, "Повідомлення: ${c.name}")
        return if (text.isEmpty()) {
            "Відкриваю чат з ${c.name}."
        } else {
            "Готую повідомлення для ${c.name}. Натисніть «Надіслати» на екрані."
        }
    }

    // ---------- ліхтарик, гучність, музика ----------

    fun torch(on: Boolean): String {
        return try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return "На цьому телефоні немає ліхтарика."
            cm.setTorchMode(id, on)
            if (on) "Ліхтарик увімкнено." else "Ліхтарик вимкнено."
        } catch (e: Exception) {
            "Не вдалося керувати ліхтариком."
        }
    }

    fun volume(t: String): String {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val flag = AudioManager.FLAG_SHOW_UI
        val pct = Regex("(\\d{1,3}) ?(відсотк\\S*|%)").find(t)
        return when {
            pct != null -> {
                val p = pct.groupValues[1].toInt().coerceIn(0, 100)
                am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(max * p / 100.0).toInt(), flag)
                "Гучність $p відсотків."
            }
            t.contains("максимум") || t.contains("максимальн") || t.contains("на повну") -> {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, max, flag)
                "Гучність на максимум."
            }
            t.contains("мінімум") || t.contains("мінімальн") -> {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, 1, flag)
                "Гучність на мінімум."
            }
            t.contains("вимкни звук") || t.contains("без звуку") -> {
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, flag)
                "Звук вимкнено."
            }
            t.contains("увімкни звук") -> {
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, flag)
                "Звук увімкнено."
            }
            t.contains("тихіш") || t.contains("зменш") -> {
                repeat(2) { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, flag) }
                "Зробив тихіше."
            }
            t.contains("гучніш") || t.contains("збільш") || t.contains("підніми") -> {
                repeat(2) { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, flag) }
                "Зробив гучніше."
            }
            else -> "Скажіть: гучніше, тихіше або гучність 50 відсотків."
        }
    }

    /** Керування музикою в будь-якому плеєрі. Повертає null, якщо це не команда для музики. */
    fun media(t: String): String? {
        val key = when {
            t.contains("наступн") && (t.contains("пісн") || t.contains("трек") || t.contains("композиц")) ->
                KeyEvent.KEYCODE_MEDIA_NEXT
            t.contains("попередн") && (t.contains("пісн") || t.contains("трек") || t.contains("композиц")) ->
                KeyEvent.KEYCODE_MEDIA_PREVIOUS
            t.contains("пауза") || t.contains("паузу") || t.contains("зупини музику") ||
                t.contains("вимкни музику") || t.contains("зупини пісню") -> KeyEvent.KEYCODE_MEDIA_PAUSE
            t.contains("увімкни музику") || t.contains("включи музику") || t.contains("грай музику") ||
                t.contains("продовж музику") -> KeyEvent.KEYCODE_MEDIA_PLAY
            else -> return null
        }
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
        return "Добре."
    }

    // ---------- налаштування, батарея, навігація, пошук ----------

    fun wifi(): String {
        val i = if (Build.VERSION.SDK_INT >= 29) {
            Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
        } else {
            Intent(Settings.ACTION_WIFI_SETTINGS)
        }
        Launcher.start(context, i, "Налаштування Wi-Fi")
        return "Android не дозволяє програмам вмикати Wi-Fi самостійно. Відкриваю панель, натисніть перемикач."
    }

    fun bluetooth(): String {
        Launcher.start(context, Intent(Settings.ACTION_BLUETOOTH_SETTINGS), "Налаштування Bluetooth")
        return "Android не дозволяє програмам вмикати Bluetooth самостійно. Відкриваю налаштування."
    }

    fun battery(): String {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val p = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return "Заряд батареї $p відсотків."
    }

    fun navigate(t: String): String {
        val place = cleanup(
            t,
            listOf("прокладі", "маршрут", "навігація", "навігацію", "як", "доїхати", "до", "мені", "будь ласка", "увімкни")
        )
        if (place.isEmpty()) return "Куди їхати?"
        Launcher.start(
            context,
            Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(place))),
            "Карта: $place"
        )
        return "Шукаю на карті: $place."
    }

    fun search(t: String): String {
        val q = cleanup(
            t,
            listOf("загугли", "погугли", "нагугли", "знайди", "пошукай", "в інтернеті", "в гуглі", "мені", "будь ласка")
        )
        if (q.isEmpty()) return "Що знайти?"
        Launcher.start(
            context,
            Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(q))),
            "Пошук: $q"
        )
        return "Шукаю в інтернеті: $q."
    }
}
