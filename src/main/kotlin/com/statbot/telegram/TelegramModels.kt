package com.statbot.telegram

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName

// ---------- Входящие объекты Telegram Bot API (только нужные поля) ----------

data class TgUser(
    val id: Long,
    @SerializedName("first_name") val firstName: String?,
    val username: String?,
)

data class TgChat(val id: Long, val type: String?)

data class TgFileRef(@SerializedName("file_id") val fileId: String)

data class TgMessage(
    @SerializedName("message_id") val messageId: Long,
    val from: TgUser?,
    val chat: TgChat,
    val text: String?,
    val voice: TgFileRef?,
    val audio: TgFileRef?,
    @SerializedName("video_note") val videoNote: TgFileRef?,
    val photo: List<TgFileRef>?,
    val document: TgFileRef?,
    val sticker: TgFileRef?,
)

data class TgCallbackQuery(
    val id: String,
    val from: TgUser,
    val message: TgMessage?,
    val data: String?,
)

data class TgUpdate(
    @SerializedName("update_id") val updateId: Long,
    val message: TgMessage?,
    @SerializedName("callback_query") val callbackQuery: TgCallbackQuery?,
)

data class TgFile(
    @SerializedName("file_id") val fileId: String,
    @SerializedName("file_path") val filePath: String?,
)

// ---------- Клавиатуры ----------

data class Button(val text: String, val data: String)

sealed interface Markup {
    fun toJson(): JsonElement
}

/** Inline-клавиатура под сообщением. */
data class InlineKeyboard(val rows: List<List<Button>>) : Markup {
    override fun toJson(): JsonElement = JsonObject().apply {
        add("inline_keyboard", JsonArray().apply {
            rows.forEach { row ->
                add(JsonArray().apply {
                    row.forEach { b ->
                        add(JsonObject().apply {
                            addProperty("text", b.text)
                            addProperty("callback_data", b.data)
                        })
                    }
                })
            }
        })
    }

    companion object {
        val EMPTY = InlineKeyboard(emptyList())
        fun of(vararg rows: List<Button>) = InlineKeyboard(rows.toList())
        fun column(buttons: List<Button>) = InlineKeyboard(buttons.map { listOf(it) })
    }
}

/** Постоянная клавиатура внизу экрана (например «👤 Мой профиль»). */
data class ReplyKeyboard(val rows: List<List<String>>) : Markup {
    override fun toJson(): JsonElement = JsonObject().apply {
        add("keyboard", JsonArray().apply {
            rows.forEach { row ->
                add(JsonArray().apply { row.forEach { add(JsonObject().apply { addProperty("text", it) }) } })
            }
        })
        addProperty("resize_keyboard", true)
        addProperty("is_persistent", true)
    }
}
