package com.statbot.model

import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.InlineKeyboardMarkup
import com.github.kotlintelegrambot.entities.keyboard.InlineKeyboardButton
import com.statbot.db.DbRepository
import com.statbot.ai.OpenAiService
import com.statbot.bot.BotDispatcher
import java.time.DayOfWeek
import java.time.LocalDate

object SurveyManager {

    private val STANDARD_RATING = listOf(
        listOf(InlineKeyboardButton.CallbackData("9-10", "ans_9-10"), InlineKeyboardButton.CallbackData("7-8", "ans_7-8")),
        listOf(InlineKeyboardButton.CallbackData("4-6", "ans_4-6"), InlineKeyboardButton.CallbackData("1-3", "ans_1-3"))
    )

    fun startSurvey(bot: Bot, tgId: Long, chatId: ChatId) {
        val reportId = DbRepository.getOrCreateReport(tgId)
        val userData = DbRepository.getUserData(tgId)

        val fullName = userData?.get(com.statbot.db.Users.fullName)
        val project = userData?.get(com.statbot.db.Users.project)
        if (fullName.isNullOrEmpty() || project.isNullOrEmpty()) {
            SessionManager.activeSurveys[tgId] = SurveyState(SurveyStep.REG_FULL_NAME, reportId)
            bot.sendMessage(chatId, "📌 На открытые вопросы отвечай удобным способом: текстом или голосовым сообщением. Если предложены варианты ответа — просто выбери подходящий.\n\nВведи свои ФИО:")
            return
        }

        val startDate = userData?.get(com.statbot.db.Users.startDate)
        if (startDate == null) {
            SessionManager.activeSurveys[tgId] = SurveyState(SurveyStep.INIT_GOALS_YEAR, reportId)
            bot.sendMessage(chatId, "1. Каких результатов ты хочешь достичь за этот год, чтобы продвинуться к своим долгосрочным целям?")
            return
        }

        val today = LocalDate.now()
        val dayOfWeek = today.dayOfWeek
        val cycleDay = DbRepository.getOrCreateReport(tgId, today)

        val state = SurveyState(reportId = reportId)
        SessionManager.activeSurveys[tgId] = state

        bot.sendMessage(chatId, "📌 На открытые вопросы отвечай удобным способом: текстом или голосовым сообщением. Если предложены варианты ответа — просто выбери подходящий.")

        when {
            dayOfWeek == DayOfWeek.MONDAY -> startMondaySurvey(bot, chatId, state, tgId)
            dayOfWeek == DayOfWeek.FRIDAY -> startFridaySurvey(bot, chatId, state)
            else -> startDailySurveyByCycle(bot, chatId, state, cycleDay)
        }
    }

    private fun startMondaySurvey(bot: Bot, chatId: ChatId, state: SurveyState, tgId: Long) {
        state.currentStep = SurveyStep.MON_TASKS_CHECK
        val tasks = DbRepository.getUserData(tgId)?.get(com.statbot.db.Users.currentWeeklyTasks) ?: "не указаны"
        bot.sendMessage(
            chatId,
            text = "В пятницу ты запланировал(а) такие задачи на текущую неделю:\n$tasks\n\nВсе ли актуально?",
            replyMarkup = InlineKeyboardMarkup.create(
                listOf(
                    listOf(InlineKeyboardButton.CallbackData("Да, все актуально", "ans_mon_yes")),
                    listOf(InlineKeyboardButton.CallbackData("Нет, есть изменения", "ans_mon_no"))
                )
            )
        )
    }

    private fun startFridaySurvey(bot: Bot, chatId: ChatId, state: SurveyState) {
        state.currentStep = SurveyStep.FRI_WEEK_SCORE
        bot.sendMessage(
            chatId,
            text = "1. Как прошла рабочая неделя?",
            replyMarkup = InlineKeyboardMarkup.create(STANDARD_RATING)
        )
    }

