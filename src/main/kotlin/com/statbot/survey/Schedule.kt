package com.statbot.survey

import com.statbot.model.Profile
import com.statbot.util.firstMondayOfMonth
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Какие вопросы задавать в конкретный день.
 *
 * 30-дневный цикл начинается с первого понедельника месяца (День 1 — ПН, День 30 — ВТ пятой недели)
 * и для нового сотрудника стартует с первого понедельника СЛЕДУЮЩЕГО месяца после онбординга.
 * Еженедельные вопросы (ПН — проверка плана, ПТ — итоги недели и план) идут каждую неделю, начиная со следующего дня после онбординга.
 *
 * Периодические вопросы добавляются в дни, где по одному вопросу (СР: дни 3, 10, 17, 24):
 *  - раз в месяц (со 2-го месяца цикла) — день 3;
 *  - раз в квартал (месяцы 3, 6, 9…) — вопросы 1–5 по дням 3, 10, 17, 24 (заменяют ежемесячный);
 *  - раз в полгода — день 17;
 *  - раз в год — день 10.
 */
object Schedule {
    private val CYCLE: Map<Int, List<String>> = mapOf(
        2 to listOf("ENERGY", "SPEED"),
        3 to listOf("NEW_INFO"),
        4 to listOf("ENGAGEMENT"),
        9 to listOf("INITIATIVE", "PLANNING_Q"),
        10 to listOf("NOTES_Q"),
        11 to listOf("CALL_ENG", "WORKLOAD"),
        16 to listOf("EMOTIONAL", "SPEED"),
        17 to listOf("NEW_INFO"),
        18 to listOf("CALL_VALUE", "EXPERT"),
        23 to listOf("NOTES_Q", "UNFINISHED"),
        24 to listOf("AUTONOMY"),
        25 to listOf("HAPPINESS", "HARD_TASKS"),
        30 to listOf("HOURS", "POTENTIAL"),
    )

    val CYCLE_DAYS: Set<Int> get() = CYCLE.keys

    val QUARTERLY = listOf(
        StepIds.Q_HEALTH, StepIds.Q_YEAR_PROGRESS, StepIds.Q_ATTENTION,
        StepIds.Q_GOALS_RESULT, StepIds.Q_GOALS_SAT, StepIds.Q_NEW_GOALS, StepIds.Q_PROJECTS,
    )
    val YEARLY = listOf(StepIds.Y_RESULT, StepIds.Y_SAT, StepIds.Y_NEXT)
    val FRIDAY = listOf(StepIds.FRI_WEEK, StepIds.FRI_CALLS, StepIds.FRI_TASKS, StepIds.FRI_EXTRA, StepIds.PLAN_FRI)

    /** Начало цикла для нового сотрудника — первый понедельник следующего месяца. */
    fun cycleStartFor(onboarded: LocalDate): LocalDate = onboarded.plusMonths(1).firstMondayOfMonth()

    /** Первый понедельник текущего «циклового» месяца (или предыдущего, если он ещё не наступил). */
    fun cycleAnchor(date: LocalDate): LocalDate {
        val thisMonth = date.firstMondayOfMonth()
        return if (date >= thisMonth) thisMonth else date.minusMonths(1).firstMondayOfMonth()
    }

    /** День цикла 1..30 или null (вне цикла / цикл ещё не начался). */
    fun cycleDay(date: LocalDate, profile: Profile): Int? {
        val start = profile.cycleStart ?: profile.onboardedAt?.let(::cycleStartFor) ?: return null
        val anchor = cycleAnchor(date)
        if (anchor < start) return null
        val day = ChronoUnit.DAYS.between(anchor, date).toInt() + 1
        return day.takeIf { it in 1..30 }
    }

    fun stepsForCycleDay(day: Int): List<String> = CYCLE[day].orEmpty()

    fun periodicSteps(day: Int, monthIndex: Int): List<String> {
        if (monthIndex <= 0) return emptyList()
        val quarter = monthIndex % 3 == 0
        val result = mutableListOf<String>()
        when (day) {
            3 -> if (quarter) result += StepIds.Q_HEALTH else result += StepIds.MONTHLY
            10 -> {
                if (quarter) result += StepIds.Q_YEAR_PROGRESS
                if (monthIndex % 12 == 0) result += YEARLY
            }
            17 -> {
                if (quarter) result += StepIds.Q_ATTENTION
                if (monthIndex % 6 == 0) result += StepIds.HALF_PROJECTS
            }
            24 -> if (quarter) result += listOf(StepIds.Q_GOALS_RESULT, StepIds.Q_GOALS_SAT, StepIds.Q_NEW_GOALS, StepIds.Q_PROJECTS)
        }
        return result
    }

    /**
     * Шаги на дату. hasPlanThisWeek — сохранён ли план на текущую неделю (нужно для пятницы).
     * Возвращает пустой список, если в этот день вопросов нет.
     */
    fun stepsFor(date: LocalDate, profile: Profile, hasPlanThisWeek: Boolean): Pair<Int, List<String>> {
        val onboarded = profile.onboardedAt ?: return 0 to emptyList()
        if (!date.isAfter(onboarded)) return 0 to emptyList()

        val day = cycleDay(date, profile)
        val steps = mutableListOf<String>()
        when (date.dayOfWeek) {
            DayOfWeek.MONDAY -> steps += StepIds.MON_CHECK
            // Если плана на эту неделю нет — из пятничного блока остаётся только «составь план на следующую неделю»
            DayOfWeek.FRIDAY -> steps += if (hasPlanThisWeek) FRIDAY else listOf(StepIds.PLAN_FRI)
            DayOfWeek.SATURDAY, DayOfWeek.SUNDAY -> return 0 to emptyList()
            else -> {}
        }
        if (day != null) {
            steps += stepsForCycleDay(day)
            val start = profile.cycleStart ?: cycleStartFor(onboarded)
            val monthIndex = ChronoUnit.MONTHS.between(start.withDayOfMonth(1), cycleAnchor(date).withDayOfMonth(1)).toInt()
            steps += periodicSteps(day, monthIndex)
        }
        return (day ?: 0) to steps.distinct()
    }
}
