package com.statbot.notifications

import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.InlineKeyboardMarkup
import com.github.kotlintelegrambot.entities.keyboard.InlineKeyboardButton
import com.statbot.db.DailyReports
import com.statbot.db.DbRepository
import com.statbot.db.Users
import com.statbot.model.SurveyManager
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.DayOfWeek
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object SchedulerService {
    private val scheduler = Executors.newScheduledThreadPool(4)
    private val MSK_ZONE = ZoneId.of("Europe/Moscow")
    private val ACTIVE_CYCLE_DAYS = setOf(2, 3, 4, 9, 10, 11, 16, 17, 18, 23, 24, 25, 30)

    fun start(bot: Bot) {
        scheduleDailyTask(10, 0) { sendDailySurvey(bot) }
        scheduleDailyTask(11, 0) { sendFocusCheck(bot) }
        scheduleDailyTask(13, 0) { sendFocusCheck(bot) }
        scheduleDailyTask(18, 0) { sendFocusCheck(bot) }
        scheduleDailyTask(20, 0) { sendEveningReminder(bot) }
    }

    private fun scheduleDailyTask(hour: Int, minute: Int, task: () -> Unit) {
        val nowMsk = ZonedDateTime.now(MSK_ZONE)
        var nextRun = nowMsk.withHour(hour).withMinute(minute).withSecond(0).withNano(0)

        if (nowMsk.isAfter(nextRun)) {
            nextRun = nextRun.plusDays(1)
        }

        val initialDelay = Duration.between(nowMsk, nextRun).toSeconds()

        scheduler.scheduleAtFixedRate(
            {
                try {
                    task()
                } catch (e: Exception) {
                    println("❌ Ошибка выполнения задачи расписания ($hour:$minute МСК): ${e.message}")
                    e.printStackTrace()
                }
            },
            initialDelay,
            TimeUnit.DAYS.toSeconds(1),
            TimeUnit.SECONDS
        )
    }

    private fun sendDailySurvey(bot: Bot) {
        val todayMsk = ZonedDateTime.now(MSK_ZONE).toLocalDate()
        if (todayMsk.dayOfWeek == DayOfWeek.SATURDAY || todayMsk.dayOfWeek == DayOfWeek.SUNDAY) {
            return
        }

        val studentTgIds = transaction {
            Users.select { Users.role eq "STUDENT" }.map { it[Users.tgId] }
        }

        studentTgIds.forEach { tgId ->
            val cycleDay = DbRepository.getUserCycleDay(tgId, todayMsk)
            if (isSurveyDay(todayMsk.dayOfWeek, cycleDay)) {
                SurveyManager.startSurvey(bot, tgId, ChatId.fromId(tgId))
            }
        }
    }

    private fun sendFocusCheck(bot: Bot) {
        val todayMsk = ZonedDateTime.now(MSK_ZONE).toLocalDate()
        if (todayMsk.dayOfWeek == DayOfWeek.SATURDAY || todayMsk.dayOfWeek == DayOfWeek.SUNDAY) {
            return
        }

        val studentIds = transaction {
            Users.select { Users.role eq "STUDENT" }.map { it[Users.tgId] }
        }
        val markup = InlineKeyboardMarkup.createSingleRowKeyboard(
            InlineKeyboardButton.CallbackData("🎬 В работе", "focus_work"),
            InlineKeyboardButton.CallbackData("⏸ На перерыве", "focus_break"),
            InlineKeyboardButton.CallbackData("🔄 Ничего не делаю", "focus_none")
        )
        studentIds.forEach { id ->
            bot.sendMessage(
                chatId = ChatId.fromId(id),
                text = "🔔 Минутка фокуса! Ты тут? Чем занят прямо сейчас?",
                replyMarkup = markup
            )
        }
    }

    private fun sendEveningReminder(bot: Bot) {
        val todayMsk = ZonedDateTime.now(MSK_ZONE).toLocalDate()
        if (todayMsk.dayOfWeek == DayOfWeek.SATURDAY || todayMsk.dayOfWeek == DayOfWeek.SUNDAY) {
            return
        }

        val studentIdsToRemind = transaction {
            val students = Users.select { Users.role eq "STUDENT" }.map { it[Users.tgId] }
            students.filter { tgId ->
                val cycleDay = DbRepository.getUserCycleDay(tgId, todayMsk)
                if (!isSurveyDay(todayMsk.dayOfWeek, cycleDay)) {
                    false
                } else {
                    val report = DailyReports.select {
                        (DailyReports.userTgId eq tgId) and (DailyReports.date eq todayMsk)
                    }.singleOrNull()
                    report == null || !report[DailyReports.isCompleted]
                }
            }
        }

        studentIdsToRemind.forEach { id ->
            bot.sendMessage(
                chatId = ChatId.fromId(id),
                text = "🔔 Ежедневный отчёт\nЕсли ещё не заполнял за сегодня — самое время сделать это."
            )
        }
    }

    private fun isSurveyDay(dayOfWeek: DayOfWeek, cycleDay: Int): Boolean {
        return dayOfWeek == DayOfWeek.MONDAY || dayOfWeek == DayOfWeek.FRIDAY || ACTIVE_CYCLE_DAYS.contains(cycleDay)
    }
}