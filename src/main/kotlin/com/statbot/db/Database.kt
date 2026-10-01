package com.statbot.db

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.javatime.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.DayOfWeek
import java.time.LocalDate

object Users : Table("users") {
    val tgId = long("tg_id")
    val role = varchar("role", 20).default("STUDENT")
    val fullName = varchar("full_name", 100).nullable()
    val project = varchar("project", 200).nullable()
    val username = varchar("username", 100).nullable()
    val workType = varchar("work_type", 50).nullable()
    val workHours = varchar("work_hours", 50).nullable()
    val startDate = date("start_date").nullable()
    val goalsYear = text("goals_year").nullable()
    val goals3Months = text("goals_3_months").nullable()
    val currentWeeklyTasks = text("current_weekly_tasks").nullable()
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
    override val primaryKey = PrimaryKey(id)
}

object DbRepository {
    fun initDb() {
        Database.connect("jdbc:sqlite:statbot.db", driver = "org.sqlite.JDBC")
        transaction { SchemaUtils.create(Users, DailyReports, Answers) }
    }

    /**
     * Расчет дня 30-дневного цикла по календарной неделе месяца (общий для всей компании)
     */
    fun getCalendarCycleDay(date: LocalDate = LocalDate.now()): Int {
        if (date.dayOfMonth == 30) return 30

        val occurrence = (date.dayOfMonth - 1) / 7 + 1
        return when (date.dayOfWeek) {
            DayOfWeek.TUESDAY -> when (occurrence) {
                1 -> 2
                2 -> 9
                3 -> 16
                4 -> 23
                else -> -1
            }
            DayOfWeek.WEDNESDAY -> when (occurrence) {
                1 -> 3
                2 -> 10
                3 -> 17
                4 -> 24
                else -> -1
            }
            DayOfWeek.THURSDAY -> when (occurrence) {
                1 -> 4
                2 -> 11
                3 -> 18
                4 -> 25
                else -> -1
            }
            else -> -1
        }
    }

    fun getOrCreateReport(tgId: Long, date: LocalDate = LocalDate.now()): Int {
        return transaction {
            val existing = DailyReports.select { (DailyReports.userTgId eq tgId) and (DailyReports.date eq date) }.singleOrNull()
            if (existing != null) return@transaction existing[DailyReports.id]

            val cycleDayVal = getCalendarCycleDay(date)

            DailyReports.insert {
                it[userTgId] = tgId
                it[DailyReports.date] = date
                it[cycleDay] = if (cycleDayVal > 0) cycleDayVal else 1
            }[DailyReports.id]
        }
    }

    fun saveAnswer(reportId: Int, key: String, text: String) {
        transaction {
            Answers.insert {
                it[Answers.reportId] = reportId
                it[questionKey] = key
                it[answerText] = text
            }
        }
    }

    fun updateOnboardingProfile(
        tgId: Long,
        fullName: String? = null,
        project: String? = null,
        workType: String? = null,
        workHours: String? = null
    ) = transaction {
        Users.update({ Users.tgId eq tgId }) {
            if (fullName != null) it[Users.fullName] = fullName
            if (project != null) it[Users.project] = project
            if (workType != null) it[Users.workType] = workType
            if (workHours != null) it[Users.workHours] = workHours
        }
    }

    fun saveOrUpdateUsername(tgId: Long, uname: String?) = transaction {
        if (!uname.isNullOrBlank()) {
            Users.update({ Users.tgId eq tgId }) {
                it[username] = uname
            }
        }
    }

    fun getUserProfile(tgId: Long): Pair<String?, String?> = transaction {
        Users.select { Users.tgId eq tgId }.singleOrNull()?.let {
            Pair(it[Users.fullName], it[Users.project])
        } ?: Pair(null, null)
    }

    fun setUserGoals(tgId: Long, yearGoals: String?, threeMonthGoals: String?) = transaction {
        val currentStartDate = Users.select { Users.tgId eq tgId }.singleOrNull()?.get(Users.startDate)
        if (yearGoals != null || threeMonthGoals != null || currentStartDate == null) {
            Users.update({ Users.tgId eq tgId }) {
                if (yearGoals != null) it[goalsYear] = yearGoals
                if (threeMonthGoals != null) it[goals3Months] = threeMonthGoals
                if (currentStartDate == null) it[startDate] = LocalDate.now()
            }
        }
    }

    fun setWeeklyTasks(tgId: Long, tasks: String) = transaction {
        Users.update({ Users.tgId eq tgId }) { it[currentWeeklyTasks] = tasks }
    }

    fun getUserData(tgId: Long) = transaction {
        Users.select { Users.tgId eq tgId }.singleOrNull()
    }

    fun saveAiAlert(reportId: Int, summary: String) = transaction {
        DailyReports.update({ DailyReports.id eq reportId }) {
            it[aiFlagged] = true
            it[aiSummary] = summary
        }
    }

    fun markReportCompleted(reportId: Int) = transaction {
        DailyReports.update({ DailyReports.id eq reportId }) {
            it[isCompleted] = true
        }
    }

    fun getLast5AiAlerts(): List<String> = transaction {
        (DailyReports innerJoin Users)
            .select { DailyReports.aiFlagged eq true }
            .orderBy(DailyReports.id to SortOrder.DESC)
            .limit(5)
            .map {
                "👤 ${it[Users.fullName] ?: "Сотрудник"} [${it[Users.project] ?: "Без проекта"}]\n📅 Дата: ${it[DailyReports.date]}\n🚨 ИИ: ${it[DailyReports.aiSummary]}"
            }
    }

