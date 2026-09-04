package com.statbot.notifications

import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.entities.ChatId
import com.statbot.db.Users
import com.statbot.model.SurveyManager
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object SchedulerService {
    private val scheduler = Executors.newSingleThreadScheduledExecutor()

    fun start(bot: Bot) {
        scheduler.scheduleAtFixedRate({
            try {
                val now = java.time.LocalTime.now()
                val today = LocalDate.now()

                if (now.hour == 10 && today.dayOfWeek != DayOfWeek.SATURDAY && today.dayOfWeek != DayOfWeek.SUNDAY) {
                    val studentTgIds = transaction {
                        Users.selectAll().map { it[Users.tgId] }
                    }

                    studentTgIds.forEach { tgId ->
                        SurveyManager.startSurvey(bot, tgId, ChatId.fromId(tgId))
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, 0, 1, TimeUnit.HOURS)
    }
}