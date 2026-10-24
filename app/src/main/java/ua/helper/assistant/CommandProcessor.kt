package ua.helper.assistant

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Розпізнає голосові команди: час, нагадування, будильники, нотатки, покупки та керування телефоном.
 * Якщо команду не розпізнано, handle() повертає null, і фраза йде до ШІ.
 */
class CommandProcessor(private val context: Context, private val storage: Storage) {

    private val uk = Locale("uk", "UA")
    private val phone = PhoneActions(context)

    private class SpokenError(message: String) : Exception(message)
    private class When(val millis: Long, val rest: String, val explicitDay: Boolean)

    private val hourWords = mapOf(
        "першій" to 1, "першу" to 1, "другій" to 2, "другу" to 2,
        "третій" to 3, "третю" to 3, "четвертій" to 4, "четверту" to 4,
        "пятій" to 5, "пяту" to 5, "шостій" to 6, "шосту" to 6,
        "сьомій" to 7, "сьому" to 7, "восьмій" to 8, "восьму" to 8,
        "девятій" to 9, "девяту" to 9, "десятій" to 10, "десяту" to 10,
        "одинадцятій" to 11, "одинадцяту" to 11, "дванадцятій" to 12, "дванадцяту" to 12
    )

    fun handle(raw: String): String? {
        val t = normalize(raw)
        if (t.isEmpty()) return null
        return try {
            route(t)
        } catch (e: SpokenError) {
            e.message
        } catch (e: SecurityException) {
            "Не вистачає дозволу. Відкрийте Арчіка й дайте потрібні дозволи."
        }
    }

    private fun has(t: String, vararg words: String) = words.any { t.contains(it) }
    private fun starts(t: String, vararg words: String) = words.any { t.startsWith(it) }

    private fun route(t: String): String? {
        if (has(t, "скасуй", "скасувати", "видали", "відміни", "прибери") &&
            has(t, "будильник", "таймер", "нагадуван")
        ) return cancel(t)
        if (has(t, "які будильник", "які нагадуван", "мої нагадуван", "мої будильник",
                "покажи нагадуван", "покажи будильник", "які таймер")
        ) return listReminders()
        if ((has(t, "будильник") && has(t, "постав", "завед", "створи", "встанови", "на ")) ||
            starts(t, "розбуди")
        ) return alarm(t)
        if (has(t, "таймер")) return timer(t)
        if (starts(t, "нагадай", "нагадати") || has(t, "постав нагадування", "створи нагадування")) {
            return reminder(t)
        }
        if (isShopping(t)) return shopping(t)
        if (isNote(t)) return notes(t)

        if (has(t, "ліхтарик", "ліхтар")) {
            return phone.torch(!has(t, "вимкни", "вимкнути", "погаси", "загаси", "виключи"))
        }
        if (has(t, "гучніш", "тихіш", "гучність") ||
            (has(t, "звук") && has(t, "вимкни", "увімкни", "без звуку"))
        ) return phone.volume(t)
        phone.media(t)?.let { return it }
        if (has(t, "вайфай")) return phone.wifi()
        if (has(t, "блютуз", "bluetooth")) return phone.bluetooth()
        if (has(t, "заряд", "батаре") && has(t, "скільки", "який", "яка")) return phone.battery()

        if (starts(t, "подзвони", "зателефонуй", "телефонуй", "набери", "подзвонити")) return phone.call(t)
        if (starts(t, "напиши", "відправ", "надішли", "написати", "відправити")) return phone.message(t)
        if (starts(t, "відкрий", "запусти", "відкрити", "запустити")) return phone.openApp(t)
        if (has(t, "маршрут", "навігац", "як доїхати", "прокладі")) return phone.navigate(t)
        if (starts(t, "загугли", "погугли", "нагугли") || has(t, "в інтернеті знайди", "знайди в інтернеті")) {
            return phone.search(t)
        }

        if (has(t, "котра година", "який час", "скільки зараз часу")) {
            return "Зараз " + SimpleDateFormat("HH:mm", uk).format(Date()) + "."
        }
        if (has(t, "яке сьогодні число", "яка сьогодні дата", "який сьогодні день")) {
            return "Сьогодні " + SimpleDateFormat("d MMMM, EEEE", uk).format(Date()) + "."
        }
        return null
    }

