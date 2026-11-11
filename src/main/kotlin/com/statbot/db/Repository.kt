package com.statbot.db

import com.statbot.model.Profile
import com.statbot.model.ProfilePatch
import com.statbot.model.Role
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.sql.Connection
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.Executors

data class ReportRow(
    val id: Int,
    val userTgId: Long,
    val date: LocalDate,
    val cycleDay: Int,
    val completed: Boolean,
    val ignored: Boolean,
    val flagged: Boolean,
    val summary: String?,
)

data class AnswerRow(val reportId: Int, val key: String, val question: String?, val answer: String, val score: Double?)

/**
 * Доступ к SQLite. Все запросы выполняются на одном выделенном потоке —
 * так SQLite не ловит «database is locked» при параллельных апдейтах.
 */
class Repository(dbPath: String) {
    private val dispatcher = Executors.newSingleThreadExecutor { r -> Thread(r, "db").apply { isDaemon = true } }
        .asCoroutineDispatcher()
    private val db = Database.connect("jdbc:sqlite:$dbPath", driver = "org.sqlite.JDBC")

    init {
        TransactionManager.manager.defaultIsolationLevel = Connection.TRANSACTION_SERIALIZABLE
        transaction(db) {
            @Suppress("DEPRECATION")
            SchemaUtils.createMissingTablesAndColumns(Users, DailyReports, Answers, WeeklyPlans)
        }
    }

    private suspend fun <T> q(block: Transaction.() -> T): T = withContext(dispatcher) { transaction(db) { block() } }

    // ---------------- Пользователи ----------------

    private fun ResultRow.toProfile() = Profile(
        tgId = this[Users.tgId],
        role = runCatching { Role.valueOf(this[Users.role]) }.getOrDefault(Role.STUDENT),
        username = this[Users.username],
        fullName = this[Users.fullName],
        project = this[Users.project],
        goalsYear = this[Users.goalsYear],
        goalsYearInitial = this[Users.goalsYearInitial],
        goals3Months = this[Users.goals3Months],
        // Старые пользователи: onboarded_at не было, «датой старта» служил start_date
        onboardedAt = this[Users.onboardedAt] ?: this[Users.startDate],
        cycleStart = this[Users.cycleStart],
    )

    suspend fun profile(tgId: Long): Profile? = q {
        Users.select { Users.tgId eq tgId }.singleOrNull()?.toProfile()
    }

    /** Создаёт пользователя при первом обращении и обновляет username. */
    suspend fun ensureUser(tgId: Long, username: String?): Profile = q {
        val existing = Users.select { Users.tgId eq tgId }.singleOrNull()
        if (existing == null) {
            Users.insert {
                it[Users.tgId] = tgId
                it[Users.role] = Role.STUDENT.name
                it[Users.username] = username
            }
        } else if (!username.isNullOrBlank() && existing[Users.username] != username) {
            Users.update({ Users.tgId eq tgId }) { it[Users.username] = username }
        }
        Users.select { Users.tgId eq tgId }.single().toProfile()
    }

    suspend fun updateProfile(tgId: Long, patch: ProfilePatch) = q {
        Users.update({ Users.tgId eq tgId }) { row ->
            patch.fullName?.let { row[fullName] = it.take(100) }
            patch.project?.let { row[project] = it.take(200) }
            patch.goalsYear?.let { row[goalsYear] = it }
            patch.goalsYearInitial?.let { row[goalsYearInitial] = it }
            patch.goals3Months?.let { row[goals3Months] = it }
            patch.onboardedAt?.let { row[onboardedAt] = it }
            patch.cycleStart?.let { row[cycleStart] = it }
        }
    }

    suspend fun setRole(tgId: Long, role: Role) = q {
        Users.update({ Users.tgId eq tgId }) { it[Users.role] = role.name }
    }

    suspend fun teacherIds(): List<Long> = q {
        Users.select { Users.role eq Role.TEACHER.name }.map { it[Users.tgId] }
    }

    suspend fun students(): List<Profile> = q {
        Users.select { Users.role eq Role.STUDENT.name }.map { it.toProfile() }
    }

    // ---------------- Сессии ----------------

    suspend fun saveSession(tgId: Long, json: String?) = q {
        Users.update({ Users.tgId eq tgId }) { it[sessionJson] = json }
    }

