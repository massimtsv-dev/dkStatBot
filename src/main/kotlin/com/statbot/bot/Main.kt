package com.statbot.bot

import com.github.kotlintelegrambot.bot
import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.dispatch
import com.github.kotlintelegrambot.dispatcher.command
import com.github.kotlintelegrambot.dispatcher.text
import com.github.kotlintelegrambot.dispatcher.callbackQuery
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.InlineKeyboardMarkup
import com.github.kotlintelegrambot.entities.keyboard.InlineKeyboardButton
import com.github.kotlintelegrambot.entities.KeyboardReplyMarkup
import com.github.kotlintelegrambot.entities.keyboard.KeyboardButton
import com.github.kotlintelegrambot.entities.TelegramFile
import com.github.kotlintelegrambot.entities.ParseMode
import com.github.kotlintelegrambot.entities.Message
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.javatime.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.knowm.xchart.BitmapEncoder
import org.knowm.xchart.CategoryChartBuilder
import java.io.File
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Properties
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// =========================================================================
//                       БАЗА ДАННЫХ И ТАБЛИЦЫ
// =========================================================================

object Users : Table() {
    val tgId = long("tg_id")
    val role = varchar("role", 20).default("STUDENT")
    override val primaryKey = PrimaryKey(tgId)
}

object Questions : Table() {
    val id = integer("id").autoIncrement()
    val text = text("text")
    val isActive = bool("is_active").default(true)
    val sortOrder = integer("sort_order").default(0)
    override val primaryKey = PrimaryKey(id)
}

object Answers : Table() {
    val id = integer("id").autoIncrement()
    val userTgId = long("user_tg_id")
    val questionId = integer("question_id").references(Questions.id)
    val score = integer("score")
    val createdAt = datetime("created_at").clientDefault { LocalDateTime.now() }
    override val primaryKey = PrimaryKey(id)
}

// =========================================================================
//                       УПРАВЛЕНИЕ СОСТОЯНИЕМ
// =========================================================================

data class SurveyState(val pendingQuestionIds: List<Int>)

object SessionManager {
    val activeSurveys = mutableMapOf<Long, SurveyState>()
}

// =========================================================================
//                       ТОЧКА ВХОДА (MAIN)
// =========================================================================

