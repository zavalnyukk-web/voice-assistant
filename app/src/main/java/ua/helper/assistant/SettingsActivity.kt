package ua.helper.assistant

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SettingsActivity : Activity() {

    private lateinit var storage: Storage
    private lateinit var col: LinearLayout
    private lateinit var wakeSwitch: Switch
    private lateinit var wakeWordInput: EditText
    private lateinit var keyInput: EditText
    private lateinit var ttsLangView: TextView
    private lateinit var recogLangView: TextView
    private lateinit var voiceView: TextView
    private lateinit var rateView: TextView

    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private val matchParent = ViewGroup.LayoutParams.MATCH_PARENT
    private val wrap = ViewGroup.LayoutParams.WRAP_CONTENT

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Ui.BG
        window.navigationBarColor = Ui.BG
        storage = Storage(this)
        tts = TextToSpeech(this) { status -> ttsReady = status == TextToSpeech.SUCCESS }
        buildUi()
        refreshValues()
    }

    override fun onPause() {
        save()
        super.onPause()
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }

    private fun dp(v: Int) = Ui.dp(this, v)

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    // ---------- збереження ----------

    private fun save() {
        storage.wakeEnabled = wakeSwitch.isChecked
        storage.wakeWord = wakeWordInput.text.toString()
        storage.apiKey = keyInput.text.toString()
        if (AssistantService.running) {
            try {
                startService(
                    Intent(this, AssistantService::class.java).setAction(AssistantService.ACTION_RELOAD)
                )
            } catch (e: Exception) {
                // застосується під час наступного запуску
            }
        }
    }

    // ---------- побудова екрана ----------

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(8))
        }
        header.addView(
            TextView(this).apply {
                text = "←"
                textSize = 22f
                gravity = Gravity.CENTER
                setTextColor(Ui.TEXT)
                background = Ui.circle(Ui.SURFACE2)
                setOnClickListener { finish() }
            },
            LinearLayout.LayoutParams(dp(44), dp(44))
        )
        header.addView(
            TextView(this).apply {
                text = "Налаштування"
                textSize = 22f
                setTextColor(Ui.TEXT)
                typeface = Typeface.DEFAULT_BOLD
            },
            LinearLayout.LayoutParams(wrap, wrap).apply { marginStart = dp(14) }
        )
        root.addView(header)

        col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, dp(14), dp(28))
        }
        root.addView(
            ScrollView(this).apply {
                isVerticalScrollBarEnabled = false
                addView(col)
            },
            LinearLayout.LayoutParams(matchParent, 0, 1f)
        )

        // --- голос і мова ---
        val voice = section("ГОЛОС І МОВА")
        ttsLangView = valueRow(voice, "Мова відтворення") {
            pickLanguage(storage.ttsLang) {
                storage.ttsLang = it
                storage.ttsVoice = ""
                refreshValues()
            }
        }
        recogLangView = valueRow(voice, "Мова розпізнавання") {
            pickLanguage(storage.recogLang) {
                storage.recogLang = it
                refreshValues()
            }
        }
        voiceView = valueRow(voice, "Голос") { pickVoice() }

        rateView = TextView(this).apply {
            textSize = 16f
            setTextColor(Ui.TEXT)
            setPadding(0, dp(12), 0, dp(4))
        }
        voice.addView(rateView)
        val seek = SeekBar(this).apply {
            max = 100
            progress = ((storage.speechRate - 0.5f) * 100).toInt().coerceIn(0, 100)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    storage.speechRate = 0.5f + p / 100f
                    refreshValues()
                }

                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        voice.addView(seek)
        actionButton(voice, "Прослухати зразок", true) { speakSample() }
        voice.addView(
            TextView(this).apply {
                text = "Команди керування телефоном працюють українською. " +
                    "Іншу мову обирайте для розмови з ШІ. Голоси додаються в налаштуваннях телефону: " +
                    "Мова → Синтез мовлення."
                textSize = 13f
                setTextColor(Ui.MUTED)
                setPadding(0, dp(12), 0, 0)
            }
        )

        // --- активація ---
        val wake = section("АКТИВАЦІЯ")
        wakeSwitch = Switch(this).apply {
            text = "Слухати слово-активацію постійно"
            textSize = 16f
            setTextColor(Ui.TEXT)
            isChecked = storage.wakeEnabled
            setPadding(0, dp(8), 0, dp(8))
        }
        wake.addView(wakeSwitch)
        wakeWordInput = field("Слово-активація", storage.wakeWord, false)
        wake.addView(wakeWordInput, LinearLayout.LayoutParams(matchParent, wrap).apply { topMargin = dp(6) })

        // --- ШІ ---
        val ai = section("ШТУЧНИЙ ІНТЕЛЕКТ")
        keyInput = field("Ключ (Gemini або Claude)", storage.apiKey, true)
        ai.addView(keyInput, LinearLayout.LayoutParams(matchParent, wrap).apply { topMargin = dp(6) })
        actionButton(ai, "Отримати безкоштовний ключ Gemini") {
            openUrl("https://aistudio.google.com/apikey")
        }
        ai.addView(
            TextView(this).apply {
                text = "Ключ зберігається лише на цьому телефоні. Нікому його не показуйте."
                textSize = 13f
                setTextColor(Ui.MUTED)
                setPadding(0, dp(10), 0, 0)
            }
        )

        // --- дозволи ---
        val perms = section("ДОЗВОЛИ")
        actionButton(perms, "Контакти й дзвінки") {
            requestPermissions(
                arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE), 11
            )
        }
        actionButton(perms, "Відкривати програми з фону") {
            try {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                )
            } catch (e: Exception) {
                toast("Відкрийте вручну: Програми → Арчік → Поверх інших вікон.")
            }
        }
        actionButton(perms, "Не обмежувати батарею") {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e: Exception) {
                toast("Відкрийте налаштування батареї вручну.")
            }
        }

        // --- дані ---
        val data = section("ДАНІ")
        actionButton(data, "Мої нотатки, покупки, нагадування") { showData() }
        actionButton(data, "Очистити розмову") {
            synchronized(AssistantService.log) { AssistantService.log.clear() }
            toast("Розмову очищено.")
        }

        // --- про застосунок ---
        val about = section("ПРО АРЧІКА")
        about.addView(
            TextView(this).apply {
                text = "Арчік 2.1: голосовий помічник для керування телефоном і розмови."
                textSize = 14f
                setTextColor(Ui.MUTED)
                setPadding(0, dp(6), 0, dp(6))
            }
        )

        setContentView(root)
    }

    // ---------- елементи ----------

    private fun section(title: String): LinearLayout {
        col.addView(
            TextView(this).apply {
                text = title
                textSize = 13f
                setTextColor(Ui.MUTED)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(6), dp(18), dp(6), dp(8))
            }
        )
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(this@SettingsActivity, Ui.SURFACE, 20)
            setPadding(dp(16), dp(8), dp(16), dp(14))
        }
        col.addView(card, LinearLayout.LayoutParams(matchParent, wrap))
        return card
    }

    private fun valueRow(card: LinearLayout, title: String, onClick: () -> Unit): TextView {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(12))
            setOnClickListener { onClick() }
        }
        row.addView(
            TextView(this).apply {
                text = title
                textSize = 16f
                setTextColor(Ui.TEXT)
            },
            LinearLayout.LayoutParams(0, wrap, 1f)
        )
        val value = TextView(this).apply {
            textSize = 15f
            setTextColor(Ui.ACCENT)
        }
        row.addView(value)
        card.addView(row)
        return value
    }

    private fun actionButton(
        card: LinearLayout,
        label: String,
        primary: Boolean = false,
        onClick: () -> Unit
    ) {
        val b = TextView(this).apply {
            text = label
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(Ui.TEXT)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(14), dp(13), dp(14), dp(13))
            background = if (primary) {
                Ui.gradient(this@SettingsActivity, Ui.ACCENT, Ui.ACCENT2, 14)
            } else {
                Ui.rounded(this@SettingsActivity, Ui.SURFACE2, 14)
            }
            setOnClickListener { onClick() }
        }
        card.addView(b, LinearLayout.LayoutParams(matchParent, wrap).apply { topMargin = dp(10) })
    }

    private fun field(hintText: String, value: String, password: Boolean): EditText =
        EditText(this).apply {
            hint = hintText
            setText(value)
            setTextColor(Ui.TEXT)
            setHintTextColor(Ui.MUTED)
            textSize = 16f
            background = Ui.rounded(this@SettingsActivity, Ui.SURFACE2, 14)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setSingleLine(true)
            inputType = if (password) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            } else {
                InputType.TYPE_CLASS_TEXT
            }
        }

    private fun refreshValues() {
        ttsLangView.text = Languages.find(storage.ttsLang).label + "  ›"
        recogLangView.text = Languages.find(storage.recogLang).label + "  ›"
        voiceView.text = (if (storage.ttsVoice.isEmpty()) "Стандартний" else "Свій") + "  ›"
        rateView.text = String.format(Locale.US, "Швидкість мовлення: ×%.1f", storage.speechRate)
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            toast("Не вдалося відкрити браузер.")
        }
    }

    // ---------- діалоги ----------

    private fun dialog() = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)

    private fun pickLanguage(current: String, onPick: (String) -> Unit) {
        val items = Languages.all.map { it.label }.toTypedArray()
        val index = Languages.all.indexOfFirst { it.tag == current }.coerceAtLeast(0)
        dialog()
            .setTitle("Оберіть мову")
            .setSingleChoiceItems(items, index) { d, which ->
                onPick(Languages.all[which].tag)
                d.dismiss()
            }
            .setNegativeButton("Скасувати", null)
            .show()
    }

    private fun applyVoice(engine: TextToSpeech) {
        val name = storage.ttsVoice
        if (name.isEmpty()) return
        try {
            engine.voices?.firstOrNull { it.name == name }?.let { engine.setVoice(it) }
        } catch (e: Exception) {
            // залишаємо стандартний голос
        }
    }

    private fun pickVoice() {
        val engine = tts
        if (engine == null || !ttsReady) {
            toast("Голоси ще завантажуються, спробуйте за секунду.")
            return
        }
        val lang = Locale.forLanguageTag(storage.ttsLang).language
        val voices = try {
            (engine.voices ?: emptySet()).filter { it.locale.language == lang }.sortedBy { it.name }
        } catch (e: Exception) {
            emptyList()
        }
        if (voices.isEmpty()) {
            toast("Для цієї мови немає голосів. Встановіть їх: Налаштування → Мова → Синтез мовлення.")
            return
        }
        val names = mutableListOf("Стандартний")
        voices.forEachIndexed { i, v ->
            names.add("Голос ${i + 1} (" + (if (v.isNetworkConnectionRequired) "онлайн" else "на телефоні") + ")")
        }
        dialog()
            .setTitle("Оберіть голос")
            .setItems(names.toTypedArray()) { _, which ->
                storage.ttsVoice = if (which == 0) "" else voices[which - 1].name
                refreshValues()
                speakSample()
            }
            .setNegativeButton("Скасувати", null)
            .show()
    }

    private fun speakSample() {
        val engine = tts
        if (engine == null || !ttsReady) {
            toast("Озвучення ще завантажується, спробуйте за секунду.")
            return
        }
        val lang = Languages.find(storage.ttsLang)
        val r = engine.setLanguage(Locale.forLanguageTag(lang.tag))
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            toast("Для цієї мови немає голосу на телефоні. Встановіть його: Налаштування → Мова → Синтез мовлення.")
            return
        }
        applyVoice(engine)
        engine.setSpeechRate(storage.speechRate)
        engine.speak(lang.sample, TextToSpeech.QUEUE_FLUSH, null, "sample")
    }

    private fun showData() {
        val notes = storage.getList("notes")
        val shopping = storage.getList("shopping")
        val reminders = storage.getReminders().sortedBy { it.time }
        val fmt = SimpleDateFormat("d MMMM, HH:mm", Locale("uk", "UA"))
        val sb = StringBuilder()
        sb.append("Нотатки:\n")
        if (notes.isEmpty()) sb.append("— порожньо\n") else notes.forEach { sb.append("• ").append(it).append("\n") }
        sb.append("\nСписок покупок:\n")
        if (shopping.isEmpty()) sb.append("— порожньо\n") else shopping.forEach { sb.append("• ").append(it).append("\n") }
        sb.append("\nБудильники й нагадування:\n")
        if (reminders.isEmpty()) {
            sb.append("— немає\n")
        } else {
            reminders.forEach {
                sb.append("• ").append(fmt.format(Date(it.time))).append(" — ").append(it.text).append("\n")
            }
        }
        dialog().setTitle("Мої дані").setMessage(sb.toString()).setPositiveButton("Ок", null).show()
    }
}