    suspend fun loadSessions(): Map<Long, String> = q {
        Users.slice(Users.tgId, Users.sessionJson).selectAll()
            .mapNotNull { row -> row[Users.sessionJson]?.let { row[Users.tgId] to it } }
            .toMap()
    }

    // ---------------- Недельные планы ----------------

    suspend fun planFor(tgId: Long, weekStart: LocalDate): List<String>? = q {
        WeeklyPlans.select { (WeeklyPlans.userTgId eq tgId) and (WeeklyPlans.weekStart eq weekStart) }
            .orderBy(WeeklyPlans.id to SortOrder.DESC)
            .limit(1)
            .firstOrNull()
            ?.get(WeeklyPlans.tasks)
            ?.lines()?.filter { it.isNotBlank() }
    }

    suspend fun savePlan(tgId: Long, weekStart: LocalDate, tasks: List<String>) = q {
        WeeklyPlans.insert {
            it[userTgId] = tgId
            it[WeeklyPlans.weekStart] = weekStart
            it[WeeklyPlans.tasks] = tasks.joinToString("\n") { t -> t.replace('\n', ' ') }
            it[createdAt] = LocalDateTime.now()
        }
        Unit
    }

    // ---------------- Отчёты и ответы ----------------

    private fun ResultRow.toReport() = ReportRow(
        id = this[DailyReports.id],
        userTgId = this[DailyReports.userTgId],
        date = this[DailyReports.date],
        cycleDay = this[DailyReports.cycleDay],
        completed = this[DailyReports.isCompleted],
        ignored = this[DailyReports.isIgnored],
        flagged = this[DailyReports.aiFlagged],
        summary = this[DailyReports.aiSummary],
    )

    suspend fun reportFor(tgId: Long, date: LocalDate): ReportRow? = q {
        DailyReports.select { (DailyReports.userTgId eq tgId) and (DailyReports.date eq date) }
            .orderBy(DailyReports.id to SortOrder.DESC).limit(1).firstOrNull()?.toReport()
    }

    suspend fun createReport(tgId: Long, date: LocalDate, cycleDay: Int): Int = q {
        DailyReports.insert {
            it[userTgId] = tgId
            it[DailyReports.date] = date
            it[DailyReports.cycleDay] = cycleDay
        }[DailyReports.id]
    }

    suspend fun saveAnswer(reportId: Int, key: String, question: String, answer: String, score: Double?) = q {
        Answers.insert {
            it[Answers.reportId] = reportId
            it[questionKey] = key.take(50)
            it[questionText] = question
            it[answerText] = answer
            it[Answers.score] = score
        }
        Unit
    }

    suspend fun markCompleted(reportId: Int) = q {
        DailyReports.update({ DailyReports.id eq reportId }) { it[isCompleted] = true }
    }

    suspend fun markIgnored(reportId: Int) = q {
        DailyReports.update({ (DailyReports.id eq reportId) and (DailyReports.isCompleted eq false) }) { it[isIgnored] = true }
    }

    suspend fun saveAlert(reportId: Int, summary: String) = q {
        DailyReports.update({ DailyReports.id eq reportId }) {
            it[aiFlagged] = true
            it[aiSummary] = summary
        }
    }

    suspend fun reportsSince(from: LocalDate): List<ReportRow> = q {
        DailyReports.select { DailyReports.date greaterEq from }.map { it.toReport() }
    }

    suspend fun reportsOf(tgId: Long, limit: Int = 100): List<ReportRow> = q {
        DailyReports.select { DailyReports.userTgId eq tgId }
            .orderBy(DailyReports.id to SortOrder.DESC).limit(limit).map { it.toReport() }
    }

    suspend fun answersFor(reportIds: Collection<Int>): List<AnswerRow> = if (reportIds.isEmpty()) emptyList() else q {
        Answers.select { Answers.reportId inList reportIds }.map {
            AnswerRow(it[Answers.reportId], it[Answers.questionKey], it[Answers.questionText], it[Answers.answerText], it[Answers.score])
        }
    }

    suspend fun lastAlerts(limit: Int): List<Pair<ReportRow, Profile?>> = q {
        DailyReports.select { DailyReports.aiFlagged eq true }
            .orderBy(DailyReports.id to SortOrder.DESC).limit(limit)
            .map { it.toReport() }
            .map { r -> r to Users.select { Users.tgId eq r.userTgId }.singleOrNull()?.toProfile() }
    }
}
