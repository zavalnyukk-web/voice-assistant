package ua.helper.assistant

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

class AssistantService : Service() {

    companion object {
        const val ACTION_START = "ua.helper.assistant.START"
        const val ACTION_STOP = "ua.helper.assistant.STOP"
        const val ACTION_LISTEN = "ua.helper.assistant.LISTEN"
        const val ACTION_RELOAD = "ua.helper.assistant.RELOAD"
        const val ACTION_TEXT = "ua.helper.assistant.TEXT"
        const val ACTION_SPEAK = "ua.helper.assistant.SPEAK"

        private const val CHANNEL_ID = "assistant_service"
        private const val NOTIF_ID = 1

        @Volatile
        var running = false

        /** off, idle, wake, command, busy: для індикатора в інтерфейсі */
        @Volatile
        var mode = "off"
        var stateListener: (() -> Unit)? = null

        fun setMode(m: String) {
            mode = m
            stateListener?.invoke()
        }

        val log = ArrayList<String>()
        var logListener: ((String) -> Unit)? = null

        fun addLog(line: String) {
            synchronized(log) {
                log.add(line)
                while (log.size > 60) log.removeAt(0)
            }
            logListener?.invoke(line)
        }
    }

    private val uk = Locale("uk", "UA")
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var storage: Storage
    private lateinit var ai: AiClient
    private lateinit var processor: CommandProcessor

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var ttsUkOk = false
    private var warnedTts = false
    private val speakCallbacks = HashMap<String, () -> Unit>()
    private var utteranceCounter = 0