    private fun startDailySurveyByCycle(bot: Bot, chatId: ChatId, state: SurveyState, cycleDay: Int) {
        when (cycleDay) {
            2 -> {
                state.currentStep = SurveyStep.DAY2_ENERGY
                bot.sendMessage(chatId, "1. На сколько ты энергичен(а)?", replyMarkup = InlineKeyboardMarkup.create(STANDARD_RATING))
            }
            3 -> {
                state.currentStep = SurveyStep.DAY3_NEW_INFO
                bot.sendMessage(chatId, "Узнавал ли что-то новое по своей профессии за последнее время?", replyMarkup = InlineKeyboardMarkup.create(
                    listOf(
                        listOf(InlineKeyboardButton.CallbackData("Да, ежедневно", "ans_daily")),
                        listOf(InlineKeyboardButton.CallbackData("Да, периодически", "ans_sometimes")),
                        listOf(InlineKeyboardButton.CallbackData("Нет времени", "ans_no_time"))
                    )
                ))
            }
            4 -> {
                state.currentStep = SurveyStep.DAY4_ENGAGEMENT
                bot.sendMessage(chatId, "Оцени уровень вовлеченности в проекты DK FILMS:", replyMarkup = InlineKeyboardMarkup.create(STANDARD_RATING))
            }
            9 -> {
                state.currentStep = SurveyStep.DAY9_INITIATIVE
                bot.sendMessage(chatId, "1. Оцени свою инициативность:", replyMarkup = InlineKeyboardMarkup.create(STANDARD_RATING))
            }
            10 -> {
                state.currentStep = SurveyStep.DAY10_NOTES_QUALITY
                bot.sendMessage(chatId, "Насколько качественно фиксируешь задачи после созвона?", replyMarkup = InlineKeyboardMarkup.create(STANDARD_RATING))
            }
            11 -> {
                state.currentStep = SurveyStep.DAY11_CALL_ENGAGEMENT
                bot.sendMessage(chatId, "1. Оцени свою включенность на созвонах:", replyMarkup = InlineKeyboardMarkup.create(STANDARD_RATING))
            }
            16 -> {
                state.currentStep = SurveyStep.DAY16_EMOTIONAL
                bot.sendMessage(chatId, "1. Оцени свой эмоциональный настрой:", replyMarkup = InlineKeyboardMarkup.create(STANDARD_RATING))
            }
            17 -> {
                state.currentStep = SurveyStep.DAY17_NEW_INFO
                bot.sendMessage(chatId, "Узнавал ли что-то новое по своей профессии за последнее время?", replyMarkup = InlineKeyboardMarkup.create(
                    listOf(
                        listOf(InlineKeyboardButton.CallbackData("Да, ежедневно", "ans_daily")),
                        listOf(InlineKeyboardButton.CallbackData("Да, периодически", "ans_sometimes")),
                        listOf(InlineKeyboardButton.CallbackData("Нет времени", "ans_no_time"))
                    )
                ))
            }
            18 -> {
                state.currentStep = SurveyStep.DAY18_CALL_VALUE
                bot.sendMessage(chatId, "1. Как ты оцениваешь ценность созвонов для себя?", replyMarkup = InlineKeyboardMarkup.create(STANDARD_RATING))
            }
            23 -> {
                state.currentStep = SurveyStep.DAY23_NOTES_QUALITY
                bot.sendMessage(chatId, "1. Насколько качественно фиксируешь задачи после созвона?", replyMarkup = InlineKeyboardMarkup.create(STANDARD_RATING))
            }
            24 -> {
                state.currentStep = SurveyStep.DAY24_AUTONOMY
                bot.sendMessage(chatId, "Насколько самостоятельно ты справляешься с задачами?", replyMarkup = InlineKeyboardMarkup.create(STANDARD_RATING))
            }
            25 -> {
                state.currentStep = SurveyStep.DAY25_HAPPINESS
                bot.sendMessage(chatId, "1. На сколько ты счастлив(а) в компании?", replyMarkup = InlineKeyboardMarkup.create(STANDARD_RATING))
            }
            30 -> {
                state.currentStep = SurveyStep.DAY30_HOURS
                bot.sendMessage(chatId, "1. Сколько часов в среднем в день ты работал(а)?", replyMarkup = InlineKeyboardMarkup.create(
                    listOf(
                        listOf(InlineKeyboardButton.CallbackData("0-1", "ans_0-1"), InlineKeyboardButton.CallbackData("2-3", "ans_2-3")),
                        listOf(InlineKeyboardButton.CallbackData("4-6", "ans_4-6"), InlineKeyboardButton.CallbackData("7+", "ans_7+"))
                    )
                ))
            }
            else -> {
                bot.sendMessage(chatId, "Сегодня нет обязательных вопросов по графику. Хорошего рабочего дня!")
            }
        }
    }

