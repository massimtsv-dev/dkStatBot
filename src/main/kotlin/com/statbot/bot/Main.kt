package com.statbot.bot

import com.github.kotlintelegrambot.bot
import com.github.kotlintelegrambot.dispatch
import com.github.kotlintelegrambot.dispatcher.callbackQuery
import com.github.kotlintelegrambot.dispatcher.command
import com.github.kotlintelegrambot.dispatcher.photos
import com.github.kotlintelegrambot.dispatcher.text
import com.github.kotlintelegrambot.dispatcher.voice
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.InlineKeyboardMarkup
import com.github.kotlintelegrambot.entities.keyboard.InlineKeyboardButton
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File
import java.time.LocalDate
import java.util.Properties

import com.statbot.db.DbRepository
import com.statbot.db.Users
import com.statbot.model.SurveyManager
import com.statbot.notifications.SchedulerService

object BotDispatcher {
    var teacherIds = listOf<Long>()

    fun notifyTeachers(bot: com.github.kotlintelegrambot.Bot, message: String) {
        teacherIds.forEach { bot.sendMessage(ChatId.fromId(it), message) }
    }
}

fun extractTelegramProperty(obj: Any?, targetProp: String): String? {
    if (obj == null) return null
    val queue = ArrayDeque<Any>()
    queue.add(obj)
    val visited = mutableSetOf<Int>()
    while (queue.isNotEmpty()) {
        val current = queue.removeFirst()
        val id = System.identityHashCode(current)
        if (id in visited) continue
        visited.add(id)
        val cls = current.javaClass
        if (cls.name.startsWith("java.lang.") || cls.name.startsWith("android.")) continue
        try {
            for (method in cls.methods) {
                if (method.parameterCount == 0) {
                    val name = method.name.lowercase()
                    if (targetProp == "path" && (name == "getfilepath" || name == "filepath" || name == "getpath" || name == "path")) {
                        val res = method.invoke(current) as? String
                        if (!res.isNullOrEmpty()) return res
                    }
                    if (targetProp == "username" && (name == "getusername" || name == "username")) {
                        val res = method.invoke(current) as? String
                        if (!res.isNullOrEmpty()) return res
                    }
                }
            }
            for (method in cls.methods) {
                if (method.parameterCount == 0 && method.name != "getClass" && method.name != "hashCode" && method.name != "toString") {
                    val name = method.name.lowercase()
                    if (name == "body" || name == "getvalue" || name == "getfirst" || name == "getsecond" || name == "component1" || name == "component2" || name == "getok") {
                        val inner = method.invoke(current)
                        if (inner != null) queue.add(inner)
                    }
                }
            }
        } catch (e: Exception) {}
    }
    return null
}

// Функция отрисовки главного меню преподавателя
fun sendTeacherMenu(bot: com.github.kotlintelegrambot.Bot, chatId: ChatId) {
    val markup = InlineKeyboardMarkup.create(
        listOf(
            listOf(InlineKeyboardButton.CallbackData("📊 Дневная статистика", "t_stats_day")),
            listOf(InlineKeyboardButton.CallbackData("📅 Недельная статистика", "t_stats_week")),
            listOf(InlineKeyboardButton.CallbackData("🗓 Месячная статистика", "t_stats_month")),
            listOf(InlineKeyboardButton.CallbackData("👥 Статистика по ученикам", "t_stats_students")),
            listOf(InlineKeyboardButton.CallbackData("🚨 Последние 5 AI алертов", "t_stats_ai"))
        )
    )
    bot.sendMessage(chatId, "🎛 **Панель управления преподавателя**\nВыберите интересующий формат аналитики:", replyMarkup = markup)
}

