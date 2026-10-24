package ua.helper.assistant

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.Animation
import android.view.animation.ScaleAnimation
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var storage: Storage
    private lateinit var statusText: TextView
    private lateinit var statusDot: View
    private lateinit var powerBtn: TextView
    private lateinit var chatBox: LinearLayout
    private lateinit var chatScroll: ScrollView
    private lateinit var ring: View
    private lateinit var askInput: EditText

    private val handler = Handler(Looper.getMainLooper())
    private var pendingAction: (() -> Unit)? = null
    private var pulsing = false

    private val matchParent = ViewGroup.LayoutParams.MATCH_PARENT
    private val wrap = ViewGroup.LayoutParams.WRAP_CONTENT

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Ui.BG
        window.navigationBarColor = Ui.BG
        storage = Storage(this)
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        Launcher.appVisible = true
        AssistantService.logListener = { runOnUiThread { renderChat() } }
        AssistantService.stateListener = { runOnUiThread { refreshState() } }
        refreshState()
        renderChat()
    }

    override fun onPause() {
        Launcher.appVisible = false
        AssistantService.logListener = null
        AssistantService.stateListener = null
        setPulse(false)
        super.onPause()
    }

    private fun dp(v: Int) = Ui.dp(this, v)

    // ---------- інтерфейс ----------

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
        }

        // шапка: логотип, назва, кнопка вмикання, налаштування
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(4))
        }
        header.addView(
            ImageView(this).apply { setImageResource(R.mipmap.ic_launcher_foreground) },
            LinearLayout.LayoutParams(dp(60), dp(60))
        )
        header.addView(
            TextView(this).apply {
                text = "Арчік"
                textSize = 26f
                setTextColor(Ui.TEXT)
                typeface = Typeface.DEFAULT_BOLD
            },
            LinearLayout.LayoutParams(0, wrap, 1f)
        )
        powerBtn = TextView(this).apply {
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(9), dp(16), dp(9))
            setOnClickListener { toggleService() }
        }
        header.addView(powerBtn)
        val gear = TextView(this).apply {
            text = "⚙"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(Ui.TEXT)
            background = Ui.circle(Ui.SURFACE2)
            setOnClickListener {
                startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
            }
        }
        header.addView(gear, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(10) })
        root.addView(header)

        // статус
        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(2), dp(20), dp(6))
        }
        statusDot = View(this)
        statusRow.addView(statusDot, LinearLayout.LayoutParams(dp(10), dp(10)))
        statusText = TextView(this).apply {
            textSize = 14f
            setTextColor(Ui.MUTED)
        }
        statusRow.addView(statusText, LinearLayout.LayoutParams(wrap, wrap).apply { marginStart = dp(8) })
        root.addView(statusRow)

        // розмова
        chatBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(6), dp(14), dp(14))
        }
        chatScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(chatBox)
        }
        root.addView(chatScroll, LinearLayout.LayoutParams(matchParent, 0, 1f))

        // велика кнопка мікрофона з пульсуючим кільцем
        val orbArea = FrameLayout(this)
        ring = View(this).apply {
            background = Ui.ring(Ui.ACCENT, dp(3))
            visibility = View.INVISIBLE
        }
        orbArea.addView(ring, FrameLayout.LayoutParams(dp(112), dp(112), Gravity.CENTER))
        val orb = TextView(this).apply {
            text = "🎤"
            textSize = 30f
            gravity = Gravity.CENTER
            background = Ui.circleGradient(Ui.ACCENT, Ui.ACCENT2)
            setOnClickListener { onOrbClick() }
        }
        orbArea.addView(orb, FrameLayout.LayoutParams(dp(84), dp(84), Gravity.CENTER))
        root.addView(orbArea, LinearLayout.LayoutParams(matchParent, dp(120)))

        // поле введення
        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(12))
        }
        askInput = EditText(this).apply {
            hint = "Напишіть Арчіку…"
            setHintTextColor(Ui.MUTED)
            setTextColor(Ui.TEXT)
            textSize = 16f
            background = Ui.rounded(this@MainActivity, Ui.SURFACE, 26)
            setPadding(dp(18), dp(12), dp(18), dp(12))
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    sendTyped()
                    true
                } else {
                    false
                }
            }
        }
        inputRow.addView(askInput, LinearLayout.LayoutParams(0, wrap, 1f))
        val send = TextView(this).apply {
            text = "↑"
            textSize = 24f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            background = Ui.circleGradient(Ui.ACCENT, Ui.ACCENT2)
            setOnClickListener { sendTyped() }
        }
        inputRow.addView(send, LinearLayout.LayoutParams(dp(50), dp(50)).apply { marginStart = dp(10) })
        root.addView(inputRow)

        setContentView(root)
    }

    private fun refreshState() {
        val running = AssistantService.running
        val mode = if (running) AssistantService.mode else "off"
        val (label, color) = when (mode) {
            "wake" -> "Слухає: скажіть «${storage.wakeWord}»" to Ui.GREEN
            "command" -> "Слухаю вас…" to Ui.ACCENT
            "busy" -> "Думаю…" to Ui.AMBER
            "idle" -> "Очікує: торкніться мікрофона" to Ui.GREEN
            else -> if (running) ("Запускаюсь…" to Ui.AMBER) else ("Вимкнено" to Ui.MUTED)
        }
        statusText.text = label
        statusDot.background = Ui.circle(color)
        powerBtn.text = if (running) "Вимкнути" else "Увімкнути"
        powerBtn.setTextColor(if (running) Ui.BG else Ui.TEXT)
        powerBtn.background = Ui.rounded(this, if (running) Ui.GREEN else Ui.SURFACE2, 22)
        setPulse(mode == "wake" || mode == "command")
    }

    private fun setPulse(on: Boolean) {
        if (on == pulsing) return
        pulsing = on
        if (on) {
            ring.visibility = View.VISIBLE
            val anim = ScaleAnimation(
                0.8f, 1.12f, 0.8f, 1.12f,
                Animation.RELATIVE_TO_SELF, 0.5f, Animation.RELATIVE_TO_SELF, 0.5f
            ).apply {
                duration = 1100
                repeatCount = Animation.INFINITE
                repeatMode = Animation.REVERSE
            }
            ring.startAnimation(anim)
        } else {
            ring.clearAnimation()
            ring.visibility = View.INVISIBLE
        }
    }

    // ---------- розмова ----------

    private fun renderChat() {
        val lines = synchronized(AssistantService.log) { AssistantService.log.toList() }
        chatBox.removeAllViews()
        if (lines.isEmpty()) {
            addWelcome()
        } else {
            lines.forEach { addBubble(it) }
        }
        chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun addBubble(line: String) {
        val isUser = line.startsWith("Ви: ")
        val isBot = line.startsWith("Арчік: ")
        val text = when {
            isUser -> line.removePrefix("Ви: ")
            isBot -> line.removePrefix("Арчік: ")
            else -> line
        }
        val tv = TextView(this).apply {
            this.text = text
            textSize = if (isUser || isBot) 16f else 13f
            setTextColor(if (isUser) Color.WHITE else if (isBot) Ui.TEXT else Ui.MUTED)
            if (isUser || isBot) {
                setPadding(dp(14), dp(10), dp(14), dp(10))
                background = if (isUser) {
                    Ui.gradient(this@MainActivity, Ui.ACCENT, Ui.ACCENT2, 18)
                } else {
                    Ui.rounded(this@MainActivity, Ui.SURFACE2, 18)
                }
            } else {
                gravity = Gravity.CENTER
            }
        }
        val lp = LinearLayout.LayoutParams(wrap, wrap).apply {
            topMargin = dp(8)
            when {
                isUser -> {
                    gravity = Gravity.END
                    marginStart = dp(56)
                }
                isBot -> {
                    gravity = Gravity.START
                    marginEnd = dp(56)
                }
                else -> {
                    gravity = Gravity.CENTER_HORIZONTAL
                }
            }
        }
        chatBox.addView(tv, lp)
    }

    private fun addWelcome() {
        chatBox.addView(
            TextView(this).apply {
                text = "Привіт! Я Арчік."
                textSize = 24f
                setTextColor(Ui.TEXT)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(4), dp(24), dp(4), dp(4))
            }
        )
        chatBox.addView(
            TextView(this).apply {
                text = "Увімкніть мене, скажіть «${storage.wakeWord}» і команду або торкніться мікрофона. " +
                    "Спробуйте:"
                textSize = 15f
                setTextColor(Ui.MUTED)
                setPadding(dp(4), 0, dp(4), dp(8))
            }
        )
        val ideas = listOf(
            "Котра година?" to "котра година",
            "Увімкни ліхтарик" to "увімкни ліхтарик",
            "Постав будильник на 7:30" to "постав будильник на 7:30",
            "Подзвони мамі" to "подзвони мамі",
            "Відкрий Вайбер" to "відкрий вайбер",
            "Розкажи жарт" to "розкажи жарт"
        )
        for ((label, command) in ideas) {
            val chip = TextView(this).apply {
                text = label
                textSize = 15f
                setTextColor(Ui.TEXT)
                setPadding(dp(16), dp(12), dp(16), dp(12))
                background = Ui.rounded(this@MainActivity, Ui.SURFACE, 16)
                setOnClickListener { sendToService(AssistantService.ACTION_TEXT, command) }
            }
            chatBox.addView(chip, LinearLayout.LayoutParams(matchParent, wrap).apply { topMargin = dp(8) })
        }
    }

    // ---------- дії ----------

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun sendTyped() {
        val q = askInput.text.toString().trim()
        if (q.isNotEmpty()) {
            askInput.setText("")
            sendToService(AssistantService.ACTION_TEXT, q)
        }
    }

    private fun onOrbClick() {
        if (AssistantService.running) {
            sendToService(AssistantService.ACTION_LISTEN, null)
        } else {
            sendToService(AssistantService.ACTION_START, null)
        }
    }

    private fun toggleService() {
        if (AssistantService.running) {
            startService(Intent(this, AssistantService::class.java).setAction(AssistantService.ACTION_STOP))
            handler.postDelayed({ refreshState() }, 600)
        } else {
            sendToService(AssistantService.ACTION_START, null)
        }
    }

    private fun sendToService(action: String, text: String?) {
        ensurePermissions {
            val i = Intent(this, AssistantService::class.java).setAction(action)
            if (text != null) i.putExtra("text", text)
            try {
                if (AssistantService.running) startService(i) else startForegroundService(i)
            } catch (e: Exception) {
                toast("Не вдалося запустити Арчіка: ${e.message}")
            }
            handler.postDelayed({ refreshState() }, 700)
        }
    }

    // ---------- дозволи ----------

    private fun ensurePermissions(then: () -> Unit) {
        val need = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            need.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (need.isEmpty()) {
            then()
        } else {
            pendingAction = then
            requestPermissions(need.toTypedArray(), 10)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 10) {
            val action = pendingAction
            pendingAction = null
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                action?.invoke()
            } else {
                toast("Без дозволу на мікрофон Арчік не працює.")
            }
        }
    }
}