    fun getAllStudents(): List<Pair<Long, String>> = transaction {
        Users.select { (Users.fullName.isNotNull()) and (Users.role eq "STUDENT") }.map {
            it[Users.tgId] to (it[Users.fullName] ?: "ID: ${it[Users.tgId]}")
        }
    }

    fun getStudentStats(studentTgId: Long): String = transaction {
        val userRow = Users.select { Users.tgId eq studentTgId }.singleOrNull()
            ?: return@transaction "❌ Ученик не найден в базе."

        val total = DailyReports.select { DailyReports.userTgId eq studentTgId }.count()
        val completed = DailyReports.select { (DailyReports.userTgId eq studentTgId) and (DailyReports.isCompleted eq true) }.count()
        val flagged = DailyReports.select { (DailyReports.userTgId eq studentTgId) and (DailyReports.aiFlagged eq true) }.count()

        val rawUsername = userRow[Users.username]
        val telegramDisplay = if (!rawUsername.isNullOrBlank()) "@$rawUsername" else "$studentTgId"

        """
            👤 Ученик: ${userRow[Users.fullName] ?: "Не заполнено"}
            🎬 Проект/Роль: ${userRow[Users.project] ?: "Не заполнено"}
            💼 Формат: ${userRow[Users.workType] ?: "Не заполнено"} (${userRow[Users.workHours] ?: "—"})
            🆔 Telegram ID: $telegramDisplay
            -----------------------------------
            📈 Всего сессий опроса: $total
            ✅ Пройдено полностью: $completed
            🚨 Анализ выгорания AI: $flagged
        """.trimIndent()
    }

    fun getPeriodStats(startDate: LocalDate): String = transaction {
        val total = DailyReports.select { DailyReports.date greaterEq startDate }.count()
        val completed = DailyReports.select { (DailyReports.date greaterEq startDate) and (DailyReports.isCompleted eq true) }.count()
        val flagged = DailyReports.select { (DailyReports.date greaterEq startDate) and (DailyReports.aiFlagged eq true) }.count()

        val query = (Answers innerJoin DailyReports)
            .select { DailyReports.date greaterEq startDate }

        val keyValues = mutableMapOf<String, MutableList<Double>>()

        query.forEach { row ->
            val key = row[Answers.questionKey]
            val text = row[Answers.answerText].trim()

            val numericValue = when (text) {
                "9-10" -> 9.5
                "7-8" -> 7.5
                "4-6" -> 5.0
                "1-3" -> 2.0
                "100%" -> 100.0
                "70-90%" -> 80.0
                "50%" -> 50.0
                "<30%" -> 20.0
                "5" -> 5.0
                "4" -> 4.0
                "3" -> 3.0
                "2" -> 2.0
                "1" -> 1.0
                else -> text.toDoubleOrNull()
            }

            if (numericValue != null) {
                keyValues.getOrPut(key) { mutableListOf() }.add(numericValue)
            }
        }

        fun getAverageByKeys(vararg keys: String): String {
            val combinedList = mutableListOf<Double>()
            keys.forEach { key ->
                keyValues[key]?.let { combinedList.addAll(it) }
            }
            if (combinedList.isEmpty()) return "Нет данных"
            val avg = combinedList.average()
            return (Math.round(avg * 10) / 10.0).toString()
        }

        val avgEnergy = getAverageByKeys("DAY2_ENERGY", "DAY16_EMOTIONAL")
        val avgSpeed = getAverageByKeys("DAY2_SPEED", "DAY16_SPEED")
        val avgEngagement = getAverageByKeys("DAY4_ENGAGEMENT")
        val avgInitiative = getAverageByKeys("DAY9_INITIATIVE")
        val avgCallEngagement = getAverageByKeys("DAY11_CALL_ENGAGEMENT")
        val avgAutonomy = getAverageByKeys("DAY24_AUTONOMY")
        val avgHappiness = getAverageByKeys("DAY25_HAPPINESS")
        val avgWeekScore = getAverageByKeys("FRI_WEEK_SCORE")
        val avgTaskPct = getAverageByKeys("FRI_TASK_PCT")
        val formattedTaskPct = if (avgTaskPct != "Нет данных") "$avgTaskPct%" else "Нет данных"

        """
            📋 Общая активность:
            Всего запущено опросов: $total
            ✅ Успешно заполнено: $completed
            🚨 Алертов от OpenAI: $flagged

            📊 Средние показатели команды:
            ⚡ Энергия и настрой (1-10): $avgEnergy
            🚀 Скорость работы (1-10): $avgSpeed
            🔥 Вовлеченность в проекты (1-10): $avgEngagement
            💡 Инициативность (1-10): $avgInitiative
            📞 Включенность на созвонах (1-10): $avgCallEngagement
            🛡 Самостоятельность (1-10): $avgAutonomy
            😊 Счастье в компании (1-10): $avgHappiness
            📅 Оценка рабочей недели (1-10): $avgWeekScore
            🎯 Выполнение задач недели: $formattedTaskPct
        """.trimIndent()
    }

    fun getUserCycleDay(tgId: Long, date: LocalDate = LocalDate.now()): Int = transaction {
        getOrCreateReport(tgId, date)
        DailyReports.select { (DailyReports.userTgId eq tgId) and (DailyReports.date eq date) }
            .singleOrNull()?.get(DailyReports.cycleDay) ?: getCalendarCycleDay(date)
    }
}