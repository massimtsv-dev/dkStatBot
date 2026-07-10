package com.statbot.model

import java.util.concurrent.ConcurrentHashMap

// Перечисление ролей
enum class UserRole { STUDENT, TEACHER }

// Состояние текущего опроса пользователя
data class SurveyState(
    val questionIds: List<Int>, // ID всех вопросов на сегодня
    var currentIndex: Int = 0   // На каком вопросе сейчас находится
)

// Хранилище активных сессий (в оперативной памяти)
object SessionManager {
    val activeSurveys = ConcurrentHashMap<Long, SurveyState>()
}