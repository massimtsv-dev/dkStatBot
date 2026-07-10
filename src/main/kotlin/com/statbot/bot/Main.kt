package com.statbot.bot

import com.github.kotlintelegrambot.bot
import com.github.kotlintelegrambot.dispatch
import com.github.kotlintelegrambot.dispatcher.callbackQuery
import com.github.kotlintelegrambot.dispatcher.command
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.InlineKeyboardMarkup
import com.github.kotlintelegrambot.entities.keyboard.InlineKeyboardButton
import com.statbot.db.Answers
import com.statbot.db.Questions
import com.statbot.db.Users
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDateTime

val BOT_TOKEN = System.getenv("BOT_TOKEN") ?: error("Переменная окружения BOT_TOKEN не задана!")
const val TEACHER_PASSWORD = "SuperStat2026"

fun main() {
    // 1. Инициализация локальной базы данных SQLite
    Database.connect("jdbc:sqlite:statbot.db", driver = "org.sqlite.JDBC")
    transaction {
        SchemaUtils.create(Users, Questions, Answers)

        // Если таблица вопросов пустая, заполняем её стартовыми вопросами
        if (Questions.selectAll().count() == 0L) {
            // Явное указание колонок для исключения ошибок типа Unresolved reference 'text'
            Questions.insert { it[Questions.text] = "Как прошел созвон?"; it[Questions.sortOrder] = 1 }
            Questions.insert { it[Questions.text] = "Насколько понятна задача?"; it[Questions.sortOrder] = 2 }
            Questions.insert { it[Questions.text] = "Успеваешь по дедлайнам?"; it[Questions.sortOrder] = 3 }
        }
    }

    // 2. Настройка бота
    val statBot = bot {
        token = BOT_TOKEN
        dispatch {

            command("start") {
                val tgId = message.from?.id ?: return@command
                val chatIdWrapper = ChatId.fromId(message.chat.id)

                transaction {
                    val userExists = Users.select { Users.tgId eq tgId }.count() > 0
                    if (!userExists) {
                        Users.insert {
                            it[Users.tgId] = tgId
                            it[Users.role] = "STUDENT"
                        }
                    }
                }

                bot.sendMessage(
                    chatId = chatIdWrapper,
                    text = "Привет! Я бот для сбора статистики. Опросы будут приходить по расписанию."
                )
            }

            // Обработка секретной команды для преподавателя
            command("iamteacher") {
                val args = message.text?.split(" ") ?: return@command
                val tgId = message.from?.id ?: return@command
                val chatIdWrapper = ChatId.fromId(message.chat.id)

                if (args.size == 2 && args[1] == TEACHER_PASSWORD) {
                    // Обновляем роль пользователя до TEACHER
                    transaction {
                        Users.update({ Users.tgId eq tgId }) {
                            it[Users.role] = "TEACHER"
                        }
                    }

                    // Используем безопасный ChatId.fromId()
                    bot.deleteMessage(chatIdWrapper, message.messageId)
                    bot.sendMessage(chatIdWrapper, text = "✅ Права преподавателя получены. Вам доступна статистика.")
                } else {
                    bot.sendMessage(chatIdWrapper, text = "❌ Неверный пароль.")
                }
            }

            // Тестовая команда для ручного запуска опроса
            command("test") {
                val tgId = message.from?.id ?: return@command
                val chatIdWrapper = ChatId.fromId(message.chat.id)

                transaction {
                    // 1. Берем из базы ID всех активных вопросов
                    val questionIds = Questions.select { Questions.isActive eq true }
                        .orderBy(Questions.sortOrder to SortOrder.ASC)
                        .map { it[Questions.id].value }

                    if (questionIds.isEmpty()) {
                        bot.sendMessage(chatIdWrapper, text = "В базе данных нет активных вопросов.")
                        return@transaction
                    }

                    // 2. Создаем сессию опроса в оперативной памяти
                    com.statbot.model.SessionManager.activeSurveys[tgId] = com.statbot.model.SurveyState(questionIds)

                    // 3. Берем самый первый вопрос и его текст
                    val firstQuestionId = questionIds.first()
                    val questionText = Questions.select { Questions.id eq firstQuestionId }
                        .single()[Questions.text]

                    // 4. Отправляем первый вопрос с кнопками 1-10
                    bot.sendMessage(
                        chatId = chatIdWrapper,
                        text = "Вопрос 1: $questionText",
                        replyMarkup = createRatingKeyboard(firstQuestionId)
                    )
                }
            }

            // Перехват нажатий на Inline-кнопки (оценки от 1 до 10)
// Перехват нажатий на Inline-кнопки (оценки от 1 до 10)
            callbackQuery {
                val data = callbackQuery.data
                val messageObj = callbackQuery.message ?: return@callbackQuery
                val chatIdWrapper = ChatId.fromId(messageObj.chat.id)
                val messageId = messageObj.messageId
                val tgId = callbackQuery.from.id

                if (data.startsWith("ans_")) {
                    val parts = data.split("_")
                    val questionId = parts[1].toInt()
                    val score = parts[2].toInt()

                    // 1. Сохраняем текущий ответ в базу данных
                    transaction {
                        val userRow = Users.select { Users.tgId eq tgId }.singleOrNull()
                        val internalUserId = userRow?.get(Users.id)

                        if (internalUserId != null) {
                            Answers.insert {
                                it[Answers.userId] = internalUserId
                                it[Answers.questionId] = questionId
                                it[Answers.score] = score
                                it[Answers.createdAt] = LocalDateTime.now()
                            }
                        }
                    }

                    // 2. Проверяем, есть ли следующий вопрос в сессии пользователя
                    val session = com.statbot.model.SessionManager.activeSurveys[tgId]

                    if (session != null) {
                        session.currentIndex++ // Переходим к следующему индексу

                        if (session.currentIndex < session.questionIds.size) {
                            // Если вопросы еще есть — берем следующий
                            val nextQuestionId = session.questionIds[session.currentIndex]

                            val nextQuestionText = transaction {
                                Questions.select { Questions.id eq nextQuestionId }.single()[Questions.text]
                            }

                            // Редактируем ТЕКУЩЕЕ сообщение: меняем текст и обновляем ID вопроса в кнопках
                            bot.editMessageText(
                                chatId = chatIdWrapper,
                                messageId = messageId,
                                text = "Вопрос ${session.currentIndex + 1}: $nextQuestionText",
                                replyMarkup = createRatingKeyboard(nextQuestionId)
                            )
                        } else {
                            // Если это был последний вопрос — закрываем сессию
                            com.statbot.model.SessionManager.activeSurveys.remove(tgId)

                            bot.editMessageText(
                                chatId = chatIdWrapper,
                                messageId = messageId,
                                text = "✨ Спасибо! Все оценки приняты. Опрос успешно завершен."
                            )
                        }
                    }
                }
            }
        }
    }

    statBot.startPolling()
    println("Бот успешно запущен!")
}

// Вспомогательная функция для генерации кнопок от 1 до 10
fun createRatingKeyboard(questionId: Int): InlineKeyboardMarkup {
    val buttons = (1..10).map { score ->
        InlineKeyboardButton.CallbackData(
            text = score.toString(),
            callbackData = "ans_${questionId}_$score"
        )
    }.chunked(5) // Разбиваем по 5 кнопок в ряд

    return InlineKeyboardMarkup.create(buttons)
}