fun main() {
    Database.connect("jdbc:sqlite:statbot.db", driver = "org.sqlite.JDBC")
    transaction {
        SchemaUtils.create(Users, Questions, Answers)
        if (Questions.selectAll().count() == 0L) {
            Questions.insert { it[text] = "Оцени уровень энергии сегодня"; it[sortOrder] = 1 }
            Questions.insert { it[text] = "Оцени уровень мотивации"; it[sortOrder] = 2 }
            Questions.insert { it[text] = "Оцени уровень стресса (10 - нет стресса, 1 - сильный стресс)"; it[sortOrder] = 3 }
        }
    }

    val botToken = System.getenv("BOT_TOKEN") ?: Properties().apply {
        val propertiesFile = File("local.properties")
        if (propertiesFile.exists()) {
            propertiesFile.inputStream().use { load(it) }
        }
    }.getProperty("BOT_TOKEN") ?: error("Критическая ошибка: Токен не найден!")

    val statBot = bot {
        token = botToken
        dispatch {
            command("start") {
                val tgId = message.from?.id ?: return@command
                val chatIdWrapper = ChatId.fromId(message.chat.id)

                val userRole = transaction<String> {
                    val userRow = Users.select { Users.tgId eq tgId }.singleOrNull()
                    if (userRow == null) {
                        Users.insert { it[Users.tgId] = tgId; it[Users.role] = "STUDENT" }
                        "STUDENT"
                    } else {
                        userRow[Users.role]
                    }
                }

                val menuKeyboard = if (userRole == "TEACHER") getTeacherKeyboard() else getStudentKeyboard()
                bot.sendMessage(chatId = chatIdWrapper, text = "Привет! Я бот для сбора статистики.", replyMarkup = menuKeyboard)
            }

            command("iamteacher") {
                val tgId = message.from?.id ?: return@command
                val chatIdWrapper = ChatId.fromId(message.chat.id)
                val args = message.text?.split(" ") ?: return@command

                if (args.size < 2 || args[1] != "SuperStat2026") {
                    bot.sendMessage(chatIdWrapper, text = "❌ Неверный пароль.")
                    return@command
                }

                transaction {
                    val userExists = Users.select { Users.tgId eq tgId }.count() > 0
                    if (userExists) {
                        Users.update({ Users.tgId eq tgId }) { it[role] = "TEACHER" }
                    } else {
                        Users.insert { it[Users.tgId] = tgId; it[role] = "TEACHER" }
                    }
                }
                bot.sendMessage(chatIdWrapper, text = "✅ Пароль верный!", replyMarkup = getTeacherKeyboard())
            }

            command("test") { handleStartSurvey(bot, message) }
            text("📝 Пройти опрос") { handleStartSurvey(bot, message) }

            command("stats") { handleShowStats(bot, message) }
            text("📊 Статистика за сегодня") { handleShowStats(bot, message) }

            command("weekly") { handleShowWeeklyChart(bot, message) }
            text("📈 График за неделю") { handleShowWeeklyChart(bot, message) }

            callbackQuery {
                val tgId = callbackQuery.from.id
                val chatIdWrapper = ChatId.fromId(callbackQuery.message?.chat?.id ?: return@callbackQuery)
                val messageId = callbackQuery.message?.messageId ?: return@callbackQuery
                val data = callbackQuery.data

                if (data.startsWith("ans_")) {
                    val parts = data.split("_")
                    val qId = parts[1].toInt()
                    val score = parts[2].toInt()

                    transaction {
                        Answers.insert { it[userTgId] = tgId; it[questionId] = qId; it[Answers.score] = score }
                    }

                    val state = SessionManager.activeSurveys[tgId]
                    if (state != null) {
                        val remaining = state.pendingQuestionIds.drop(1)
                        if (remaining.isNotEmpty()) {
                            SessionManager.activeSurveys[tgId] = SurveyState(remaining)
                            val nextQId = remaining.first()
                            val qText = transaction<String> { Questions.select { Questions.id eq nextQId }.single()[Questions.text] }
                            bot.editMessageText(chatId = chatIdWrapper, messageId = messageId, text = "Вопрос: $qText", replyMarkup = createRatingKeyboard(nextQId))
                        } else {
                            SessionManager.activeSurveys.remove(tgId)
                            bot.editMessageText(chatId = chatIdWrapper, messageId = messageId, text = "✨ Спасибо!")

                            val todayAvg = transaction<Double> {
                                val avgRow = Answers.slice(Answers.score.avg()).select { Answers.createdAt greaterEq LocalDateTime.now().toLocalDate().atStartOfDay() }.singleOrNull()
                                avgRow?.get(Answers.score.avg())?.toDouble() ?: Double.NaN
                            }
                            if (!todayAvg.isNaN() && todayAvg < 5.0) {
                                sendChartToAllTeachers(bot, "🚨 ВНИМАНИЕ! Средний балл сегодня: ${String.format("%.1f", todayAvg)}/10")
                            }
                        }
                    }
                }
            }
        }
    }

    val scheduler = Executors.newSingleThreadScheduledExecutor()
    val now = ZonedDateTime.now()
    var nextRun = now.withHour(20).withMinute(0).withSecond(0).withNano(0)
    if (now.isAfter(nextRun)) nextRun = nextRun.plusDays(1)
    val initialDelay = Duration.between(now, nextRun).toSeconds()

    scheduler.scheduleAtFixedRate({
        sendChartToAllTeachers(statBot, "📊 Ежедневный отчет за неделю.")
    }, initialDelay, TimeUnit.DAYS.toSeconds(1), TimeUnit.SECONDS)

    val me = statBot.getMe().get()
    println("====================================================")
    println("БОТ ЗАПУЩЕН КАК: ${me.firstName} (@${me.username})")
    println("ID БОТА: ${me.id}")
    println("====================================================")

    statBot.startPolling()

    statBot.startPolling()
}

// =========================================================================
//                       ФУНКЦИИ-ОБРАБОТЧИКИ
// =========================================================================

fun handleStartSurvey(bot: Bot, message: Message) {
    val tgId = message.from?.id ?: return
    val chatIdWrapper = ChatId.fromId(message.chat.id)

    transaction {
        val qIds = Questions.select { Questions.isActive eq true }.orderBy(Questions.sortOrder to SortOrder.ASC).map { it[Questions.id] }
        if (qIds.isEmpty()) return@transaction
        SessionManager.activeSurveys[tgId] = SurveyState(qIds)
        val qText = Questions.select { Questions.id eq qIds.first() }.single()[Questions.text]
        bot.sendMessage(chatIdWrapper, "Вопрос 1: $qText", replyMarkup = createRatingKeyboard(qIds.first()))
    }
}