    // ВОССТАНОВЛЕН ПАРАМЕТР photoUrl
    fun processAnswer(bot: Bot, tgId: Long, chatId: ChatId, textAnswer: String?, callbackData: String?, photoUrl: String? = null) {
        val state = SessionManager.activeSurveys[tgId] ?: return
        val valData = callbackData?.removePrefix("ans_")

        when (state.currentStep) {
            SurveyStep.REG_FULL_NAME -> {
                if (textAnswer != null) {
                    DbRepository.updateUserProfile(tgId, textAnswer, null)
                    state.currentStep = SurveyStep.REG_PROJECT
                    bot.sendMessage(chatId, "Введи название проекта, над которым ты работаешь:")
                }
            }
            SurveyStep.REG_PROJECT -> {
                if (textAnswer != null) {
                    DbRepository.updateUserProfile(tgId, null, textAnswer)
                    startSurvey(bot, tgId, chatId)
                }
            }
            SurveyStep.INIT_GOALS_YEAR -> {
                if (textAnswer != null) {
                    DbRepository.setUserGoals(tgId, textAnswer, null)
                    state.currentStep = SurveyStep.INIT_GOALS_3MONTHS
                    bot.sendMessage(chatId, "2. Каких конкретных результатов ты хочешь достичь за следующие 3 месяца?")
                }
            }
            SurveyStep.INIT_GOALS_3MONTHS -> {
                if (textAnswer != null) {
                    DbRepository.setUserGoals(tgId, null, textAnswer)
                    bot.sendMessage(chatId, "✅ Цели успешно зафиксированы! Отсчет программы начат.")
                    startSurvey(bot, tgId, chatId)
                }
            }

            SurveyStep.MON_TASKS_CHECK -> {
                if (valData == "mon_yes") {
                    save(state, "MON_TASKS", "Да, все актуально")
                    endSurvey(bot, tgId, chatId, state)
                } else if (valData == "mon_no") {
                    state.currentStep = SurveyStep.MON_TASKS_CHANGES
                    bot.sendMessage(chatId, "Опиши изменения в задачах:")
                }
            }
            SurveyStep.MON_TASKS_CHANGES -> {
                if (textAnswer != null) {
                    save(state, "MON_TASKS_CHANGES", textAnswer)
                    endSurvey(bot, tgId, chatId, state)
                }
            }

            SurveyStep.FRI_WEEK_SCORE -> {
                if (valData != null) {
                    save(state, "FRI_WEEK_SCORE", valData)
                    state.currentStep = SurveyStep.FRI_CALL_DAYS
                    bot.sendMessage(chatId, "2. Сколько дней ты был(а) на созвонах за эту неделю?", replyMarkup = InlineKeyboardMarkup.create(
                        listOf(listOf("1", "2", "3", "4", "5").map { InlineKeyboardButton.CallbackData(it, "ans_$it") })
                    ))
                }
            }
            SurveyStep.FRI_CALL_DAYS -> {
                if (valData != null) {
                    save(state, "FRI_CALL_DAYS", valData)
                    state.currentStep = SurveyStep.FRI_TASK_PCT
                    val tasks = DbRepository.getUserData(tgId)?.get(com.statbot.db.Users.currentWeeklyTasks) ?: "не указаны"
                    bot.sendMessage(chatId, "3. На эту неделю ты ставил(а) такие задачи:\n$tasks\n\nОтметь процент их выполнения:", replyMarkup = InlineKeyboardMarkup.create(
                        listOf(
                            listOf(InlineKeyboardButton.CallbackData("100%", "ans_100")),
                            listOf(InlineKeyboardButton.CallbackData("70-90%", "ans_70-90")),
                            listOf(InlineKeyboardButton.CallbackData("50%", "ans_50")),
                            listOf(InlineKeyboardButton.CallbackData("<30%", "ans_<30"))
                        )
                    ))
                }
            }
            SurveyStep.FRI_TASK_PCT -> {
                if (valData != null) {
                    save(state, "FRI_TASK_PCT", valData)
                    if (valData != "100") {
                        state.currentStep = SurveyStep.FRI_TASK_MISSED_REASON
                        bot.sendMessage(chatId, "Какие из запланированных задач не удалось выполнить и почему?")
                    } else {
                        askFriExtraTasks(bot, chatId, state)
                    }
                }
            }
            SurveyStep.FRI_TASK_MISSED_REASON -> {
                if (textAnswer != null) {
                    save(state, "FRI_MISSED_REASON", textAnswer)
                    askFriExtraTasks(bot, chatId, state)
                }
            }
            SurveyStep.FRI_EXTRA_TASKS_YESNO -> {
                if (valData == "extra_yes") {
                    state.currentStep = SurveyStep.FRI_EXTRA_TASKS_LIST
                    bot.sendMessage(chatId, "Какие именно дополнительные задачи были?")
                } else if (valData == "extra_no") {
                    save(state, "FRI_EXTRA_TASKS", "Нет")
                    askFriNextWeekTasks(bot, chatId, state)
                }
            }
            SurveyStep.FRI_EXTRA_TASKS_LIST -> {
                if (textAnswer != null) {
                    save(state, "FRI_EXTRA_TASKS", textAnswer)
                    askFriNextWeekTasks(bot, chatId, state)
                }
            }
            SurveyStep.FRI_NEXT_WEEK_TASKS -> {
                if (textAnswer != null) {
                    save(state, "FRI_NEXT_WEEK_TASKS", textAnswer)
                    DbRepository.setWeeklyTasks(tgId, textAnswer)
                    endSurvey(bot, tgId, chatId, state)
                }
            }

            SurveyStep.DAY2_ENERGY -> {
                if (valData != null) {
                    save(state, "DAY2_ENERGY", valData)
                    state.currentStep = SurveyStep.DAY2_SPEED
                    bot.sendMessage(chatId, "2. Оцени скорость выполнения задач:", replyMarkup = InlineKeyboardMarkup.create(STANDARD_RATING))
                }
            }
            SurveyStep.DAY2_SPEED -> {
                if (valData != null) {
                    save(state, "DAY2_SPEED", valData)
                    if (valData == "4-6" || valData == "1-3") {
                        state.currentStep = SurveyStep.DAY2_SPEED_WHY
                        bot.sendMessage(chatId, "Что помешало тебе работать на максимальной скорости?")
                    } else {
                        endSurvey(bot, tgId, chatId, state)
                    }
                }
            }
            SurveyStep.DAY2_SPEED_WHY -> {
                if (textAnswer != null) {
                    save(state, "DAY2_SPEED_WHY", textAnswer)
                    endSurvey(bot, tgId, chatId, state)
                }
            }
            SurveyStep.DAY4_ENGAGEMENT -> {
                if (valData != null) {
                    save(state, "DAY4_ENGAGEMENT", valData)
                    if (valData == "4-6" || valData == "1-3") {
                        state.currentStep = SurveyStep.DAY4_ENGAGEMENT_WHY
                        bot.sendMessage(chatId, "Что могло бы помочь повысить твою вовлеченность в проекты?")
                    } else {
                        endSurvey(bot, tgId, chatId, state)
                    }
                }
            }
            SurveyStep.DAY4_ENGAGEMENT_WHY -> {
                if (textAnswer != null) {
                    save(state, "DAY4_ENGAGEMENT_WHY", textAnswer)
                    endSurvey(bot, tgId, chatId, state)
                }
            }

            else -> {
                if (valData != null) save(state, state.currentStep.name, valData)
                if (textAnswer != null) save(state, state.currentStep.name, textAnswer)
                if (photoUrl != null) save(state, "${state.currentStep.name}_PHOTO", photoUrl)
                endSurvey(bot, tgId, chatId, state)
            }
        }
    }