    private fun normalize(s: String): String =
        s.lowercase(uk)
            .replace("'", "").replace("’", "").replace("ʼ", "")
            .replace("wi-fi", "вайфай").replace("wifi", "вайфай").replace("wi fi", "вайфай")
            .replace("вай фай", "вайфай").replace("вай-фай", "вайфай")
            .replace(Regex("[,.!?;]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .removePrefix("будь ласка ")

    // ---------- час: нагадування, будильники, таймери ----------

    private fun parseWhen(t: String, allowNa: Boolean): When {
        val now = System.currentTimeMillis()
        var rest = t

        val relWord = Regex("через (півгодини|пів години|півтори години|годину|хвилину)").find(rest)
        val relNum = Regex("через (\\d+) ?(секунд|сек|хвилин|хв|годин|год)\\S*").find(rest)
        if (relWord != null) {
            val ms = when (relWord.groupValues[1]) {
                "півгодини", "пів години" -> 30 * 60_000L
                "півтори години" -> 90 * 60_000L
                "годину" -> 60 * 60_000L
                else -> 60_000L
            }
            return When(now + ms, rest.replace(relWord.value, " "), true)
        }
        if (relNum != null) {
            val n = relNum.groupValues[1].toLong()
            val mult = when (relNum.groupValues[2]) {
                "секунд", "сек" -> 1_000L
                "хвилин", "хв" -> 60_000L
                else -> 3_600_000L
            }
            return When(now + n * mult, rest.replace(relNum.value, " "), true)
        }

        var dayOffset = 0
        var explicitDay = false
        when {
            rest.contains("післязавтра") -> {
                dayOffset = 2
                explicitDay = true
                rest = rest.replace("післязавтра", " ")
            }
            rest.contains("завтра") -> {
                dayOffset = 1
                explicitDay = true
                rest = rest.replace("завтра", " ")
            }
            rest.contains("сьогодні") -> {
                explicitDay = true
                rest = rest.replace("сьогодні", " ")
            }
        }

        val prep = if (allowNa) "о|об|в|у|на" else "о|об|в|у"
        var hour = -1
        var minute = 0
        val digits = Regex(
            "(?:^| )(?:$prep) (\\d{1,2})(?:[: ](\\d{2}))?(?: (?:годині|год))?(?= |$)"
        ).find(rest)
        if (digits != null) {
            hour = digits.groupValues[1].toInt()
            minute = digits.groupValues[2].toIntOrNull() ?: 0
            rest = rest.replaceFirst(digits.value, " ")
        } else {
            val words = Regex(
                "(?:^| )(?:$prep) (" + hourWords.keys.joinToString("|") + ")(?= |$)"
            ).find(rest)
            if (words != null) {
                hour = hourWords[words.groupValues[1]] ?: -1
                rest = rest.replaceFirst(words.value, " ")
            }
        }
        if (hour < 0) {
            throw SpokenError(
                "Не зрозумів, на який час. Скажіть, наприклад: завтра о сьомій ранку, або через 30 хвилин."
            )
        }
        if (hour > 23 || minute > 59) throw SpokenError("Не вдалося розпізнати час. Спробуйте ще раз.")

        val periodRegex = Regex(" (ввечері|вечора|вдень|дня|вночі|ночі|зранку|ранку|вранці)(?= |$)")
        val padded = " $rest"
        val periods = periodRegex.findAll(padded).map { it.groupValues[1] }.toList()
        rest = periodRegex.replace(padded, " ")
        val pm = periods.any { it in listOf("ввечері", "вечора", "вдень", "дня") }
        val night = periods.any { it == "вночі" || it == "ночі" }
        val morning = periods.any { it in listOf("зранку", "ранку", "вранці") }

        if (pm && hour in 1..11) hour += 12
        if (night && hour == 12) hour = 0
        if (night && hour in 8..11) hour += 12

        val cal = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, dayOffset)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= now) {
            if (!explicitDay && !pm && !night && !morning && hour in 1..11) {
                val evening = cal.clone() as Calendar
                evening.add(Calendar.HOUR_OF_DAY, 12)
                if (evening.timeInMillis > now) {
                    cal.timeInMillis = evening.timeInMillis
                } else {
                    cal.add(Calendar.DAY_OF_YEAR, 1)
                }
            } else if (!explicitDay) {
                cal.add(Calendar.DAY_OF_YEAR, 1)
            } else {
                throw SpokenError("Цей час уже минув.")
            }
        }
        return When(cal.timeInMillis, rest, explicitDay)
    }

    private fun reminder(t: String): String {
        val w = parseWhen(t, false)
        var text = " ${w.rest} "
        for (x in listOf("постав нагадування", "створи нагадування", "нагадай", "нагадати", "мені")) {
            text = text.replace(" $x ", " ")
        }
        text = text.trim().replace(Regex("\\s+"), " ")
            .replace(Regex("^(про|що|щоб я|щоб)\\s+"), "")
        if (text.isEmpty()) text = "нагадування"

        val r = storage.addReminder(w.millis, text)
        Reminders.schedule(context, r)
        val label = SimpleDateFormat("d MMMM 'о' HH:mm", uk).format(Date(w.millis))
        return "Гаразд, нагадаю $label: $text."
    }

    private fun alarm(t: String): String {
        val w = parseWhen(t, true)
        val r = storage.addReminder(w.millis, "Будильник", alarm = true)
        Reminders.schedule(context, r)
        val label = SimpleDateFormat("EEEE, d MMMM 'о' HH:mm", uk).format(Date(w.millis))
        return "Будильник поставлено: $label."
    }

