package com.statbot.model

import java.util.concurrent.ConcurrentHashMap

enum class SurveyStep {
    // 1. Онбординг профиля
    REG_WELCOME,
    REG_FULL_NAME,
    REG_ROLE_DESC,
    REG_WORK_TYPE,
    REG_WORK_HOURS,

    // 2. Первичная постановка целей
    INIT_GOALS_YEAR,
    INIT_GOALS_3MONTHS,

    // 3. Обновление целей вручную
    UPDATE_GOALS_YEAR,
    UPDATE_GOALS_3MONTHS,

    // 4. Сценарий ИИ-планирования задач на неделю
    PLAN_RAW_INPUT,          // Ожидание развернутого ответа (проекты, задачи, результаты)
    PLAN_REVIEW_MENU,         // Меню подтверждения и редактирования
    PLAN_EDIT_SELECT,         // Выбор задачи для редактирования
    PLAN_EDIT_TEXT,           // Ввод текста измененной задачи
    PLAN_ADD_TEXT,            // Ввод новой задачи
    PLAN_DELETE_SELECT,       // Выбор задачи для удаления
    PLAN_REWRITE_TEXT,        // Переписывание плана целиком

    // 5. Понедельник (проверка существующих задач)
    MON_TASKS_CHECK,
    MON_CHANGE_MENU,
    MON_EDIT_SELECT_TASK,
    MON_EDIT_TASK_TEXT,
    MON_ADD_TASK_TEXT,
    MON_DELETE_SELECT_TASK,
    MON_REWRITE_TASKS,

    // 6. Пятница (отметка выполненного и подведение итогов)
    FRI_WEEK_SCORE,
    FRI_CALL_DAYS,
    FRI_SELECT_TASKS,
    FRI_MISSED_REASON,
    FRI_EXTRA_TASKS_YESNO,
    FRI_EXTRA_TASKS_LIST,
    FRI_NEXT_WEEK_TASKS,

    // 7. Дни 30-дневного цикла
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

    // 8. Периодические опросы
    MONTHLY_GOALS_PROGRESS,
    Q_HEALTH_SCORE,
    Q_YEAR_GOALS_PROGRESS,
    Q_COMPANY_ATTENTION,
    Q_GOALS_RESULT_TEXT,
    Q_GOALS_RESULT_SCORE,
    Q_GOALS_RESULT_IMPROVE,
    Q_NEW_3MONTHS_GOALS,
    Q_NEW_PROJECTS_YESNO,
    Q_NEW_PROJECTS_LIST,
    HALF_YEAR_WORLD_PROJECTS,
    YEARLY_GOALS_RESULT_TEXT,
    YEARLY_GOALS_RESULT_SCORE,
    YEARLY_GOALS_RESULT_IMPROVE,
    YEARLY_NEXT_GOALS,

    FINISHED
}

data class SurveyState(
    var currentStep: SurveyStep = SurveyStep.REG_WELCOME,
    val reportId: Int,
    val reportData: MutableMap<String, String> = mutableMapOf(),
    val selectedTaskIndices: MutableSet<Int> = mutableSetOf(),
    var editingTaskIndex: Int = -1,

    // Поля для ИИ-планирования задач и очистки временных сообщений
    var tempTasks: MutableList<String> = mutableListOf(),
    val tempMessageIds: MutableList<Long> = mutableListOf(),
    var isMondayPlanning: Boolean = false,
    var reminderSent: Boolean = false,
    var startTimeMillis: Long = System.currentTimeMillis()
)

object SessionManager {
    val activeSurveys = ConcurrentHashMap<Long, SurveyState>()
}