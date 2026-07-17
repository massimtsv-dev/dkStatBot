package com.statbot.notifications

import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.InlineKeyboardMarkup
import com.github.kotlintelegrambot.entities.keyboard.InlineKeyboardButton
import com.statbot.db.Users
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object SchedulerService {
    private val scheduler = Executors.newScheduledThreadPool(2)
    private val MSK_ZONE = ZoneId.of("Europe/Moscow")

    fun start(bot: Bot) {
        scheduleDailyTask(10, 0) { sendFocusCheck(bot) }
        scheduleDailyTask(13, 0) { sendFocusCheck(bot) }
        scheduleDailyTask(18, 0) { sendFocusCheck(bot) }
        scheduleDailyTask(20, 0) { sendEveningReminder(bot) }
        scheduleDailyTask(7, 0) { checkIgnoredSurveys() }
    }

    private fun scheduleDailyTask(hour: Int, minute: Int, task: () -> Unit) {
        val now = ZonedDateTime.now(MSK_ZONE)
        var nextRun = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
        if (now.isAfter(nextRun)) nextRun = nextRun.plusDays(1)

        val initialDelay = Duration.between(now, nextRun).toSeconds()
        scheduler.scheduleAtFixedRate(task, initialDelay, TimeUnit.DAYS.toSeconds(1), TimeUnit.SECONDS)
    }

    private fun sendFocusCheck(bot: Bot) {
        val studentIds = transaction { Users.select { Users.role eq "STUDENT" }.map { it[Users.tgId] } }
        val markup = InlineKeyboardMarkup.createSingleRowKeyboard(
            InlineKeyboardButton.CallbackData("🎬 В процессе работы", "focus_work"),
            InlineKeyboardButton.CallbackData("⏸ На перерыве", "focus_break"),
            InlineKeyboardButton.CallbackData("🔄 Ничего не делаю", "focus_none")
        )
        studentIds.forEach { id ->
            bot.sendMessage(ChatId.fromId(id), "🔔 Минутка фокуса! Ты тут? Чем занят прямо сейчас?", replyMarkup = markup)
        }
    }

    private fun sendEveningReminder(bot: Bot) {
        val studentIds = transaction { Users.select { Users.role eq "STUDENT" }.map { it[Users.tgId] } }
        studentIds.forEach { id ->
            bot.sendMessage(ChatId.fromId(id), "🔔 Ежедневный отчёт\nЕсли ещё не заполнял за сегодня — самое время сделать это.")
        }
    }

    private fun checkIgnoredSurveys() {
        // Здесь можно дописать проставление флага isIgnored в БД для тех, кто не прошел до 7 утра
    }
}