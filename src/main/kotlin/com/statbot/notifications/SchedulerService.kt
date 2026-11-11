package com.statbot.notifications

import com.statbot.db.Repository
import com.statbot.model.Role
import com.statbot.survey.SurveyEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.time.DayOfWeek
import java.time.DayOfWeek.FRIDAY
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.THURSDAY
import java.time.DayOfWeek.TUESDAY
import java.time.DayOfWeek.WEDNESDAY
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Расписание рассылок (время московское, настраивается через TIMEZONE).
 * Если бот был выключен в момент рассылки, она догоняется в течение CATCH_UP_HOURS часов;
 * повторной отправки не будет — опрос на день создаётся один раз.
 */
class SchedulerService(
    private val engine: SurveyEngine,
    private val repo: Repository,
    private val zone: ZoneId,
    private val scope: CoroutineScope,
) {
    private val log = LoggerFactory.getLogger(SchedulerService::class.java)

    private class Job(val name: String, val days: Set<DayOfWeek>, val time: LocalTime, val action: suspend () -> Unit) {
        var lastRun: LocalDate? = null
    }

    private val jobs = listOf(
        Job("Ежедневный опрос", setOf(MONDAY, TUESDAY, WEDNESDAY, THURSDAY), LocalTime.of(10, 0)) { broadcastSurveys() },
        Job("Пятничный опрос", setOf(FRIDAY), LocalTime.of(18, 0)) { broadcastSurveys() },
        Job("Вечернее напоминание", setOf(MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY), LocalTime.of(20, 0)) { engine.eveningReminders() },
    )

    fun start() {
        scope.launch {
            while (isActive) {
                val now = ZonedDateTime.now(zone)
                jobs.forEach { job ->
                    val t = now.toLocalTime()
                    if (now.dayOfWeek in job.days && job.lastRun != now.toLocalDate() &&
                        !t.isBefore(job.time) && t.isBefore(job.time.plusHours(CATCH_UP_HOURS))
                    ) {
                        job.lastRun = now.toLocalDate()
                        log.info("▶ {}", job.name)
                        scope.launch { runCatching { job.action() }.onFailure { log.error("Ошибка задачи «{}»", job.name, it) } }
                    }
                }
                runCatching { engine.checkPlanReminders() }.onFailure { log.error("Ошибка проверки напоминаний", it) }
                delay(30_000)
            }
        }
    }

    private suspend fun broadcastSurveys() {
        val students = repo.students().filter { it.role == Role.STUDENT && it.isOnboarded }
        log.info("Рассылка опроса: {} сотрудников", students.size)
        students.forEach { p ->
            runCatching { engine.locked(p.tgId) { engine.startDaily(p.tgId, manual = false) } }
                .onFailure { log.warn("Не удалось отправить опрос {}: {}", p.tgId, it.toString()) }
            delay(50) // не упираемся в лимиты Telegram
        }
    }

    companion object {
        private const val CATCH_UP_HOURS = 3L
    }
}
