package com.statbot.db

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.date
import org.jetbrains.exposed.sql.javatime.datetime

/*
 * Схема совместима со старой базой statbot.db: существующие таблицы/колонки сохранены,
 * новые колонки добавляются автоматически при старте (все nullable).
 */

object Users : Table("users") {
    val tgId = long("tg_id")
    val role = varchar("role", 20).default("STUDENT")
    val fullName = varchar("full_name", 100).nullable()
    val project = varchar("project", 200).nullable()
    val username = varchar("username", 100).nullable()
    val workType = varchar("work_type", 50).nullable()   // устарело (шаг убран из онбординга)
    val workHours = varchar("work_hours", 50).nullable() // устарело
    val startDate = date("start_date").nullable()        // старое поле «дата старта», читается для совместимости
    val goalsYear = text("goals_year").nullable()
    val goals3Months = text("goals_3_months").nullable()
    val currentWeeklyTasks = text("current_weekly_tasks").nullable() // устарело, планы теперь в weekly_plans
    val currentStep = varchar("current_step", 100).nullable()        // устарело, теперь session_json
    val activeReportId = integer("active_report_id").nullable()      // устарело

    // Новые поля
    val goalsYearInitial = text("goals_year_initial").nullable()
    val onboardedAt = date("onboarded_at").nullable()
    val cycleStart = date("cycle_start").nullable()
    val sessionJson = text("session_json").nullable()

    override val primaryKey = PrimaryKey(tgId)
}

object DailyReports : Table("daily_reports") {
    val id = integer("id").autoIncrement()
    val userTgId = long("user_tg_id").references(Users.tgId)
    val date = date("report_date")
    val cycleDay = integer("cycle_day").default(1)
    val isCompleted = bool("is_completed").default(false)
    val isIgnored = bool("is_ignored").default(false)
    val aiFlagged = bool("ai_flagged").default(false)
    val aiSummary = text("ai_summary").nullable()
    override val primaryKey = PrimaryKey(id)
}

object Answers : Table("answers") {
    val id = integer("id").autoIncrement()
    val reportId = integer("report_id").references(DailyReports.id)
    val questionKey = varchar("question_key", 50)
    val answerText = text("answer_text")
    val questionText = text("question_text").nullable()
    val score = double("score").nullable()
    override val primaryKey = PrimaryKey(id)
}

object WeeklyPlans : Table("weekly_plans") {
    val id = integer("id").autoIncrement()
    val userTgId = long("user_tg_id").references(Users.tgId)
    val weekStart = date("week_start")
    val tasks = text("tasks")
    val createdAt = datetime("created_at")
    override val primaryKey = PrimaryKey(id)
}
