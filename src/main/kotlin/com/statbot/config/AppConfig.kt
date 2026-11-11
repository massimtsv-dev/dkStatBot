package com.statbot.config

import org.slf4j.LoggerFactory
import java.io.File
import java.time.ZoneId
import java.util.Properties

/**
 * Вся конфигурация бота в одном месте.
 * Каждый параметр берётся из переменной окружения, а если её нет — из файла конфигурации.
 * Файл: путь из переменной CONFIG_PATH (например, /etc/dkstatbot/bot.properties),
 * иначе local.properties в рабочей папке процесса. Секретов в коде нет.
 */
data class AppConfig(
    val botToken: String,
    val openAiKey: String,
    val openAiBaseUrl: String,
    val openAiChatModel: String,
    val openAiAnalysisModel: String,
    val openAiTranscribeModel: String,
    val telegramApiUrl: String,
    /** null — выдача роли преподавателя через /iamteacher отключена. */
    val teacherPassword: String?,
    val dbPath: String,
    val zone: ZoneId,
    val testCommandsEnabled: Boolean,
    val feedbackContact: String,
    /** Через сколько минут напоминать о неподтверждённом плане (по ТЗ — 60). */
    val planReminderMinutes: Long,
    /** Только для отладки: подменяет «сегодня» (формат 2026-11-02). */
    val debugDate: java.time.LocalDate? = null,
) {
    companion object {
        private val log = LoggerFactory.getLogger(AppConfig::class.java)

        fun load(): AppConfig {
            val explicitPath = System.getenv("CONFIG_PATH")?.takeIf { it.isNotBlank() }
            val file = File(explicitPath ?: "local.properties")
            if (explicitPath != null && !file.isFile) {
                error("CONFIG_PATH указывает на несуществующий файл: ${file.absolutePath}")
            }
            val props = Properties().apply {
                if (file.isFile) file.reader(Charsets.UTF_8).use { load(it) }
            }
            if (file.isFile) log.info("Конфигурация: {}", file.absolutePath)
            else log.info("Файл конфигурации не найден ({}), используются переменные окружения", file.absolutePath)

            fun get(key: String): String? =
                System.getenv(key)?.takeIf { it.isNotBlank() } ?: props.getProperty(key)?.trim()?.takeIf { it.isNotBlank() }

            fun require(key: String): String =
                get(key) ?: error("Не задан параметр $key (переменная окружения или local.properties)")

            val teacherPassword = get("TEACHER_PASSWORD")
            if (teacherPassword == null) log.warn("TEACHER_PASSWORD не задан — команда /iamteacher отключена")

            return AppConfig(
                botToken = require("BOT_TOKEN"),
                openAiKey = get("OPENAI_API_KEY").orEmpty(),
                openAiBaseUrl = (get("OPENAI_BASE_URL") ?: "https://api.openai.com/v1").trimEnd('/'),
                openAiChatModel = get("OPENAI_MODEL") ?: "gpt-4o-mini",
                openAiAnalysisModel = get("OPENAI_ANALYSIS_MODEL") ?: get("OPENAI_MODEL") ?: "gpt-4o",
                openAiTranscribeModel = get("OPENAI_TRANSCRIBE_MODEL") ?: "whisper-1",
                telegramApiUrl = (get("TELEGRAM_API_URL") ?: "https://api.telegram.org").trimEnd('/'),
                teacherPassword = teacherPassword,
                dbPath = get("DB_PATH") ?: "statbot.db",
                zone = ZoneId.of(get("TIMEZONE") ?: "Europe/Moscow"),
                testCommandsEnabled = get("TEST_COMMANDS")?.toBooleanStrictOrNull() ?: true,
                feedbackContact = get("FEEDBACK_CONTACT") ?: "@ninafns",
                planReminderMinutes = get("PLAN_REMINDER_MINUTES")?.toLongOrNull() ?: 60,
                debugDate = get("DEBUG_DATE")?.let { java.time.LocalDate.parse(it) },
            )
        }
    }
}