fun handleShowStats(bot: Bot, message: Message) {
    val chatId = message.chat.id
    println("DEBUG: [Stats] Начало обработки...")

    try {
        val report = transaction {
            val startOfToday = LocalDateTime.now().toLocalDate().atStartOfDay()

            // Считаем вообще сколько записей есть
            val totalAnswers = Answers.selectAll().count()
            println("DEBUG: [Stats] Всего записей в БД: $totalAnswers")

            // Считаем записи за сегодня
            val todayAnswers = Answers.select { Answers.createdAt greaterEq startOfToday }.count()
            println("DEBUG: [Stats] Записей за сегодня: $todayAnswers")

            val stats = (Answers innerJoin Questions)
                .slice(Questions.text, Answers.score.avg())
                .select { Answers.createdAt greaterEq startOfToday }
                .groupBy(Questions.text)
                .map { it[Questions.text] to (it[Answers.score.avg()]?.toDouble() ?: 0.0) }

            if (stats.isEmpty()) {
                "Статистика за сегодня: пока нет данных (всего записей в базе: $totalAnswers)."
            } else {
                val sb = StringBuilder("📊 Статистика за сегодня:\n")
                stats.forEach { (q, avg) ->
                    sb.append("• $q: ${String.format("%.1f", avg)} / 10\n")
                }
                sb.toString()
            }
        }

        println("DEBUG: [Stats] Отправляю отчет: $report")
        bot.sendMessage(ChatId.fromId(chatId), report)

    } catch (e: Exception) {
        println("DEBUG: [Stats] ОШИБКА В ТРАНЗАКЦИИ: ${e.message}")
        e.printStackTrace()
        bot.sendMessage(ChatId.fromId(chatId), "Ошибка при чтении статистики: ${e.message}")
    }
}

fun handleShowWeeklyChart(bot: Bot, message: Message) {
    val tgId = message.from?.id ?: return
    val chatIdWrapper = ChatId.fromId(message.chat.id)
    bot.sendMessage(chatIdWrapper, "📊 Генерирую...")
    val file = generateWeeklyChart()
    bot.sendPhoto(chatIdWrapper, TelegramFile.ByFile(file), caption = "График")
    file.delete()
}

// =========================================================================
//                       ВСПОМОГАТЕЛЬНЫЕ ФУНКЦИИ
// =========================================================================

fun getStudentKeyboard() = KeyboardReplyMarkup(listOf(listOf(KeyboardButton("📝 Пройти опрос"))), resizeKeyboard = true)
fun getTeacherKeyboard() = KeyboardReplyMarkup(listOf(listOf(KeyboardButton("📊 Статистика за сегодня")), listOf(KeyboardButton("📈 График за неделю"))), resizeKeyboard = true)
fun createRatingKeyboard(qId: Int) = InlineKeyboardMarkup.create((1..10).map { InlineKeyboardButton.CallbackData(it.toString(), "ans_${qId}_$it") }.chunked(5))

fun generateWeeklyChart(): File {
    val start = LocalDate.now().minusDays(6).atStartOfDay()
    val dbData = transaction { Answers.select { Answers.createdAt greaterEq start }.map { it[Answers.createdAt].toLocalDate() to it[Answers.score] } }
    val grouped = dbData.groupBy { it.first }
    val x = mutableListOf<String>(); val y = mutableListOf<Double>()
    for (i in 6 downTo 0) {
        val date = LocalDate.now().minusDays(i.toLong())
        x.add(date.format(DateTimeFormatter.ofPattern("dd.MM")))
        val scores = grouped[date]?.map { it.second } ?: emptyList()
        y.add(if (scores.isNotEmpty()) scores.average() else 0.0)
    }
    val chart = CategoryChartBuilder().width(800).height(600).title("Статистика").build()
    chart.styler.yAxisMin = 0.0; chart.styler.yAxisMax = 10.0
    chart.addSeries("Балл", x, y)
    val f = File.createTempFile("chart", ".png")
    BitmapEncoder.saveBitmap(chart, f.absolutePath, BitmapEncoder.BitmapFormat.PNG)
    return f
}

fun sendChartToAllTeachers(bot: Bot, caption: String) {
    val ids: List<Long> = transaction { Users.select { Users.role eq "TEACHER" }.map { it[Users.tgId] } }
    if (ids.isEmpty()) return
    val f = generateWeeklyChart()
    ids.forEach { bot.sendPhoto(ChatId.fromId(it), TelegramFile.ByFile(f), caption = caption) }
    f.delete()
}