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
    override val primaryKey = PrimaryKey(tgId)
}

object DailyReports : Table("daily_reports") {
    val id = integer("id").autoIncrement()
    val userTgId = long("user_tg_id").references(Users.tgId)
    val date = date("report_date")
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
        transaction {
            SchemaUtils.create(Users, DailyReports, Answers)
        }
    }

    fun getOrCreateReport(tgId: Long, date: LocalDate = LocalDate.now()): Int {
        return transaction {
            val existing = DailyReports.select { (DailyReports.userTgId eq tgId) and (DailyReports.date eq date) }.singleOrNull()
            if (existing != null) return@transaction existing[DailyReports.id]

            DailyReports.insert {
                it[userTgId] = tgId
                it[this.date] = date
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

    fun getUserProfile(tgId: Long): Pair<String?, String?> = transaction {
        Users.select { Users.tgId eq tgId }.singleOrNull()?.let {
            Pair(it[Users.fullName], it[Users.project])
        } ?: Pair(null, null)
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
        val user = Users.select { Users.tgId eq studentTgId }.singleOrNull() ?: return@transaction "❌ Ученик не найден в базе."
        val total = DailyReports.select { DailyReports.userTgId eq studentTgId }.count()
        val completed = DailyReports.select { (DailyReports.userTgId eq studentTgId) and (DailyReports.isCompleted eq true) }.count()
        val flagged = DailyReports.select { (DailyReports.userTgId eq studentTgId) and (DailyReports.aiFlagged eq true) }.count()

        """
            👤 **Ученик:** ${user[Users.fullName] ?: "Не заполнено"}
            🎬 **Проект:** ${user[Users.project] ?: "Не заполнено"}
            🆔 **Telegram ID:** $studentTgId
            -----------------------------------
            📈 Всего сессий опроса: $total
            ✅ Пройдено полностью: $completed
            🚨 Флагов выгорания от ИИ: $flagged
        """.trimIndent()
    }

    // --- ОБНОВЛЕННЫЙ МЕТОД: РАСЧЕТ СРЕДНИХ ПОКАЗАТЕЛЕЙ ПО ПЕРИОДАМ ---
    fun getPeriodStats(startDate: LocalDate): String = transaction {
        val total = DailyReports.select { DailyReports.date greaterEq startDate }.count()
        val completed = DailyReports.select { (DailyReports.date greaterEq startDate) and (DailyReports.isCompleted eq true) }.count()
        val flagged = DailyReports.select { (DailyReports.date greaterEq startDate) and (DailyReports.aiFlagged eq true) }.count()

        // Извлекаем все ответы на вопросы за указанный промежуток времени
        val query = (Answers innerJoin DailyReports)
            .select { DailyReports.date greaterEq startDate }

        val keyValues = mutableMapOf<String, MutableList<Double>>()

        query.forEach { row ->
            val key = row[Answers.questionKey]
            val text = row[Answers.answerText].trim()

            // Превращаем строки и текстовые интервалы кнопок в точные числовые значения для математики
            val numericValue = when (text) {
                "9-10" -> 9.5
                "7-8" -> 7.5
                "4-6" -> 5.0
                "1-3" -> 2.0
                "1-2" -> 1.5
                "5" -> 5.0
                "4" -> 4.0
                "3" -> 3.0
                else -> text.toDoubleOrNull()
            }

            if (numericValue != null) {
                keyValues.getOrPut(key) { mutableListOf() }.add(numericValue)
            }
        }

        // Безопасный расчет среднего арифметического с округлением до 1 знака
        fun getAverageByKey(key: String): String {
            val list = keyValues[key] ?: return "Нет данных"
            if (list.isEmpty()) return "Нет данных"
            val avg = list.average()
            return (Math.round(avg * 10) / 10.0).toString()
        }

        val avgFeeling = getAverageByKey("Q1_FEELING")
        val avgCall = getAverageByKey("Q4_CALL_RATING")
        val avgSpeed = getAverageByKey("Q6_SPEED")
        val avgAutonomy = getAverageByKey("Q7_AUTONOMY")
        val avgEngage = getAverageByKey("Q9_ENGAGE")

        """
            📋 **Общая активность:**
            Всего запущено опросов: $total
            ✅ Успешно заполнено: $completed
            🚨 Алертов от OpenAI: $flagged
            
            📊 **Средние показатели команды:**
            🧠 Общие ощущения (0-10): $avgFeeling
            📞 Включенность на созвонах (0-5): $avgCall
            ⚡ Скорость закрытия задач (0-5): $avgSpeed
            🛡 Автономия/Самостоятельность (0-5): $avgAutonomy
            🔥 Вовлеченность в культуру (0-5): $avgEngage
        """.trimIndent()
    }
}