    private fun askFriExtraTasks(bot: Bot, chatId: ChatId, state: SurveyState) {
        state.currentStep = SurveyStep.FRI_EXTRA_TASKS_YESNO
        bot.sendMessage(chatId, "4. Были ли у тебя дополнительные задачи помимо запланированных?", replyMarkup = InlineKeyboardMarkup.create(
            listOf(
                listOf(InlineKeyboardButton.CallbackData("+ Да, были", "ans_extra_yes")),
                listOf(InlineKeyboardButton.CallbackData("- Нет, не было", "ans_extra_no"))
            )
        ))
    }

    private fun askFriNextWeekTasks(bot: Bot, chatId: ChatId, state: SurveyState) {
        state.currentStep = SurveyStep.FRI_NEXT_WEEK_TASKS
        bot.sendMessage(chatId, "5. Опиши свои задачи на следующую неделю:")
    }

    private fun endSurvey(bot: Bot, tgId: Long, chatId: ChatId, state: SurveyState) {
        state.currentStep = SurveyStep.FINISHED
        SessionManager.activeSurveys.remove(tgId)
        bot.sendMessage(chatId, "✨ Спасибо! Твой опрос успешно пройден.")

        DbRepository.markReportCompleted(state.reportId)
        val userData = DbRepository.getUserData(tgId)
        val empName = userData?.get(com.statbot.db.Users.fullName) ?: "Сотрудник"
        val empProject = userData?.get(com.statbot.db.Users.project) ?: "Проект"

        Thread {
            try {
                val openai = OpenAiService(System.getenv("OPENAI_API_KEY") ?: "")
                val (isFlagged, summary) = openai.analyzeDailyReport(empName, empProject, state.reportData)
                if (isFlagged) {
                    DbRepository.saveAiAlert(state.reportId, summary)
                    BotDispatcher.notifyTeachers(bot, "🚨 **AI Анализ ТЗ:**\n👤 **Сотрудник:** $empName\n🎬 **Проект:** $empProject\n\n📝 **Вывод:** $summary")
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    private fun save(state: SurveyState, key: String, value: String) {
        DbRepository.saveAnswer(state.reportId, key, value)
        state.reportData[key] = value
    }
}