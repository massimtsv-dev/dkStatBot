package com.statbot.bot

import com.statbot.db.AnswerRow
import com.statbot.db.ReportRow
import com.statbot.db.Repository
import com.statbot.model.Profile
import com.statbot.model.Role
import com.statbot.telegram.Button
import com.statbot.telegram.InlineKeyboard
import com.statbot.telegram.TelegramClient
import com.statbot.util.esc
import com.statbot.util.numbered
import com.statbot.util.weekMonday
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

/** Панель преподавателя: статистика команды, по ученикам и последние алерты ИИ. Кнопки «t:…». */
class TeacherPanel(private val tg: TelegramClient, private val repo: Repository, private val zone: ZoneId) {

    private val metrics: List<Pair<String, List<String>>> = listOf(
        "⚡ Энергия и эмоциональный настрой" to listOf("ENERGY", "EMOTIONAL"),
        "🚀 Скорость работы" to listOf("SPEED"),
        "🔥 Вовлечённость в проекты" to listOf("ENGAGEMENT"),
        "💡 Инициативность" to listOf("INITIATIVE"),
        "🗓 Качество планирования" to listOf("PLANNING_Q"),
        "📝 Фиксация задач после созвонов" to listOf("NOTES_Q"),
        "📞 Включённость на созвонах" to listOf("CALL_ENG"),
        "🎯 Ценность созвонов" to listOf("CALL_VALUE"),
        "⚖️ Комфорт нагрузки" to listOf("WORKLOAD"),
        "🛡 Самостоятельность" to listOf("AUTONOMY"),
        "😊 Счастье в компании" to listOf("HAPPINESS"),
        "🧗 Лёгкость сложных задач" to listOf("HARD_TASKS"),
        "🌟 Реализация потенциала" to listOf("POTENTIAL"),
        "📅 Оценка рабочей недели" to listOf("FRI_WEEK"),
        "❤️ Здоровье" to listOf("Q_HEALTH"),
        "🤝 Внимание компании к развитию" to listOf("Q_ATTENTION"),
        "🏆 Довольство результатами целей" to listOf("Q_GOALS_SAT", "Y_SAT"),
    )

    /** Ключи шкалы 1–10, по которым считаем «зону внимания». */
    private val ratingKeys = metrics.flatMap { it.second }.toSet()

    suspend fun isTeacher(tgId: Long) = repo.profile(tgId)?.role == Role.TEACHER

    suspend fun showMenu(chatId: Long) {
        val kb = InlineKeyboard.column(
            listOf(
                Button("📊 Статистика за сегодня", "t:day"),
                Button("📅 За последние 7 дней", "t:week"),
                Button("🗓 За последние 30 дней", "t:month"),
                Button("👥 Статистика по ученикам", "t:students"),
                Button("🚨 Последние 5 алертов ИИ", "t:alerts"),
            )
        )
        tg.sendMessage(chatId, "🎛 <b>Панель преподавателя</b>\nВыбери формат аналитики:", kb, html = true)
    }

    suspend fun onButton(chatId: Long, action: String, arg: String?, callbackId: String) {
        tg.answerCallback(callbackId)
        val today = LocalDate.now(zone)
        val text: String = when (action) {
            "day" -> "📊 <b>Статистика за сегодня ($today)</b>\n\n" + periodStats(today)
            "week" -> "📅 <b>Статистика за последние 7 дней</b>\n\n" + periodStats(today.minusDays(6))
            "month" -> "🗓 <b>Статистика за последние 30 дней</b>\n\n" + periodStats(today.minusDays(29))
            "alerts" -> alerts()
            "students" -> {
                val students = repo.students().filter { it.isOnboarded }
                if (students.isEmpty()) {
                    "👥 Пока нет учеников, прошедших регистрацию."
                } else {
                    val kb = InlineKeyboard.column(students.sortedBy { it.displayName }.map { Button(it.displayName, "t:s:${it.tgId}") })
                    tg.sendMessage(chatId, "👥 Выбери ученика:", kb)
                    return
                }
            }
            "s" -> arg?.toLongOrNull()?.let { studentStats(it) } ?: "Ученик не найден."
            else -> return
        }
        text.chunkedForTelegram().forEach { tg.sendMessage(chatId, it, html = true) }
    }

    private fun avg(values: List<Double>) = if (values.isEmpty()) null else (values.average() * 10).roundToInt() / 10.0

