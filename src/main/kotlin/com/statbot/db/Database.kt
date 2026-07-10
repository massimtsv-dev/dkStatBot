package com.statbot.db

import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.javatime.datetime

// Таблица пользователей и их ролей
object Users : IntIdTable() {
    val tgId = long("tg_id").uniqueIndex()
    val role = varchar("role", 20).default("STUDENT")
}

// Таблица метрик
object Questions : IntIdTable() {
    val text = varchar("text", 255)
    val isActive = bool("is_active").default(true)
    val sortOrder = integer("sort_order").default(0)
}

// Таблица ответов студентов
object Answers : IntIdTable() {
    val userId = reference("user_id", Users)
    val questionId = reference("question_id", Questions)
    val score = integer("score")
    val createdAt = datetime("created_at")
}