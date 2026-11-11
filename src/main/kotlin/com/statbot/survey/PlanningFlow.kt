package com.statbot.survey

import com.statbot.ai.ChatMessage
import com.statbot.ai.PlanAssistant
import com.statbot.ai.PlanReply
import com.statbot.telegram.Button
import com.statbot.telegram.InlineKeyboard
import com.statbot.util.esc
import com.statbot.util.numbered
import com.statbot.util.weekMonday

/**
 * Сценарий 1 из ТЗ: составление плана на неделю с помощью ИИ и его редактирование.
 * Кнопки плана: «pl:<действие>[:индекс]».
 */
class PlanningFlow(
    private val e: SurveyEngine,
    private val planner: PlanAssistant,
    private val aiEnabled: Boolean,
) {
    private fun weekWord(p: PlanState) = if (p.isMonday) "эту" else "следующую"

    private suspend fun temp(tgId: Long, p: PlanState, text: String, kb: InlineKeyboard? = null): Long? =
        e.send(tgId, text, kb)?.also { p.tempMessageIds += it }

    private suspend fun ack(tgId: Long, p: PlanState, first: String) = temp(
        tgId, p,
        "$first\n\nТы можешь продолжить редактирование или сохранить план, нажав кнопку «✅ Всё верно, сохранить» выше.",
    )

    private fun taskList(p: PlanState) = if (p.tasks.isEmpty()) "<i>(план пуст — добавь задачу)</i>" else numbered(p.tasks)

    private fun reviewText(p: PlanState): String =
        if (p.mondayChange) "Что нужно изменить?\n\n${taskList(p)}"
        else buildString {
            append("Я структурировал твои задачи:\n\n${taskList(p)}")
            if (p.note.isNotBlank()) append("\n\n${p.note.esc()}")
            append("\n\nПроверь, нужно что-то изменить?")
        }

    private fun finalText(p: PlanState): String =
        if (p.mondayChange) "План на эту неделю:\n\n${numbered(p.tasks)}"
        else "Я структурировал твои задачи:\n\n${numbered(p.tasks)}"

    private fun reviewKeyboard(p: PlanState): InlineKeyboard {
        val rows = mutableListOf(
            listOf(Button("✅ Всё верно, сохранить", "pl:save")),
            listOf(Button("✏️ Изменить одну задачу", "pl:edit")),
            listOf(Button("➕ Добавить задачу", "pl:add")),
            listOf(Button("🗑 Удалить задачу", "pl:del")),
            listOf(Button("🔄 Переписать план целиком", "pl:rew")),
        )
        if (aiEnabled) rows += listOf(Button("💬 Скорректировать с ИИ", "pl:ai"))
        if (p.mondayChange) rows += listOf(Button("← Назад", "pl:back"))
        return InlineKeyboard(rows)
    }

    private fun numbersKeyboard(p: PlanState): InlineKeyboard =
        InlineKeyboard(
            p.tasks.indices.map { Button("${it + 1}", "pl:n:$it") }.chunked(5) +
                listOf(listOf(Button("← Назад", "pl:cancel")))
        )

    private suspend fun refreshReview(tgId: Long, p: PlanState) {
        val id = p.reviewMessageId
        if (id == null || !e.tg.editMessageText(tgId, id, reviewText(p), reviewKeyboard(p), html = true)) {
            // Сообщение не удалось отредактировать (например, удалено) — пришлём заново
            p.reviewMessageId = e.send(tgId, reviewText(p), reviewKeyboard(p))
        }
    }

    // ---------------- Старт ----------------

    suspend fun start(tgId: Long, s: Session, isMonday: Boolean) {
        val p = PlanState(isMonday = isMonday)
        s.plan = p
        temp(
            tgId, p,
            "<b>Составь план на ${weekWord(p)} неделю</b>\n\n" +
                "Расскажи одним сообщением:\n" +
                "1. Над каким проектом или проектами ты будешь работать?\n" +
                "2. Какие конкретные задачи ты хочешь выполнить?\n" +
                "3. Какой законченный и ценный результат должен появиться к концу недели?",
        )
    }

    /** Понедельник → «✏️ Нет, есть изменения»: сразу меню изменений текущего плана. */
    suspend fun openMondayChange(tgId: Long, s: Session, tasks: List<String>) {
        val p = PlanState(isMonday = true, mondayChange = true, mode = PlanMode.REVIEW, tasks = tasks.toMutableList())
        s.plan = p
        p.reviewMessageId = e.send(tgId, reviewText(p), reviewKeyboard(p))
    }

    /** Повторный показ (после /start, /survey, восстановления). */
    suspend fun resend(tgId: Long, s: Session, p: PlanState) {
        when {
            p.reviewMessageId == null -> {
                temp(tgId, p, "<b>Составь план на ${weekWord(p)} неделю</b>\n\nНапиши одним сообщением, над чем будешь работать и какой результат хочешь получить к концу недели.")
            }
            else -> {
                p.reviewMessageId?.let { e.tg.editMarkup(tgId, it, null) }
                p.tempMessageIds += listOfNotNull(p.reviewMessageId)
                p.mode = PlanMode.REVIEW
                p.reviewMessageId = e.send(tgId, reviewText(p), reviewKeyboard(p))
            }
        }
        e.store.save(tgId, s)
    }

    suspend fun sendReminder(tgId: Long, p: PlanState) {
        temp(tgId, p, "Ты ещё не подтвердил(а) план на ${weekWord(p)} неделю. Проверь его и нажми «✅ Всё верно, сохранить».")
    }

    // ---------------- Кнопки ----------------

    suspend fun onButton(tgId: Long, messageId: Long, action: String, arg: String?, callbackId: String) {
        val s = e.store.active(tgId)
        val p = s?.plan
        if (s == null || p == null || p.reviewMessageId != messageId) {
            e.tg.answerCallback(callbackId, "Этот план уже неактуален")
            e.tg.editMarkup(tgId, messageId, null)
            return
        }
        when (action) {
            "save" -> {
                if (p.tasks.isEmpty()) {
                    e.tg.answerCallback(callbackId, "Добавь хотя бы одну задачу")
                    return
                }
                e.tg.answerCallback(callbackId)
                save(tgId, s, p)
                return
            }
            "edit", "del" -> {
                if (p.tasks.isEmpty()) {
                    e.tg.answerCallback(callbackId, "В плане пока нет задач")
                    return
                }
                p.mode = if (action == "edit") PlanMode.EDIT_SELECT else PlanMode.DELETE_SELECT
                e.tg.editMarkup(tgId, messageId, numbersKeyboard(p))
            }
            "n" -> {
                val i = arg?.toIntOrNull()
                if (i == null || i !in p.tasks.indices) {
                    e.tg.answerCallback(callbackId)
                    return
                }
                when (p.mode) {
                    PlanMode.EDIT_SELECT -> {
                        p.editIndex = i
                        p.mode = PlanMode.EDIT_TEXT
                        e.tg.editMarkup(tgId, messageId, reviewKeyboard(p))
                        temp(tgId, p, "Как изменить эту задачу?\n\n«${p.tasks[i].esc()}»")
                    }
                    PlanMode.DELETE_SELECT -> {
                        p.tasks.removeAt(i)
                        p.mode = PlanMode.REVIEW
                        refreshReview(tgId, p)
                        ack(tgId, p, "Задача удалена 👍")
                    }
                    else -> e.tg.editMarkup(tgId, messageId, reviewKeyboard(p))
                }
            }
            "cancel" -> {
                p.mode = PlanMode.REVIEW
                e.tg.editMarkup(tgId, messageId, reviewKeyboard(p))
            }
            "add" -> {
                p.mode = PlanMode.ADD_TEXT
                p.history.clear()
                temp(tgId, p, "Напиши новую задачу, которую нужно добавить в план")
            }
            "rew" -> {
                p.mode = PlanMode.REWRITE
                p.history.clear()
                temp(tgId, p, "Составь новый план на ${weekWord(p)} неделю")
            }
            "ai" -> {
                p.mode = PlanMode.AI_ADJUST
                p.history.clear()
                temp(tgId, p, "Напиши, что нужно скорректировать (например: «объедини первые два пункта» или «уточни формулировку второй задачи»)")
            }
            "back" -> if (p.mondayChange) {
                e.tg.answerCallback(callbackId)
                e.tg.deleteMessages(tgId, p.tempMessageIds + messageId)
                e.backToMondayCheck(tgId, s)
                return
            }
        }
        e.tg.answerCallback(callbackId)
        e.store.save(tgId, s)
    }

    // ---------------- Текст ----------------

    suspend fun onText(tgId: Long, s: Session, p: PlanState, text: String, userMessageId: Long) {
        p.tempMessageIds += userMessageId
        when (p.mode) {
            PlanMode.INPUT, PlanMode.REWRITE -> {
                p.history += ChatMessage.user(text)
                val reply = thinking(tgId, "🤖 Формирую недельные приоритеты...") { planner.structure(p.history, p.isMonday) }
                handleStructured(tgId, p, reply)
            }
            PlanMode.ADD_TEXT -> {
                p.history += ChatMessage.user(text)
                when (val reply = thinking(tgId, "🤖 Проверяю задачу...") { planner.newTask(p.history, p.tasks, p.isMonday) }) {
                    is PlanReply.Plan -> {
                        p.tasks += reply.items
                        p.history.clear()
                        p.mode = PlanMode.REVIEW
                        refreshReview(tgId, p)
                        ack(tgId, p, "Задача добавлена 👍")
                    }
                    is PlanReply.Choose -> {
                        p.tasks += reply.items
                        p.history.clear()
                        p.mode = PlanMode.REVIEW
                        refreshReview(tgId, p)
                        ack(tgId, p, "Задача добавлена 👍")
                    }
                    is PlanReply.Question -> {
                        p.history += ChatMessage.assistant(reply.text)
                        temp(tgId, p, reply.text.esc())
                    }
                }
            }
            PlanMode.AI_ADJUST -> {
                p.history += ChatMessage.user(text)
                when (val reply = thinking(tgId, "🤖 Корректирую план...") { planner.adjust(p.history, p.tasks, p.isMonday) }) {
                    is PlanReply.Plan -> {
                        p.tasks = reply.items.toMutableList()
                        p.note = reply.note
                        p.history.clear()
                        p.mode = PlanMode.REVIEW
                        refreshReview(tgId, p)
                        ack(tgId, p, "Принято 👍")
                    }
                    is PlanReply.Choose -> {
                        p.history += ChatMessage.assistant(chooseText(reply))
                        temp(tgId, p, chooseText(reply))
                    }
                    is PlanReply.Question -> {
                        p.history += ChatMessage.assistant(reply.text)
                        temp(tgId, p, reply.text.esc())
                    }
                }
            }
            PlanMode.EDIT_TEXT -> {
                val i = p.editIndex
                if (i in p.tasks.indices) p.tasks[i] = PlanAssistant.cleanItem(text).ifBlank { text.trim() }
                p.mode = PlanMode.REVIEW
                refreshReview(tgId, p)
                ack(tgId, p, "Задача изменена 👍")
            }
            PlanMode.REVIEW, PlanMode.EDIT_SELECT, PlanMode.DELETE_SELECT ->
                temp(tgId, p, "Выбери действие кнопками под планом 👆")
        }
        e.store.save(tgId, s)
    }

    private fun chooseText(r: PlanReply.Choose) = "${numbered(r.items)}\n\n${r.text.esc()}"

    private suspend fun handleStructured(tgId: Long, p: PlanState, reply: PlanReply) {
        when (reply) {
            is PlanReply.Plan -> {
                p.tasks = reply.items.toMutableList()
                p.note = reply.note
                p.history.clear()
                val wasRewrite = p.mode == PlanMode.REWRITE && p.reviewMessageId != null
                p.mode = PlanMode.REVIEW
                if (p.reviewMessageId == null) {
                    // Первое сообщение с планом — не временное, остаётся в чате
                    p.reviewMessageId = e.send(tgId, reviewText(p), reviewKeyboard(p))
                } else {
                    refreshReview(tgId, p)
                    if (wasRewrite) ack(tgId, p, "Принято 👍")
                }
            }
            is PlanReply.Question -> {
                p.history += ChatMessage.assistant(reply.text)
                temp(tgId, p, reply.text.esc())
            }
            is PlanReply.Choose -> {
                p.history += ChatMessage.assistant(chooseText(reply))
                temp(tgId, p, chooseText(reply))
            }
        }
    }

    private suspend fun <T> thinking(tgId: Long, text: String, block: suspend () -> T): T {
        if (!aiEnabled) return block()
        val id = e.send(tgId, text)
        try {
            return block()
        } finally {
            id?.let { e.tg.deleteMessages(tgId, listOf(it)) }
        }
    }

    // ---------------- Сохранение ----------------

    private suspend fun save(tgId: Long, s: Session, p: PlanState) {
        val date = e.sessionDate(s)
        val weekStart = if (p.isMonday) date.weekMonday() else date.weekMonday().plusWeeks(1)
        e.savePlan(tgId, s, weekStart, p.tasks)
        e.record(s, SurveyEngine.PLAN_KEY, "План на ${weekWord(p)} неделю", p.tasks.mapIndexed { i, t -> "${i + 1}. $t" }.joinToString("\n"))

        // Удаляем все временные сообщения сессии, в чате остаётся только итоговый план
        e.tg.deleteMessages(tgId, p.tempMessageIds)
        p.reviewMessageId?.let { e.tg.editMessageText(tgId, it, finalText(p), null, html = true) }
        e.send(
            tgId,
            if (p.isMonday) "План на эту неделю сохранён. Удачной рабочей недели!"
            else "План на следующую неделю сохранён. Хороших выходных!",
        )
        s.plan = null
        e.advance(tgId, s)
    }
}