fun main() {
    DbRepository.initDb()

    val botToken = System.getenv("BOT_TOKEN") ?: Properties().apply {
        val file = File("local.properties")
        if (file.exists()) load(file.inputStream())
    }.getProperty("BOT_TOKEN") ?: error("Токен бота не найден в local.properties!")

    transaction {
        BotDispatcher.teacherIds = Users.select { Users.role eq "TEACHER" }.map { it[Users.tgId] }
    }

    val statBot = bot {
        token = botToken

        dispatch {
            command("start") {
                val tgId = message.from?.id ?: return@command
                val username = message.from?.username
                val chatId = ChatId.fromId(message.chat.id)

                val userRole = transaction {
                    val userRow = Users.select { Users.tgId eq tgId }.singleOrNull()
                    if (userRow == null) {
                        Users.insert {
                            it[Users.tgId] = tgId
                            it[Users.role] = "STUDENT"
                            it[Users.username] = username
                        }
                        "STUDENT"
                    } else {
                        if (!username.isNullOrBlank()) {
                            Users.update({ Users.tgId eq tgId }) { it[Users.username] = username }
                        }
                        userRow[Users.role]
                    }
                }

                if (userRole == "TEACHER") {
                    sendTeacherMenu(bot, chatId)
                } else {
                    bot.sendMessage(chatId, "Привет! Я бот для сбора статистики. Чтобы запустить опрос вручную, введи команду /survey.")
                }
            }

            command("teacher") {
                val tgId = message.from?.id ?: return@command
                val chatId = ChatId.fromId(message.chat.id)
                val userRole = transaction { Users.select { Users.tgId eq tgId }.singleOrNull()?.get(Users.role) }

                if (userRole == "TEACHER") {
                    sendTeacherMenu(bot, chatId)
                } else {
                    bot.sendMessage(chatId, "❌ У вас нет прав доступа к панели управления.")
                }
            }

            command("iamteacher") {
                val tgId = message.from?.id ?: return@command
                val chatId = ChatId.fromId(message.chat.id)
                val password = args.firstOrNull()

                if (password == "SuperStat2026") {
                    transaction {
                        Users.update({ Users.tgId eq tgId }) { it[role] = "TEACHER" }
                        BotDispatcher.teacherIds = Users.select { Users.role eq "TEACHER" }.map { it[Users.tgId] }
                    }
                    bot.sendMessage(chatId, "✅ Роль ПРЕПОДАВАТЕЛЯ успешно получена! Наберите /teacher для входа в меню.")
                } else {
                    bot.sendMessage(chatId, "❌ Неверный ключ авторизации.")
                }
            }

            command("survey") {
                val tgId = message.from?.id ?: return@command
                SurveyManager.startSurvey(bot, tgId, ChatId.fromId(message.chat.id))
            }

            text {
                val tgId = message.from?.id ?: return@text
                val chatId = ChatId.fromId(message.chat.id)
                if (message.text?.startsWith("/") == true) return@text

                SurveyManager.processAnswer(bot, tgId, chatId, textAnswer = message.text, callbackData = null)
            }

            callbackQuery {
                val tgId = callbackQuery.from.id
                val chatId = ChatId.fromId(callbackQuery.message?.chat?.id ?: return@callbackQuery)
                val data = callbackQuery.data

                if (data.startsWith("ans_")) {
                    SurveyManager.processAnswer(bot, tgId, chatId, textAnswer = null, callbackData = data)
                } else if (data.startsWith("focus_")) {
                    bot.sendMessage(chatId, "Записано! Твой статус активности сохранен.")
                }
                // --- ОБРАБОТКА НАЖАТИЙ В МЕНЮ ПРЕПОДАВАТЕЛЯ ---
                else if (data.startsWith("t_stats_")) {
                    val subType = data.removePrefix("t_stats_")
                    val today = LocalDate.now()

                    when (subType) {
                        "day" -> {
                            val res = DbRepository.getPeriodStats(today)
                            bot.sendMessage(chatId, "📊 **Статистика за сегодня ($today):**\n\n$res")
                        }
                        "week" -> {
                            val res = DbRepository.getPeriodStats(today.minusDays(7))
                            bot.sendMessage(chatId, "📅 **Статистика за последние 7 дней:**\n\n$res")
                        }
                        "month" -> {
                            val res = DbRepository.getPeriodStats(today.minusMonths(1))
                            bot.sendMessage(chatId, "🗓 **Статистика за последние 30 дней:**\n\n$res")
                        }
                        "ai" -> {
                            val alerts = DbRepository.getLast5AiAlerts()
                            if (alerts.isEmpty()) {
                                bot.sendMessage(chatId, "✅ Критических отклонений от ИИ за последнее время не зафиксировано.")
                            } else {
                                val text = alerts.joinToString("\n\n-----------------------------------\n\n")
                                bot.sendMessage(chatId, "🚨 **Последние 5 алертов от OpenAI:**\n\n$text")
                            }
                        }
                        "students" -> {
                            val students = DbRepository.getAllStudents()
                            if (students.isEmpty()) {
                                bot.sendMessage(chatId, "👥 В базе данных пока нет зарегистрированных учеников.")
                            } else {
                                // Формируем выпадающий список (кнопки) учеников
                                val buttons = students.map { (id, name) ->
                                    listOf(InlineKeyboardButton.CallbackData(name, "t_select_student_$id"))
                                }
                                bot.sendMessage(chatId, "👥 Выберите ученика из списка:", replyMarkup = InlineKeyboardMarkup.create(buttons))
                            }
                        }
                    }
                }
                // Вывод персональной статистики конкретного ученика
                else if (data.startsWith("t_select_student_")) {
                    val studentId = data.removePrefix("t_select_student_").toLongOrNull()
                    if (studentId != null) {
                        val stats = DbRepository.getStudentStats(studentId)
                        bot.sendMessage(chatId, stats)
                    }
                }
            }

            photos {
                val tgId = message.from?.id ?: return@photos
                val chatId = ChatId.fromId(message.chat.id)
                val photo = message.photo?.lastOrNull() ?: return@photos

                val fileResult = bot.getFile(photo.fileId)
                val filePath = extractTelegramProperty(fileResult, "path")

                if (filePath != null) {
                    val fileUrl = "https://api.telegram.org/file/bot$botToken/$filePath"
                    SurveyManager.processAnswer(bot, tgId, chatId, textAnswer = null, callbackData = null, photoUrl = fileUrl)
                } else {
                    bot.sendMessage(chatId, "❌ Не удалось получить файл скриншота.")
                }
            }

            voice {
                val tgId = message.from?.id ?: return@voice
                val chatId = ChatId.fromId(message.chat.id)
                val voiceObj = message.voice ?: return@voice

                bot.sendMessage(chatId, "🎤 Распознаю голосовое сообщение...")

                val fileResult = bot.getFile(voiceObj.fileId)
                val filePath = extractTelegramProperty(fileResult, "path")

                if (filePath != null) {
                    val botToken = System.getenv("BOT_TOKEN") ?: Properties().apply {
                        val file = java.io.File("local.properties")
                        if (file.exists()) load(file.inputStream())
                    }.getProperty("BOT_TOKEN") ?: ""

                    val fileUrl = "https://api.telegram.org/file/bot$botToken/$filePath"

                    Thread {
                        val apiKey = System.getenv("OPENAI_API_KEY") ?: Properties().apply {
                            val file = java.io.File("local.properties")
                            if (file.exists()) load(file.inputStream())
                        }.getProperty("OPENAI_API_KEY") ?: ""

                        val openai = com.statbot.ai.OpenAiService(apiKey)
                        val recognizedText = openai.transcribeVoice(fileUrl)

                        bot.sendMessage(chatId, "🗣 **Расшифровка:** \"$recognizedText\"")
                        SurveyManager.processAnswer(bot, tgId, chatId, textAnswer = recognizedText, callbackData = null)
                    }.start()
                } else {
                    bot.sendMessage(chatId, "❌ Не удалось прочитать голосовое сообщение.")
                }
            }

        }
    }

    SchedulerService.start(statBot)

    val meResult = statBot.getMe()
    val username = extractTelegramProperty(meResult, "username")
    println("====================================================")
    if (username != null) {
        println("БОТ СБОРА СТАТИСТИКИ ЗАПУЩЕН: @$username")
    } else {
        println("БОТ СБОРА СТАТИСТИКИ ЗАПУЩЕН")
    }
    println("====================================================")

    statBot.startPolling()
}