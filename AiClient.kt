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

class AiClient(private val storage: Storage) {

    companion object {
        private const val MODEL = "claude-haiku-5-5"
        private const val URL_API = "https://api.anthropic.com/v1/messages"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val history = mutableListOf<Pair<String, String>>()

    fun ask(question: String, callback: (String) -> Unit) {
        Thread {
            val answer = try {
                request(question)
            } catch (e: Exception) {
                "Не вдалося отримати відповідь. Перевірте інтернет і ключ API."
            }
            mainHandler.post { callback(answer) }
        }.start()
    }

    private fun system(): String {
        val now = SimpleDateFormat("d MMMM yyyy, EEEE, HH:mm", Locale("uk", "UA")).format(Date())
        return "Ти голосовий помічник Арчік у смартфоні. Відповідай українською мовою, дуже коротко: " +
            "одне-три речення. Не використовуй markdown, списки, емодзі й спецсимволи, " +
            "бо відповідь озвучується голосом. Якщо не знаєш відповіді або не маєш актуальних " +
            "даних, чесно скажи про це. Поточна дата й час користувача: $now."
    }

    private fun request(question: String): String {
        val messages = JSONArray()
        val snapshot = synchronized(history) { history.toList() }
        for ((role, text) in snapshot) {
            messages.put(JSONObject().put("role", role).put("content", text))
        }
        messages.put(JSONObject().put("role", "user").put("content", question))

        val body = JSONObject()
            .put("model", MODEL)
            .put("max_tokens", 300)
            .put("system", system())
            .put("messages", messages)

        val conn = URL(URL_API).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000
            conn.readTimeout = 40000
            conn.doOutput = true
            conn.setRequestProperty("content-type", "application/json")
            conn.setRequestProperty("x-api-key", storage.apiKey)
            conn.setRequestProperty("anthropic-version", "2023-06-01")
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""

            if (code == 401) return "Ключ API не підійшов. Перевірте його в налаштуваннях."
            if (code == 429) return "Забагато запитів. Спробуйте за хвилину."
            if (code !in 200..299) return "Сервіс штучного інтелекту повернув помилку $code."

            val content = JSONObject(text).getJSONArray("content")
            val sb = StringBuilder()
            for (i in 0 until content.length()) {
                val block = content.getJSONObject(i)
                if (block.optString("type") == "text") sb.append(block.optString("text"))
            }
            val answer = sb.toString().trim()
            if (answer.isEmpty()) return "Відповідь порожня. Спробуйте ще раз."

            synchronized(history) {
                history.add("user" to question)
                history.add("assistant" to answer)
                while (history.size > 8) {
                    history.removeAt(0)
                    history.removeAt(0)
                }
            }
            return answer
        } finally {
            conn.disconnect()
        }
    }
}