    private fun timer(t: String): String {
        val m = Regex("(\\d+) ?(секунд|сек|хвилин|хв|годин|год)\\S*").find(t)
            ?: return "На скільки поставити таймер? Наприклад: таймер на 10 хвилин."
        val n = m.groupValues[1].toLong()
        val mult = when (m.groupValues[2]) {
            "секунд", "сек" -> 1_000L
            "хвилин", "хв" -> 60_000L
            else -> 3_600_000L
        }
        val r = storage.addReminder(System.currentTimeMillis() + n * mult, "Таймер завершився", alarm = true)
        Reminders.schedule(context, r)
        return "Таймер запущено: ${m.value}."
    }

    private fun cancel(t: String): String {
        val wantAlarm = has(t, "будильник", "таймер")
        val target = storage.getReminders().filter { if (wantAlarm) it.alarm else !it.alarm }
        for (r in target) {
            Reminders.cancel(context, r)
            storage.removeReminder(r.id)
        }
        return if (target.isEmpty()) "Таких немає." else "Скасовано: ${target.size}."
    }

    private fun listReminders(): String {
        val list = storage.getReminders().sortedBy { it.time }
        if (list.isEmpty()) return "Активних будильників і нагадувань немає."
        val fmt = SimpleDateFormat("d MMMM 'о' HH:mm", uk)
        return "Активних: ${list.size}. " +
            list.take(5).joinToString("; ") { fmt.format(Date(it.time)) + " — " + it.text } + "."
    }

    // ---------- список покупок ----------

    private fun isShopping(t: String) =
        t.contains("список покупок") || t.contains("списку покупок") ||
            t.contains("списком покупок") || t.contains("що купити") ||
            t.startsWith("купити") || t.startsWith("треба купити") ||
            t.startsWith("потрібно купити")

    private fun shopping(t: String): String {
        val items = storage.getList("shopping")
        if (t.contains("очисти") || t.contains("очистити") ||
            t.contains("видали все") || t.contains("видали всі")
        ) {
            storage.saveList("shopping", emptyList())
            return "Список покупок очищено."
        }
        val addStarts = listOf(
            "додай", "додати", "запиши", "занеси", "внеси",
            "купити", "треба купити", "потрібно купити"
        )
        if (addStarts.any { t.startsWith(it) }) {
            val added = extractItems(t)
            if (added.isEmpty()) return "Що саме додати до списку покупок?"
            items.addAll(added)
            storage.saveList("shopping", items)
            return "Додав до списку покупок: " + added.joinToString(", ") + "."
        }
        return if (items.isEmpty()) "Список покупок порожній."
        else "У списку покупок: " + items.joinToString(", ") + "."
    }

    private fun extractItems(t: String): List<String> {
        var s = " $t "
        val junk = listOf(
            "в список покупок", "у список покупок", "до списку покупок",
            "у списку покупок", "в списку покупок", "список покупок",
            "в список", "у список", "до списку",
            "потрібно купити", "треба купити", "купити",
            "додай", "додати", "запиши", "занеси", "внеси",
            "будь ласка", "мені"
        )
        for (j in junk) s = s.replace(" $j ", " ")
        return s.trim().split(" і ", " та ").map { it.trim() }.filter { it.isNotEmpty() }
    }

    // ---------- нотатки ----------

    private fun isNote(t: String) =
        t.contains("нотатк") || t.startsWith("запиши") ||
            t.startsWith("занотуй") || t.startsWith("запамятай")

    private fun notes(t: String): String {
        val items = storage.getList("notes")
        if (t.contains("нотатк") &&
            (t.contains("очисти") || t.contains("очистити") ||
                t.contains("видали все") || t.contains("видали всі"))
        ) {
            storage.saveList("notes", emptyList())
            return "Нотатки очищено."
        }
        val readWords = listOf("прочитай", "покажи", "мої", "які", "що в", "що у", "перелічи")
        if (t.contains("нотатк") && !t.startsWith("запиши") && readWords.any { t.contains(it) }) {
            if (items.isEmpty()) return "Нотаток поки немає."
            val last = items.takeLast(5)
            return "Нотаток: ${items.size}. " +
                last.mapIndexed { i, s -> "${i + 1}) $s" }.joinToString("; ") + "."
        }

        var text = t
        val triggers = listOf(
            "запиши нотатку", "створи нотатку", "додай нотатку", "нова нотатка",
            "нотатка", "запиши", "занотуй", "запамятай"
        )
        for (tr in triggers) {
            if (text.startsWith(tr)) {
                text = text.removePrefix(tr)
                break
            }
        }
        text = text.trim().replace(Regex("^(що|:)\\s+"), "").trim()
        if (text.isEmpty()) return "Що саме записати?"
        text = text.replaceFirstChar { it.uppercase() }
        items.add(text)
        storage.saveList("notes", items)
        return "Записав нотатку."
    }
}