    private suspend fun periodStats(from: LocalDate): String {
        val reports = repo.reportsSince(from)
        if (reports.isEmpty()) return "За этот период опросов не было."
        val answers = repo.answersFor(reports.map { it.id })
        val byKey = answers.filter { it.score != null }.groupBy { it.key }

        val sb = StringBuilder()
        sb.append("📋 <b>Активность</b>\n")
        sb.append("Отправлено опросов: ${reports.size}\n")
        sb.append("✅ Заполнено полностью: ${reports.count { it.completed }}\n")
        sb.append("⏳ Не заполнено: ${reports.count { !it.completed }}\n")
        sb.append("🚨 Алертов ИИ: ${reports.count { it.flagged }}\n\n")

        val lines = metrics.mapNotNull { (label, keys) ->
            avg(keys.flatMap { k -> byKey[k].orEmpty().mapNotNull { it.score } })?.let { "$label: <b>$it</b>" }
        }
        sb.append("📊 <b>Средние оценки (из 10)</b>\n")
        sb.append(if (lines.isEmpty()) "Нет оценок за период" else lines.joinToString("\n"))

        avg(byKey["FRI_TASKS"].orEmpty().mapNotNull { it.score })?.let { sb.append("\n\n🎯 Выполнение плана недели: <b>${it.roundToInt()}%</b>") }
        avg(byKey["EXPERT"].orEmpty().mapNotNull { it.score })?.let { sb.append("\n🎓 Самооценка уровня специалиста: <b>$it</b> из 10") }

        val attention = attentionList(reports, answers)
        if (attention.isNotEmpty()) sb.append("\n\n⚠️ <b>Зона внимания</b>\n").append(attention.joinToString("\n"))
        return sb.toString()
    }

    private suspend fun attentionList(reports: List<ReportRow>, answers: List<AnswerRow>): List<String> {
        val userByReport = reports.associate { it.id to it.userTgId }
        val lowByUser = answers.filter { it.key in ratingKeys && (it.score ?: 10.0) <= 5.0 }
            .groupingBy { userByReport[it.reportId] }.eachCount()
        val flaggedByUser = reports.filter { it.flagged }.groupingBy { it.userTgId }.eachCount()
        val ids = (lowByUser.keys.filterNotNull() + flaggedByUser.keys).distinct()
        return ids.map { id ->
            val name = repo.profile(id)?.displayName ?: "ID $id"
            val parts = listOfNotNull(
                lowByUser[id]?.let { "низких оценок: $it" },
                flaggedByUser[id]?.let { "алертов ИИ: $it" },
            )
            "• ${name.esc()} — ${parts.joinToString(", ")}"
        }
    }

    private suspend fun alerts(): String {
        val list = repo.lastAlerts(5)
        if (list.isEmpty()) return "✅ Критических сигналов от ИИ пока не было."
        return "🚨 <b>Последние алерты ИИ</b>\n\n" + list.joinToString("\n\n— — —\n\n") { (r, p) ->
            "👤 ${(p?.displayName ?: "ID ${r.userTgId}").esc()}${p?.project?.let { " [${it.esc()}]" }.orEmpty()}\n📅 ${r.date}\n${(r.summary ?: "").esc()}"
        }
    }

    private suspend fun studentStats(tgId: Long): String {
        val p: Profile = repo.profile(tgId) ?: return "Ученик не найден."
        val reports = repo.reportsOf(tgId)
        val lastCompleted = reports.firstOrNull { it.completed }
        val lastAlert = reports.firstOrNull { it.flagged }
        val today = LocalDate.now(zone)
        val plan = repo.planFor(tgId, today.weekMonday())
        val ratings = repo.answersFor(reports.take(30).map { it.id }).filter { it.key in ratingKeys }.mapNotNull { it.score }

        fun v(x: String?) = x?.takeIf { it.isNotBlank() }?.esc() ?: "<i>не указано</i>"
        val sb = StringBuilder()
        sb.append("👤 <b>${p.displayName.esc()}</b>")
        p.username?.let { sb.append(" (@${it.esc()})") }
        sb.append("\n🎬 ${v(p.project)}\n")
        sb.append("🎯 Цели на год: ${v(p.goalsYear)}\n")
        sb.append("🎯 Цели на 3 месяца: ${v(p.goals3Months)}\n")
        p.onboardedAt?.let { sb.append("📌 В боте с $it\n") }
        sb.append("\n📈 Опросов: ${reports.size}, заполнено: ${reports.count { it.completed }}, алертов ИИ: ${reports.count { it.flagged }}\n")
        avg(ratings)?.let { sb.append("📊 Средняя оценка (последние 30 опросов): <b>$it</b> из 10\n") }
        sb.append("\n🗂 <b>План на эту неделю:</b>\n${if (plan.isNullOrEmpty()) "<i>не составлен</i>" else numbered(plan)}\n")

        if (lastCompleted != null) {
            val answers = repo.answersFor(listOf(lastCompleted.id)).filter { it.key != "PLAN" }
            if (answers.isNotEmpty()) {
                sb.append("\n📝 <b>Последний опрос (${lastCompleted.date}):</b>\n")
                answers.take(12).forEach { a ->
                    sb.append("• ${(a.question ?: a.key).take(90).esc()}\n   → ${a.answer.take(300).esc()}\n")
                }
            }
        }
        if (lastAlert != null) sb.append("\n🚨 <b>Последний алерт (${lastAlert.date}):</b>\n${(lastAlert.summary ?: "").esc()}")
        return sb.toString()
    }

    /** Telegram ограничивает сообщение 4096 символами. */
    private fun String.chunkedForTelegram(limit: Int = 3800): List<String> {
        if (length <= limit) return listOf(this)
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        split("\n").forEach { line ->
            if (current.length + line.length + 1 > limit && current.isNotEmpty()) {
                parts += current.toString(); current.clear()
            }
            current.append(line).append('\n')
        }
        if (current.isNotEmpty()) parts += current.toString()
        return parts
    }
}
