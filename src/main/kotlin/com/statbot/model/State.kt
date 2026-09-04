package com.statbot.model

import java.util.concurrent.ConcurrentHashMap

enum class SurveyStep {
    // Регистрация профиля
    REG_FULL_NAME, REG_PROJECT,

    // Ввод начальных целей (один раз в самом начале)
    INIT_GOALS_YEAR, INIT_GOALS_3MONTHS,

    // Еженедельные регулярные вопросы
    MON_TASKS_CHECK, MON_TASKS_CHANGES,
    FRI_WEEK_SCORE, FRI_CALL_DAYS, FRI_TASK_PCT, FRI_TASK_MISSED_REASON, FRI_EXTRA_TASKS_YESNO, FRI_EXTRA_TASKS_LIST, FRI_NEXT_WEEK_TASKS,

    // Вопросы по дням цикла (ТЗ)
    DAY2_ENERGY, DAY2_SPEED, DAY2_SPEED_WHY,
    DAY3_NEW_INFO,
    DAY4_ENGAGEMENT, DAY4_ENGAGEMENT_WHY,
    DAY9_INITIATIVE, DAY9_PLANNING_QUALITY,
    DAY10_NOTES_QUALITY,
    DAY11_CALL_ENGAGEMENT, DAY11_WORKLOAD,
    DAY16_EMOTIONAL, DAY16_SPEED, DAY16_SPEED_WHY,
    DAY17_NEW_INFO,
    DAY18_CALL_VALUE, DAY18_EXPERT_LEVEL,
    DAY23_NOTES_QUALITY, DAY23_UNFINISHED_TASKS,
    DAY24_AUTONOMY,
    DAY25_HAPPINESS, DAY25_HARD_TASKS,
    DAY30_HOURS, DAY30_POTENTIAL,

    FINISHED
}

data class SurveyState(
    var currentStep: SurveyStep = SurveyStep.REG_FULL_NAME,
    val reportId: Int,
    val reportData: MutableMap<String, String> = mutableMapOf()
)

object SessionManager {
    val activeSurveys = ConcurrentHashMap<Long, SurveyState>()
}