package com.statbot.telegram

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class TelegramApiException(val code: Int, message: String) : RuntimeException(message)

/**
 * Лёгкий асинхронный клиент Telegram Bot API на java.net.http + корутинах.
 * Ошибки отправки логируются и не роняют сценарий: методы возвращают null/false.
 */
class TelegramClient(token: String, apiUrl: String = "https://api.telegram.org") {
    private val log = LoggerFactory.getLogger(TelegramClient::class.java)
    private val gson = Gson()
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build()
    private val base = "$apiUrl/bot$token"
    private val fileBase = "$apiUrl/file/bot$token"

    private suspend fun call(method: String, params: JsonObject, timeout: Duration = Duration.ofSeconds(30)): JsonElement {
        var attempt = 0
        while (true) {
            attempt++
            val request = HttpRequest.newBuilder(URI.create("$base/$method"))
                .timeout(timeout)
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(params.toString(), Charsets.UTF_8))
                .build()
            val response = http.sendAsync(request, HttpResponse.BodyHandlers.ofString(Charsets.UTF_8)).await()
            val json = runCatching { JsonParser.parseString(response.body()).asJsonObject }.getOrNull()
                ?: throw IOException("Telegram $method: HTTP ${response.statusCode()} ${response.body().take(200)}")

            if (json.get("ok")?.asBoolean == true) return json.get("result")

            val code = json.get("error_code")?.asInt ?: response.statusCode()
            val description = json.get("description")?.asString ?: "unknown error"
            val retryAfter = json.getAsJsonObject("parameters")?.get("retry_after")?.asInt
            if (code == 429 && retryAfter != null && attempt <= 3) {
                log.warn("Telegram flood control on {}, ждём {} c", method, retryAfter)
                delay(retryAfter * 1000L + 200)
                continue
            }
            throw TelegramApiException(code, "$method: $description")
        }
    }

    private suspend fun <T> safe(method: String, fallback: T, block: suspend () -> T): T = try {
        block()
    } catch (e: TelegramApiException) {
        // «message is not modified» и «message to delete not found» — штатные ситуации
        if (e.message?.contains("not modified") != true && e.message?.contains("not found") != true) {
            log.warn("Ошибка Telegram ({}): {}", method, e.message)
        }
        fallback
    } catch (e: Exception) {
        log.warn("Сбой запроса Telegram ({}): {}", method, e.toString())
        fallback
    }

    suspend fun getUpdates(offset: Long, timeoutSeconds: Int = 50): List<TgUpdate> {
        val params = JsonObject().apply {
            addProperty("offset", offset)
            addProperty("timeout", timeoutSeconds)
            add("allowed_updates", JsonArray().apply { add("message"); add("callback_query") })
        }
        val result = call("getUpdates", params, Duration.ofSeconds(timeoutSeconds + 15L))
        return gson.fromJson(result, object : TypeToken<List<TgUpdate>>() {}.type)
    }

    /** @return message_id отправленного сообщения или null при ошибке. */
    suspend fun sendMessage(chatId: Long, text: String, markup: Markup? = null, html: Boolean = false): Long? =
        safe("sendMessage", null) {
            val params = JsonObject().apply {
                addProperty("chat_id", chatId)
                addProperty("text", text)
                if (html) addProperty("parse_mode", "HTML")
                add("link_preview_options", JsonObject().apply { addProperty("is_disabled", true) })
                markup?.let { add("reply_markup", it.toJson()) }
            }
            call("sendMessage", params).asJsonObject.get("message_id").asLong
        }

    suspend fun editMessageText(chatId: Long, messageId: Long, text: String, markup: InlineKeyboard? = null, html: Boolean = false): Boolean =
        safe("editMessageText", false) {
            val params = JsonObject().apply {
                addProperty("chat_id", chatId)
                addProperty("message_id", messageId)
                addProperty("text", text)
                if (html) addProperty("parse_mode", "HTML")
                add("link_preview_options", JsonObject().apply { addProperty("is_disabled", true) })
                add("reply_markup", (markup ?: InlineKeyboard.EMPTY).toJson())
            }
            call("editMessageText", params)
            true
        }

    suspend fun editMarkup(chatId: Long, messageId: Long, markup: InlineKeyboard?): Boolean =
        safe("editMessageReplyMarkup", false) {
            val params = JsonObject().apply {
                addProperty("chat_id", chatId)
                addProperty("message_id", messageId)
                add("reply_markup", (markup ?: InlineKeyboard.EMPTY).toJson())
            }
            call("editMessageReplyMarkup", params)
            true
        }

    suspend fun deleteMessages(chatId: Long, messageIds: Collection<Long>) {
        if (messageIds.isEmpty()) return
        messageIds.distinct().chunked(100).forEach { chunk ->
            val ok = safe("deleteMessages", false) {
                val params = JsonObject().apply {
                    addProperty("chat_id", chatId)
                    add("message_ids", JsonArray().apply { chunk.forEach { add(it) } })
                }
                call("deleteMessages", params)
                true
            }
            // Запасной вариант для старых серверов Bot API — по одному
            if (!ok) for (id in chunk) {
                safe("deleteMessage", false) {
                    call("deleteMessage", JsonObject().apply { addProperty("chat_id", chatId); addProperty("message_id", id) })
                    true
                }
            }
        }
    }

    suspend fun answerCallback(callbackId: String, text: String? = null) = safe("answerCallbackQuery", Unit) {
        call("answerCallbackQuery", JsonObject().apply {
            addProperty("callback_query_id", callbackId)
            text?.let { addProperty("text", it) }
        })
        Unit
    }

    suspend fun getFile(fileId: String): TgFile? = safe("getFile", null) {
        gson.fromJson(call("getFile", JsonObject().apply { addProperty("file_id", fileId) }), TgFile::class.java)
    }

    suspend fun downloadFile(filePath: String): ByteArray {
        val request = HttpRequest.newBuilder(URI.create("$fileBase/$filePath")).timeout(Duration.ofSeconds(60)).GET().build()
        val response = http.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray()).await()
        if (response.statusCode() != 200) throw IOException("Не удалось скачать файл: HTTP ${response.statusCode()}")
        return response.body()
    }

    suspend fun getMe(): TgUser = gson.fromJson(call("getMe", JsonObject()), TgUser::class.java)

    suspend fun setMyCommands(commands: List<Pair<String, String>>, chatId: Long? = null) = safe("setMyCommands", Unit) {
        call("setMyCommands", JsonObject().apply {
            add("commands", JsonArray().apply {
                commands.forEach { (c, d) -> add(JsonObject().apply { addProperty("command", c); addProperty("description", d) }) }
            })
            if (chatId != null) add("scope", JsonObject().apply { addProperty("type", "chat"); addProperty("chat_id", chatId) })
        })
        Unit
    }

    suspend fun setMyDescription(description: String, shortDescription: String) {
        safe("setMyDescription", Unit) { call("setMyDescription", JsonObject().apply { addProperty("description", description) }); Unit }
        safe("setMyShortDescription", Unit) { call("setMyShortDescription", JsonObject().apply { addProperty("short_description", shortDescription) }); Unit }
    }
}
