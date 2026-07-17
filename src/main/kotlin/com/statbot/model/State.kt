package com.statbot.model

import java.util.concurrent.ConcurrentHashMap

enum class UserRole { STUDENT, TEACHER }

enum class SurveyStep {
    REG_FULL_NAME, REG_PROJECT, // Шаги ручной регистрации
    Q1_FEELING, Q1_PROBLEM,     // Первый вопрос и описание проблемы
    Q2_TASKS, Q3_EXTRA, Q4_CALL_YESNO, Q4_CALL_RATING, Q4_CALL_NOTES, Q4_CALL_THESIS,
    Q5_HUBSTAFF_YESNO, Q5_HUBSTAFF_PHOTO, Q6_SPEED, Q6_SPEED_WHY, Q7_AUTONOMY, Q8_DONE, Q8_DONE_WHY,
    Q9_ENGAGE, Q10_NEWINFO, Q10_NEWINFO_SHARE, FINISHED
}

data class SurveyState(
    var currentStep: SurveyStep = SurveyStep.Q1_FEELING,
    val reportId: Int,
    val reportData: MutableMap<String, String> = mutableMapOf()
)

object SessionManager {
    val activeSurveys = ConcurrentHashMap<Long, SurveyState>()
}