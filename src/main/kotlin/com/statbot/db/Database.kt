package com.statbot.db

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.javatime.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDate

object Users : Table("users") {
    val tgId = long("tg_id")
    val role = varchar("role", 20).default("STUDENT")
    val hasHubstaff = bool("has_hubstaff").nullable()
    val fullName = varchar("full_name", 100).nullable()
    val project = varchar("project", 100).nullable()
    val username = varchar("username", 100).nullable()
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

    fun getOrCreateReport(tgId: Long, date: LocalDate = LocalDate.now()): Int {
        return transaction {
            val existing = DailyReports.select { (DailyReports.userTgId eq tgId) and (DailyReports.date eq date) }.singleOrNull()
            if (existing != null) return@transaction existing[DailyReports.id]

            val user = Users.select { Users.tgId eq tgId }.singleOrNull()
            val start = user?.get(Users.startDate) ?: date
            val daysDiff = (date.toEpochDay() - start.toEpochDay()).toInt() + 1

            DailyReports.insert {
                it[userTgId] = tgId
                it[DailyReports.date] = date
                it[cycleDay] = if (daysDiff > 0) ((daysDiff - 1) % 30) + 1 else 1
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

    fun getUserHubstaffStatus(tgId: Long): Boolean? = transaction {
        Users.select { Users.tgId eq tgId }.singleOrNull()?.get(Users.hasHubstaff)
    }

    fun setUserHubstaffStatus(tgId: Long, status: Boolean) = transaction {
        Users.update({ Users.tgId eq tgId }) { it[hasHubstaff] = status }
    }

    fun updateUserProfile(tgId: Long, name: String?, proj: String?) = transaction {
        Users.update({ Users.tgId eq tgId }) {
            if (name != null) it[fullName] = name
            if (proj != null) it[project] = proj
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
        Users.update({ Users.tgId eq tgId }) {
            if (yearGoals != null) it[goalsYear] = yearGoals
            if (threeMonthGoals != null) it[goals3Months] = threeMonthGoals
            if (currentStartDate == null) it[startDate] = LocalDate.now()
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
            .orderBy(DailyReports.id to org.jetbrains.exposed.sql.SortOrder.DESC)
            .limit(5)
            .map {
                "👤 **${it[Users.fullName] ?: "Сотрудник"}** [${it[Users.project] ?: "Без проекта"}]\n📅 Дата: ${it[DailyReports.date]}\n🚨 **ИИ:** ${it[DailyReports.aiSummary]}"
            }
    }

    fun getAllStudents(): List<Pair<Long, String>> = transaction {
        Users.select { Users.fullName.isNotNull() }.map {
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
            👤 **Ученик:** ${userRow[Users.fullName] ?: "Не заполнено"}
            🎬 **Проект:** ${userRow[Users.project] ?: "Не заполнено"}
            🆔 **Telegram ID:** $telegramDisplay
            -----------------------------------
            📈 Всего сессий опроса: $total
            ✅ Пройдено полностью: $completed
            🚨 Флагов выгорания от ИИ: $flagged
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
            📋 **Общая активность:**
            Всего запущено опросов: $total
            ✅ Успешно заполнено: $completed
            🚨 Алертов от OpenAI: $flagged

            📊 **Средние показатели команды (ТЗ):**
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
}