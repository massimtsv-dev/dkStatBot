package com.statbot.notifications

import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.entities.ChatId
import com.statbot.db.DailyReports
import com.statbot.db.DbRepository
import com.statbot.db.Users
import com.statbot.model.SessionManager
import com.statbot.model.SurveyManager
import com.statbot.model.SurveyStep
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
    private val scheduler = Executors.newScheduledThreadPool(2)
    private val MSK_ZONE = ZoneId.of("Europe/Moscow")

    fun start(bot: Bot) {
        // 1. Ежедневная рассылка опросов по будням (10:00 МСК)
        scheduleDailyTask(10, 0) { sendDailySurvey(bot) }

        // 2. Ежевечернее напоминание по будням (20:00 МСК)
        scheduleDailyTask(20, 0) { sendEveningReminder(bot) }

        // 3. Проверка и отправка напоминания через 1 час для неподтвержденных планов
        scheduler.scheduleAtFixedRate({
            try {
                checkPlanningReminders(bot)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, 1, 1, TimeUnit.MINUTES)
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
            SurveyManager.startSurvey(bot, tgId, ChatId.fromId(tgId))
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
                val report = DailyReports.select {
                    (DailyReports.userTgId eq tgId) and (DailyReports.date eq todayMsk)
                }.singleOrNull()

                report == null || !report[DailyReports.isCompleted]
            }
        }

        studentIdsToRemind.forEach { id ->
            bot.sendMessage(
                chatId = ChatId.fromId(id),
                text = "🔔 Ежедневный отчёт\nЕсли ещё не заполнял за сегодня — самое время сделать это."
            )
        }
    }

    private fun checkPlanningReminders(bot: Bot) {
        val now = System.currentTimeMillis()
        SessionManager.activeSurveys.forEach { (tgId, state) ->
            val isPlanningStep = state.currentStep in listOf(
                SurveyStep.PLAN_RAW_INPUT,
                SurveyStep.PLAN_REVIEW_MENU,
                SurveyStep.PLAN_EDIT_SELECT,
                SurveyStep.PLAN_EDIT_TEXT,
                SurveyStep.PLAN_ADD_TEXT,
                SurveyStep.PLAN_DELETE_SELECT,
                SurveyStep.PLAN_REWRITE_TEXT
            )

            if (isPlanningStep && !state.reminderSent && (now - state.startTimeMillis >= 3600000L)) {
                state.reminderSent = true
                val weekText = if (state.isMondayPlanning) "эту" else "следующую"
                bot.sendMessage(
                    chatId = ChatId.fromId(tgId),
                    text = "Ты ещё не подтвердил(а) план на $weekText неделю. Проверь его и нажми “✅ Всё верно, сохранить”."
                )
            }
        }
    }
}