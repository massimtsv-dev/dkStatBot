package com.statbot.ai

import com.google.gson.JsonObject
import org.slf4j.LoggerFactory

/** Ответ ИИ при работе с недельным планом. */
sealed interface PlanReply {
    data class Plan(val items: List<String>, val note: String) : PlanReply
    data class Question(val text: String) : PlanReply
    data class Choose(val items: List<String>, val text: String) : PlanReply
}

class PlanAssistant(private val ai: OpenAiClient) {
    private val log = LoggerFactory.getLogger(PlanAssistant::class.java)

    /** Первичная структуризация плана. history — диалог по плану (ответы пользователя и уточняющие вопросы ИИ). */
    suspend fun structure(history: List<ChatMessage>, isMonday: Boolean): PlanReply =
        ask(listOf(ChatMessage.system(Prompts.weeklyPlan(isMonday))) + history) { fallbackParse(history.lastUserText()) }

    /** Проверка и формулировка одной новой задачи. */
    suspend fun newTask(history: List<ChatMessage>, currentPlan: List<String>, isMonday: Boolean): PlanReply =
        ask(listOf(ChatMessage.system(Prompts.addTask(isMonday, currentPlan))) + history) {
            PlanReply.Plan(listOf(history.lastUserText().trim()), "")
        }

    /** Корректировка плана по просьбе пользователя («💬 Скорректировать с ИИ»). */
    suspend fun adjust(history: List<ChatMessage>, currentPlan: List<String>, isMonday: Boolean): PlanReply =
        ask(listOf(ChatMessage.system(Prompts.adjustPlan(isMonday, currentPlan))) + history) { PlanReply.Plan(currentPlan, "") }

    private suspend fun ask(messages: List<ChatMessage>, fallback: () -> PlanReply): PlanReply {
        if (!ai.enabled) return fallback()
        return try {
            parse(ai.chatJson(messages)) ?: fallback()
        } catch (e: Exception) {
            log.warn("ИИ недоступен при работе с планом: {}", e.toString())
            fallback()
        }
    }

    private fun parse(json: JsonObject): PlanReply? {
        val status = json.get("status")?.asString?.lowercase()
        val message = json.get("message")?.takeIf { !it.isJsonNull }?.asString.orEmpty().trim()
        val items = json.getAsJsonArray("priorities")
            ?.mapNotNull { el -> el.takeIf { !it.isJsonNull }?.asString?.let(::cleanItem) }
            ?.filter { it.isNotBlank() }
            .orEmpty()
        return when (status) {
            "plan" -> if (items.isNotEmpty()) PlanReply.Plan(items, message) else null
            "choose" -> if (items.isNotEmpty()) PlanReply.Choose(items, message.ifBlank {
                "Получилось больше трёх возможных приоритетов. На неделю нужно выбрать не больше трёх. Какие оставляем?"
            }) else null
            "question" -> if (message.isNotBlank()) PlanReply.Question(message) else null
            else -> null
        }
    }

    companion object {
        private val NUMBER_PREFIX = Regex("^\\s*(\\d+[.)]|[-•*–])\\s*")

        fun cleanItem(s: String) = s.replace(NUMBER_PREFIX, "").trim()

        /** Разбор плана без ИИ: каждая строка / пункт — отдельная задача. */
        fun fallbackParse(text: String): PlanReply {
            val items = text.split('\n', ';')
                .map(::cleanItem)
                .filter { it.isNotBlank() }
            return PlanReply.Plan(items.ifEmpty { listOf(text.trim()) }, "")
        }

        private fun List<ChatMessage>.lastUserText() = lastOrNull { it.role == "user" }?.content.orEmpty()
    }
}

/** Анализ ответов дня: нужна ли сотруднику помощь. */
class ReportAnalyst(private val ai: OpenAiClient, private val model: String) {
    private val log = LoggerFactory.getLogger(ReportAnalyst::class.java)

    data class Verdict(val flagged: Boolean, val summary: String)

    suspend fun analyze(name: String, project: String, qa: List<Pair<String, String>>): Verdict? {
        if (!ai.enabled || qa.isEmpty()) return null
        val answers = qa.joinToString("\n") { (q, a) -> "— $q\n  Ответ: $a" }
        return try {
            val json = ai.chatJson(
                listOf(
                    ChatMessage.system(Prompts.DAILY_ANALYSIS),
                    ChatMessage.user("Сотрудник: $name\nЧем занимается: $project\n\nОтветы:\n$answers"),
                ),
                model = model,
            )
            Verdict(json.get("isFlagged")?.asBoolean ?: false, json.get("summary")?.takeIf { !it.isJsonNull }?.asString.orEmpty())
        } catch (e: Exception) {
            log.warn("Не удалось проанализировать отчёт: {}", e.toString())
            null
        }
    }
}