    private var recognizer: SpeechRecognizer? = null
    private var listeningForCommand = false
    private var busy = false
    private var stopped = false
    private var foregroundStarted = false
    private var lastNotifText = ""
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        storage = Storage(this)
        ai = AiClient(storage)
        processor = CommandProcessor(this, storage)
        createChannel()
        initTts()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP) {
            stopAssistant()
            return START_NOT_STICKY
        }
        try {
            ensureForeground()
        } catch (e: Exception) {
            addLog("Не вдалося запустити фонову службу: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }
        updateWakeLock()

        when (action) {
            ACTION_LISTEN -> if (!busy) {
                handler.removeCallbacks(restartRunnable)
                startListening(true)
            }
            ACTION_RELOAD -> {
                applyTtsSettings()
                if (!busy) {
                    destroyRecognizer()
                    scheduleNext(100)
                }
            }
            ACTION_TEXT -> {
                val text = intent?.getStringExtra("text")
                if (!text.isNullOrBlank() && !busy) {
                    handler.removeCallbacks(restartRunnable)
                    destroyRecognizer()
                    process(text)
                }
            }
            ACTION_SPEAK -> {
                val text = intent?.getStringExtra("text")
                if (!text.isNullOrBlank() && !busy) {
                    busy = true
                    setMode("busy")
                    handler.removeCallbacks(restartRunnable)
                    addLog("Арчік: $text")
                    speak(text) {
                        busy = false
                        scheduleNext(300)
                    }
                }
            }
            else -> if (!busy) scheduleNext(100)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopped = true
        running = false
        setMode("off")
        handler.removeCallbacksAndMessages(null)
        destroyRecognizer()
        tts?.stop()
        tts?.shutdown()
        tts = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    // ---------- керування ----------

    private fun stopAssistant() {
        stopped = true
        running = false
        setMode("off")
        handler.removeCallbacksAndMessages(null)
        destroyRecognizer()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private val restartRunnable = Runnable {
        if (stopped || busy) return@Runnable
        updateWakeLock()
        if (storage.wakeEnabled) {
            updateNotification("Слухаю: скажіть «${storage.wakeWord}»")
            startListening(false)
        } else {
            destroyRecognizer()
            setMode("idle")
            updateNotification("Очікую. Натисніть «Слухати»")
        }
    }

    private fun scheduleNext(delayMs: Long) {
        handler.removeCallbacks(restartRunnable)
        handler.postDelayed(restartRunnable, delayMs)
    }

    private fun updateWakeLock() {
        val need = storage.wakeEnabled && !stopped
        if (need && wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "assistant:listen")
                .apply { acquire() }
        } else if (!need) {
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
        }
    }

    // ---------- розпізнавання мовлення ----------

    private fun startListening(command: Boolean) {
        if (stopped) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            addLog("Немає дозволу на мікрофон. Дайте його в налаштуваннях телефону.")
            stopAssistant()
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            addLog("На телефоні немає служби розпізнавання мовлення. Встановіть або оновіть застосунок Google.")
            stopAssistant()
            return
        }
        listeningForCommand = command
        setMode(if (command) "command" else "wake")
        if (command) updateNotification("Слухаю команду…")
        destroyRecognizer()
        try {
            val r = SpeechRecognizer.createSpeechRecognizer(this)
            r.setRecognitionListener(recognitionListener)
            recognizer = r
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, storage.recogLang)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, storage.recogLang)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            }
            r.startListening(intent)
        } catch (e: Exception) {
            scheduleNext(2000)
        }
    }

    private fun destroyRecognizer() {
        val r = recognizer ?: return
        recognizer = null
        try {
            r.cancel()
            r.destroy()
        } catch (e: Exception) {
            // ігноруємо
        }
    }

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onError(error: Int) {
            if (stopped) return
            when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    addLog("Немає дозволу на мікрофон.")
                    stopAssistant()
                }
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> scheduleNext(250)
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                SpeechRecognizer.ERROR_CLIENT -> scheduleNext(1000)
                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                SpeechRecognizer.ERROR_SERVER -> scheduleNext(3000)
                else -> scheduleNext(2000)
            }
        }

        override fun onResults(results: Bundle?) {
            if (stopped) return
            val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?: arrayListOf()
            handleResults(list)
        }
    }

    private fun handleResults(list: List<String>) {
        if (list.isEmpty()) {
            scheduleNext(250)
            return
        }
        if (listeningForCommand) {
            listeningForCommand = false
            process(list[0])
            return
        }

        val full = storage.wakeWord
        // шукаємо основу слова, щоб ловити «Арчік», «Арчі», «Арчіку»
        val ww = if (full.length >= 5) full.dropLast(1) else full
        val hit = list.firstOrNull { it.lowercase(uk).contains(ww) }
        if (hit == null) {
            scheduleNext(150)
            return
        }
        val lower = hit.lowercase(uk)
        val idx = lower.indexOf(ww)
        var end = idx + ww.length
        while (end < lower.length && lower[end].isLetter()) end++
        val after = lower.substring(end).trim { !it.isLetterOrDigit() }
        val before = lower.substring(0, idx).trim { !it.isLetterOrDigit() }
        when {
            after.length >= 3 -> process(after)
            before.length >= 3 -> process(before)
            else -> {
                busy = true
                speak("Слухаю") {
                    busy = false
                    startListening(true)
                }
            }
        }
    }

    // ---------- обробка команд ----------

    private fun process(text: String) {
        busy = true
        setMode("busy")
        addLog("Ви: $text")
        val lower = text.lowercase(uk).trim { !it.isLetterOrDigit() }
        if (lower in setOf("стоп", "зупинись", "вимкнись", "вимкни помічника", "вимкни арчіка", "відбій", "годі")) {
            reply("Вимикаюся.") { stopAssistant() }
            return
        }
        val local = try {
            processor.handle(text)
        } catch (e: Exception) {
            null
        }
        if (local != null) {
            reply(local)
            return
        }
        if (storage.apiKey.isBlank()) {
            reply("Щоб я відповідав на запитання, додайте ключ для штучного інтелекту в налаштуваннях застосунку.")
            return
        }
        updateNotification("Думаю…")
        ai.ask(text) { answer -> reply(answer, followUp = true) }
    }

    /**
     * followUp = true: після відповіді одразу слухаємо далі без слова-активації (режим розмови).
     * Якщо людина мовчить, Арчік повертається до очікування слова «Арчік».
     */
    private fun reply(text: String, followUp: Boolean = false, after: (() -> Unit)? = null) {
        if (stopped) return
        addLog("Арчік: $text")
        speak(text) {
            busy = false
            when {
                after != null -> after()
                followUp && !stopped -> startListening(true)
                else -> scheduleNext(300)
            }
        }
    }

    // ---------- озвучення ----------

    private fun initTts() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val engine = tts
                if (engine != null) {
                    engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {}
                        override fun onDone(utteranceId: String?) {
                            finishUtterance(utteranceId)
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            finishUtterance(utteranceId)
                        }

                        override fun onError(utteranceId: String?, errorCode: Int) {
                            finishUtterance(utteranceId)
                        }
                    })
                    ttsReady = true
                    applyTtsSettings()
                }
            }
        }
    }

    private fun applyTtsSettings() {
        val engine = tts ?: return
        if (!ttsReady) return
        val res = engine.setLanguage(Locale.forLanguageTag(storage.ttsLang))
        ttsUkOk = res != TextToSpeech.LANG_MISSING_DATA && res != TextToSpeech.LANG_NOT_SUPPORTED
        val voiceName = storage.ttsVoice
        if (voiceName.isNotEmpty()) {
            try {
                engine.voices?.firstOrNull { it.name == voiceName }?.let { engine.setVoice(it) }
            } catch (e: Exception) {
                // залишаємо стандартний голос
            }
        }
        engine.setSpeechRate(storage.speechRate)
        warnedTts = false
    }

    private fun finishUtterance(id: String?) {
        handler.post { speakCallbacks.remove(id)?.invoke() }
    }

    private fun speak(text: String, attempt: Int = 0, done: () -> Unit) {
        destroyRecognizer()
        val engine = tts
        if (engine == null || !ttsReady) {
            if (attempt < 10) {
                handler.postDelayed({ speak(text, attempt + 1, done) }, 400)
            } else {
                done()
            }
            return
        }
        if (!ttsUkOk) {
            if (!warnedTts) {
                warnedTts = true
                addLog("Немає голосу для вибраної мови озвучення. Встановіть його: Налаштування → Мова → Синтез мовлення.")
            }
            done()
            return
        }
        val id = "u${++utteranceCounter}"
        speakCallbacks[id] = done
        val r = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        if (r != TextToSpeech.SUCCESS) {
            speakCallbacks.remove(id)
            done()
        }
    }

    // ---------- сповіщення ----------

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Арчік",NotificationManager.IMPORTANCE_LOW)
        )
    }

    @Suppress("DEPRECATION")
    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val listen = PendingIntent.getService(
            this, 1,
            Intent(this, AssistantService::class.java).setAction(ACTION_LISTEN),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 2,
            Intent(this, AssistantService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Арчік")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(0, "Слухати", listen).build())
            .addAction(Notification.Action.Builder(0, "Зупинити", stop).build())
            .build()
    }

    private fun ensureForeground() {
        if (foregroundStarted) return
        val text = "Запускаюсь…"
        val n = buildNotification(text)
        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, n)
        }
        lastNotifText = text
        foregroundStarted = true
        running = true
    }

    private fun updateNotification(text: String) {
        if (!foregroundStarted || text == lastNotifText) return
        lastNotifText = text
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(text))
    }
}
