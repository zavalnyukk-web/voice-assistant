package ua.helper.assistant

import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Відповіді ШІ. Провайдер визначається за ключем:
 *  - ключ, що починається з "sk-ant-", це Claude (платний);
 *  - будь-який інший (зазвичай "AIza..."), це Google Gemini (є безкоштовний рівень).
 */
class AiClient(private val storage: Storage) {

    companion object {
        private const val CLAUDE_MODEL = "claude-haiku-5-5"
        private const val CLAUDE_URL = "https://api.anthropic.com/v1/messages"
        private const val GEMINI_BASE = "https://generativelanguage.googleapis.com/v1beta/models/"

        // Якщо одна модель перевантажена (503) або вичерпала ліміт (429), пробуємо наступну.
        private val GEMINI_MODELS =
            listOf("gemini-flash-latest", "gemini-flash-lite-latest", "gemini-2.5-flash")
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val history = mutableListOf<Pair<String, String>>() // ("user"|"assistant", текст)

    fun ask(question: String, callback: (String) -> Unit) {
        Thread {
            val answer = try {
                if (storage.apiKey.startsWith("sk-ant-")) requestClaude(question) else requestGemini(question)
            } catch (e: Exception) {
                "Не вдалося отримати відповідь. Перевірте інтернет і ключ."
            }
            mainHandler.post { callback(answer) }
        }.start()
    }

    private fun system(): String {
        val now = SimpleDateFormat("d MMMM yyyy, EEEE, HH:mm", Locale("uk", "UA")).format(Date())
        return "Ти Арчік: голосовий помічник у смартфоні і добрий співрозмовник. " +
            "Відповідай виключно ${Languages.find(storage.ttsLang).adjective} мовою. Говори тепло й невимушено, як друг. " +
            "Відповідай живою мовою, зазвичай двома-чотирма реченнями, без списків, markdown, емодзі й спецсимволів, " +
            "бо твою відповідь озвучують голосом. Можеш поставити одне коротке запитання у відповідь, щоб підтримати розмову, " +
            "і давати поради. У питаннях здоров'я, грошей чи права давай обережні загальні поради " +
            "й раджай звернутися до фахівця, коли це справді важливо. " +
            "Якщо не знаєш відповіді або не маєш актуальних даних (погода, новини, курси), чесно скажи про це. " +
            "Поточна дата й час користувача: $now."
    }

    private fun remember(question: String, answer: String) {
        synchronized(history) {
            history.add("user" to question)
            history.add("assistant" to answer)
            while (history.size > 16) {
                history.removeAt(0)
                history.removeAt(0)
            }
        }
    }

    private fun post(url: String, headers: Map<String, String>, body: JSONObject): Pair<Int, String> {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000
            conn.readTimeout = 40000
            conn.doOutput = true
            conn.setRequestProperty("content-type", "application/json")
            for ((k, v) in headers) conn.setRequestProperty(k, v)
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            return code to text
        } finally {
            conn.disconnect()
        }
    }

    private fun logDetails(provider: String, code: Int, body: String) {
        val msg = try {
            JSONObject(body).getJSONObject("error").optString("message")
        } catch (e: Exception) {
            body
        }
        mainHandler.post { AssistantService.addLog("$provider, код $code: ${msg.take(300)}") }
    }

    private fun errorMessage(code: Int): String? = when (code) {
        400, 401, 403 -> "Ключ не підійшов або немає доступу. Перевірте ключ у налаштуваннях."
        404 -> "Модель ШІ недоступна. Напишіть розробнику."
        429 -> "Ліміт запитів вичерпано. Спробуйте за хвилину або пізніше."
        in 500..599 -> "Сервіс ШІ тимчасово недоступний. Спробуйте пізніше."
        else -> if (code in 200..299) null else "Сервіс ШІ повернув помилку $code."
    }

    // ---------- Claude ----------

    private fun requestClaude(question: String): String {
        val messages = JSONArray()
        val snapshot = synchronized(history) { history.toList() }
        for ((role, text) in snapshot) {
            messages.put(JSONObject().put("role", role).put("content", text))
        }
        messages.put(JSONObject().put("role", "user").put("content", question))

        val body = JSONObject()
            .put("model", CLAUDE_MODEL)
            .put("max_tokens", 400)
            .put("system", system())
            .put("messages", messages)

        val (code, text) = post(
            CLAUDE_URL,
            mapOf("x-api-key" to storage.apiKey, "anthropic-version" to "2023-06-01"),
            body
        )
        errorMessage(code)?.let { return it }

        val content = JSONObject(text).getJSONArray("content")
        val sb = StringBuilder()
        for (i in 0 until content.length()) {
            val block = content.getJSONObject(i)
            if (block.optString("type") == "text") sb.append(block.optString("text"))
        }
        val answer = sb.toString().trim()
        if (answer.isEmpty()) return "Відповідь порожня. Спробуйте ще раз."
        remember(question, answer)
        return answer
    }

    // ---------- Gemini (безкоштовний рівень) ----------

    private fun requestGemini(question: String): String {
        val contents = JSONArray()
        val snapshot = synchronized(history) { history.toList() }
        for ((role, text) in snapshot) {
            contents.put(
                JSONObject()
                    .put("role", if (role == "user") "user" else "model")
                    .put("parts", JSONArray().put(JSONObject().put("text", text)))
            )
        }
        contents.put(
            JSONObject()
                .put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", question)))
        )

        val body = JSONObject()
            .put(
                "system_instruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system())))
            )
            .put("contents", contents)
            .put("generationConfig", JSONObject().put("maxOutputTokens", 1024))

        val key = storage.apiKey
        var code = 0
        var text = ""
        for (model in GEMINI_MODELS) {
            val r = post("$GEMINI_BASE$model:generateContent", mapOf("x-goog-api-key" to key), body)
            code = r.first
            text = r.second
            if (code in 200..299) break
            // проблема з ключем: інша модель не допоможе
            if (code == 400 || code == 401 || code == 403) break
            // 429, 404, 5xx: пробуємо наступну модель
        }
        if (code !in 200..299) logDetails("Gemini", code, text)
        errorMessage(code)?.let { return it }

        val parts = JSONObject(text)
            .optJSONArray("candidates")?.optJSONObject(0)
            ?.optJSONObject("content")?.optJSONArray("parts")
        val sb = StringBuilder()
        if (parts != null) {
            for (i in 0 until parts.length()) sb.append(parts.getJSONObject(i).optString("text"))
        }
        val answer = sb.toString().trim()
        if (answer.isEmpty()) return "Відповідь порожня. Спробуйте переформулювати запитання."
        remember(question, answer)
        return answer
    }
}
