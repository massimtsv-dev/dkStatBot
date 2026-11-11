package com.statbot.ai

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.statbot.config.AppConfig
import kotlinx.coroutines.future.await
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID

data class ChatMessage(val role: String, val content: String) {
    companion object {
        fun system(text: String) = ChatMessage("system", text)
        fun user(text: String) = ChatMessage("user", text)
        fun assistant(text: String) = ChatMessage("assistant", text)
    }
}

/** Минимальный клиент OpenAI-совместимого API (Chat Completions + транскрибация). */
class OpenAiClient(private val config: AppConfig) {
    private val log = LoggerFactory.getLogger(OpenAiClient::class.java)
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build()

    val enabled: Boolean get() = config.openAiKey.isNotBlank()

    /** Запрос с response_format=json_object. Возвращает распарсенный JSON-ответ модели. */
    suspend fun chatJson(messages: List<ChatMessage>, model: String = config.openAiChatModel, temperature: Double = 0.2): JsonObject {
        val body = JsonObject().apply {
            addProperty("model", model)
            addProperty("temperature", temperature)
            add("response_format", JsonObject().apply { addProperty("type", "json_object") })
            add("messages", JsonArray().apply {
                messages.forEach { m -> add(JsonObject().apply { addProperty("role", m.role); addProperty("content", m.content) }) }
            })
        }
        val request = HttpRequest.newBuilder(URI.create("${config.openAiBaseUrl}/chat/completions"))
            .timeout(Duration.ofSeconds(90))
            .header("Authorization", "Bearer ${config.openAiKey}")
            .header("Content-Type", "application/json; charset=utf-8")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString(), Charsets.UTF_8))
            .build()
        val response = http.sendAsync(request, HttpResponse.BodyHandlers.ofString(Charsets.UTF_8)).await()
        if (response.statusCode() !in 200..299) {
            throw IOException("OpenAI HTTP ${response.statusCode()}: ${response.body().take(300)}")
        }
        val content = JsonParser.parseString(response.body()).asJsonObject
            .getAsJsonArray("choices")[0].asJsonObject
            .getAsJsonObject("message").get("content").asString
        return JsonParser.parseString(content.trim().removePrefix("```json").removePrefix("```").removeSuffix("```")).asJsonObject
    }

    /** Распознавание голосового сообщения (ogg/opus из Telegram). */
    suspend fun transcribe(audio: ByteArray, fileName: String = "voice.ogg"): String {
        val boundary = "----statbot" + UUID.randomUUID().toString().replace("-", "")
        val out = ByteArrayOutputStream()
        fun part(name: String, value: String) {
            out.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray(Charsets.UTF_8))
        }
        part("model", config.openAiTranscribeModel)
        part("language", "ru")
        out.write(
            ("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$fileName\"\r\n" +
                "Content-Type: audio/ogg\r\n\r\n").toByteArray(Charsets.UTF_8)
        )
        out.write(audio)
        out.write("\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8))

        val request = HttpRequest.newBuilder(URI.create("${config.openAiBaseUrl}/audio/transcriptions"))
            .timeout(Duration.ofSeconds(120))
            .header("Authorization", "Bearer ${config.openAiKey}")
            .header("Content-Type", "multipart/form-data; boundary=$boundary")
            .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray()))
            .build()
        val response = http.sendAsync(request, HttpResponse.BodyHandlers.ofString(Charsets.UTF_8)).await()
        if (response.statusCode() !in 200..299) {
            log.warn("Ошибка транскрибации: HTTP {} {}", response.statusCode(), response.body().take(300))
            throw IOException("Transcription HTTP ${response.statusCode()}")
        }
        return JsonParser.parseString(response.body()).asJsonObject.get("text")?.asString?.trim().orEmpty()
    }
}
