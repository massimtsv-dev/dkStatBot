package com.statbot.model

import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.InlineKeyboardMarkup
import com.github.kotlintelegrambot.entities.keyboard.InlineKeyboardButton
import com.statbot.db.DbRepository
import com.statbot.ai.OpenAiService
import com.statbot.bot.BotDispatcher

object SurveyManager {

    fun startSurvey(bot: Bot, tgId: Long, chatId: ChatId) {
        val reportId = DbRepository.getOrCreateReport(tgId)

        // Проверяем, заполнил ли человек профиль (ФИО и Проект)
        val (fullName, project) = DbRepository.getUserProfile(tgId)
        if (fullName.isNullOrEmpty() || project.isNullOrEmpty()) {
            SessionManager.activeSurveys[tgId] = SurveyState(SurveyStep.REG_FULL_NAME, reportId)
            bot.sendMessage(chatId, "📝 Перед началом опроса давай заполним твой профиль.\n\nВведи свои ФИО (например, Иванов Иван Иванович):")
            return
        }

        SessionManager.activeSurveys[tgId] = SurveyState(SurveyStep.Q1_FEELING, reportId)
        sendQ1(bot, chatId)
    }

    private fun sendQ1(bot: Bot, chatId: ChatId) {
        bot.sendMessage(
            chatId = chatId,
            text = "1. Как прошел твой рабочий день? Оцени общие ощущения:",
            replyMarkup = InlineKeyboardMarkup.create(
                listOf(
                    listOf(InlineKeyboardButton.CallbackData("Все супер, продуктивно (9-10)", "ans_Q1_9-10")),
                    listOf(InlineKeyboardButton.CallbackData("Нормально, обычный день (7-8)", "ans_Q1_7-8")),
                    listOf(InlineKeyboardButton.CallbackData("Тяжело, устал(а) (4-6)", "ans_Q1_4-6")),
                    listOf(InlineKeyboardButton.CallbackData("Полный завал/выгораю (1-3)", "ans_Q1_1-3"))
                )
            )
        )
    }

    fun processAnswer(bot: Bot, tgId: Long, chatId: ChatId, textAnswer: String?, callbackData: String?, photoUrl: String? = null) {
        val state = SessionManager.activeSurveys[tgId] ?: return

        when (state.currentStep) {
            SurveyStep.REG_FULL_NAME -> {
                if (textAnswer != null) {
                    DbRepository.updateUserProfile(tgId, textAnswer, null)
                    state.currentStep = SurveyStep.REG_PROJECT
                    bot.sendMessage(chatId, "🚀 Теперь введи название проекта, над которым ты сейчас работаешь:")
                }
            }
            SurveyStep.REG_PROJECT -> {
                if (textAnswer != null) {
                    DbRepository.updateUserProfile(tgId, null, textAnswer)
                    state.currentStep = SurveyStep.Q1_FEELING
                    sendQ1(bot, chatId)
                }
            }
            SurveyStep.Q1_FEELING -> {
                if (callbackData != null) {
                    val rating = callbackData.removePrefix("ans_Q1_")
                    save(state, "Q1_FEELING", rating)

                    // Если оценка ниже 7-8 (то есть 4-6 или 1-3), уводим на вопрос о проблеме
                    if (rating == "4-6" || rating == "1-3") {
                        state.currentStep = SurveyStep.Q1_PROBLEM
                        bot.sendMessage(chatId, "⚠️ Что именно случилось? Опиши проблему:")
                    } else {
                        state.currentStep = SurveyStep.Q2_TASKS
                        bot.sendMessage(chatId, "2. Распиши подробно о прошедшем дне. Какие конкретно выполнял(а) задачи?")
                    }
                }
            }
            SurveyStep.Q1_PROBLEM -> {
                if (textAnswer != null) {
                    save(state, "Q1_PROBLEM", textAnswer)
                    state.currentStep = SurveyStep.Q2_TASKS
                    bot.sendMessage(chatId, "2. Распиши подробно о прошедшем дне. Какие конкретно выполнял(а) задачи?")
                }
            }
            SurveyStep.Q2_TASKS -> {
                if (textAnswer != null) {
                    save(state, "Q2_TASKS", textAnswer)
                    state.currentStep = SurveyStep.Q3_EXTRA
                    bot.sendMessage(
                        chatId,
                        text = "3. Делал(а) ли что-то вне плана? Напиши, что конкретно:",
                        replyMarkup = InlineKeyboardMarkup.createSingleRowKeyboard(
                            InlineKeyboardButton.CallbackData("Исключительно все по плану, ничего лишнего", "ans_Q3_plan")
                        )
                    )
                }
            }
            SurveyStep.Q3_EXTRA -> {
                val ans = callbackData?.let { "Все по плану, ничего лишнего" } ?: textAnswer
                if (ans != null) {
                    save(state, "Q3_EXTRA", ans)
                    state.currentStep = SurveyStep.Q4_CALL_YESNO
                    bot.sendMessage(
                        chatId,
                        text = "4. Ты был(а) сегодня на созвоне?",
                        replyMarkup = InlineKeyboardMarkup.createSingleRowKeyboard(
                            InlineKeyboardButton.CallbackData("Да", "ans_Q4_yes"),
                            InlineKeyboardButton.CallbackData("Нет", "ans_Q4_no")
                        )
                    )
                }
            }
            SurveyStep.Q4_CALL_YESNO -> {
                if (callbackData != null) {
                    save(state, "Q4_CALL_YESNO", callbackData)
                    if (callbackData == "ans_Q4_yes") {
                        state.currentStep = SurveyStep.Q4_CALL_RATING
                        bot.sendMessage(
                            chatId,
                            text = "Оцени свою включенность на сегодняшнем созвоне:\n\n5/5 — Активен: предлагал готовые идеи\n4/5 — Включен: всё понимал, слушал внимательно\n3/5 — Медленен: устал, долго вникал в задачи\n1-2/5 — Пассивен: присутствовал только формально",
                            replyMarkup = InlineKeyboardMarkup.create(
                                listOf(
                                    listOf(InlineKeyboardButton.CallbackData("5/5", "ans_Q4R_5"), InlineKeyboardButton.CallbackData("4/5", "ans_Q4R_4")),
                                    listOf(InlineKeyboardButton.CallbackData("3/5", "ans_Q4R_3"), InlineKeyboardButton.CallbackData("1-2/5", "ans_Q4R_1-2"))
                                )
                            )
                        )
                    } else {
                        goToHubstaffBranch(bot, tgId, chatId, state)
                    }
                }
            }
            SurveyStep.Q4_CALL_RATING -> {
                if (callbackData != null) {
                    save(state, "Q4_CALL_RATING", callbackData.removePrefix("ans_Q4R_"))
                    state.currentStep = SurveyStep.Q4_CALL_NOTES
                    bot.sendMessage(
                        chatId,
                        text = "Насколько зафиксирована суть созвона?",
                        replyMarkup = InlineKeyboardMarkup.create(
                            listOf(
                                listOf(InlineKeyboardButton.CallbackData("[ Записан на диктофон + расписал подробно все правки ]", "ans_Q4N_dict")),
                                listOf(InlineKeyboardButton.CallbackData("[ Набросал быстрые заметки ]", "ans_Q4N_notes")),
                                listOf(InlineKeyboardButton.CallbackData("[ Всё осталось на словах, записи не велись ]", "ans_Q4N_none"))
                            )
                        )
                    )
                }
            }
            SurveyStep.Q4_CALL_NOTES -> {
                if (callbackData != null) {
                    save(state, "Q4_CALL_NOTES", callbackData.removePrefix("ans_Q4N_"))
                    state.currentStep = SurveyStep.Q4_CALL_THESIS
                    bot.sendMessage(chatId, "Напиши 2–3 главных тезиса/итога сегодняшнего созвона:")
                }
            }
            SurveyStep.Q4_CALL_THESIS -> {
                if (textAnswer != null) {
                    save(state, "Q4_CALL_THESIS", textAnswer)
                    goToHubstaffBranch(bot, tgId, chatId, state)
                }
            }
            SurveyStep.Q5_HUBSTAFF_YESNO -> {
                if (callbackData != null) {
                    if (callbackData == "ans_Q5_yes") {
                        DbRepository.setUserHubstaffStatus(tgId, true)
                        state.currentStep = SurveyStep.Q5_HUBSTAFF_PHOTO
                        bot.sendMessage(chatId, "Укажи общее отработанное время в Hubstaff. Пришли скриншот:")
                    } else {
                        DbRepository.setUserHubstaffStatus(tgId, false)
                        askSpeedQuestion(bot, chatId, state)
                    }
                }
            }
            SurveyStep.Q5_HUBSTAFF_PHOTO -> {
                if (photoUrl != null) {
                    save(state, "Q5_HUBSTAFF_SCREENSHOT", photoUrl)
                    bot.sendMessage(chatId, "⏳ ИИ анализирует ваш скриншот Hubstaff...")
                    Thread {
                        try {
                            val openai = OpenAiService(System.getenv("OPENAI_API_KEY") ?: "")
                            val hours = openai.extractHubstaffHours(photoUrl)
                            save(state, "Q5_HUBSTAFF_HOURS", hours)
                            bot.sendMessage(chatId, "✅ ИИ определил время: $hours")
                        } catch (e: Exception) {
                            save(state, "Q5_HUBSTAFF_HOURS", "Ошибка чтения ИИ")
                            bot.sendMessage(chatId, "⚠️ Не удалось считать автоматически, скриншот сохранен для проверки.")
                        }
                        askSpeedQuestion(bot, chatId, state)
                    }.start()
                }
            }
            SurveyStep.Q6_SPEED -> {
                if (callbackData != null) {
                    save(state, "Q6_SPEED", callbackData.removePrefix("ans_Q6_"))
                    if (callbackData.contains("3") || callbackData.contains("1-2")) {
                        state.currentStep = SurveyStep.Q6_SPEED_WHY
                        bot.sendMessage(
                            chatId,
                            text = "Что конкретно замедлило твою скорость сегодня?",
                            replyMarkup = InlineKeyboardMarkup.create(
                                listOf(
                                    listOf(InlineKeyboardButton.CallbackData("[ технические проблемы (завис софт, баг) ]", "ans_Q6W_tech")),
                                    listOf(InlineKeyboardButton.CallbackData("[ ждал обратную связь / согласование ]", "ans_Q6W_wait")),
                                    listOf(InlineKeyboardButton.CallbackData("[ сложная / новая задача, долго разбирался ]", "ans_Q6W_hard")),
                                    listOf(InlineKeyboardButton.CallbackData("[ личное состояние (расфокус, нет сил) ]", "ans_Q6W_personal"))
                                )
                            )
                        )
                    } else {
                        askAutonomyQuestion(bot, chatId, state)
                    }
                }
            }
            SurveyStep.Q6_SPEED_WHY -> {
                if (callbackData != null) {
                    save(state, "Q6_SPEED_WHY", callbackData.removePrefix("ans_Q6W_"))
                    askAutonomyQuestion(bot, chatId, state)
                }
            }
            SurveyStep.Q7_AUTONOMY -> {
                if (callbackData != null) {
                    save(state, "Q7_AUTONOMY", callbackData.removePrefix("ans_Q7_"))
                    state.currentStep = SurveyStep.Q8_DONE
                    bot.sendMessage(
                        chatId,
                        text = "8. Все ли запланированные задачи на сегодня удалось выполнить?",
                        replyMarkup = InlineKeyboardMarkup.create(
                            listOf(
                                listOf(InlineKeyboardButton.CallbackData("Да, выполнено 100%", "ans_Q8_100")),
                                listOf(InlineKeyboardButton.CallbackData("Большая часть (70-90%)", "ans_Q8_70")),
                                listOf(InlineKeyboardButton.CallbackData("Только половину (50%)", "ans_Q8_50")),
                                listOf(InlineKeyboardButton.CallbackData("Почти ничего (<30%)", "ans_Q8_30"))
                            )
                        )
                    )
                }
            }
            SurveyStep.Q8_DONE -> {
                if (callbackData != null) {
                    save(state, "Q8_DONE", callbackData.removePrefix("ans_Q8_"))
                    if (callbackData != "ans_Q8_100") {
                        state.currentStep = SurveyStep.Q8_DONE_WHY
                        bot.sendMessage(
                            chatId,
                            text = "Как думаешь, почему не получилось закрыть все задачи?",
                            replyMarkup = InlineKeyboardMarkup.create(
                                listOf(
                                    listOf(InlineKeyboardButton.CallbackData("[ Взял слишком много задач ]", "ans_Q8W_lots")),
                                    listOf(InlineKeyboardButton.CallbackData("[ Сбился план из-за срочных правок ]", "ans_Q8W_edits")),
                                    listOf(InlineKeyboardButton.CallbackData("[ Жесткий завал, физически не успеваю ]", "ans_Q8W_blocking"))
                                )
                            )
                        )
                    } else {
                        askEngagementQuestion(bot, chatId, state)
                    }
                }
            }
            SurveyStep.Q8_DONE_WHY -> {
                if (callbackData != null) {
                    save(state, "Q8_DONE_WHY", callbackData.removePrefix("ans_Q8W_"))
                    askEngagementQuestion(bot, chatId, state)
                }
            }
            SurveyStep.Q9_ENGAGE -> {
                if (callbackData != null) {
                    save(state, "Q9_ENGAGE", callbackData.removePrefix("ans_Q9_"))
                    state.currentStep = SurveyStep.Q10_NEWINFO
                    bot.sendMessage(
                        chatId,
                        text = "10. Искал(а) ли ты новую информацию по своей профессии? Удалось узнать что-нибудь интересное?",
                        replyMarkup = InlineKeyboardMarkup.create(
                            listOf(
                                listOf(InlineKeyboardButton.CallbackData("[ Да, узнал(а) крутую фишку/инструмент! ]", "ans_Q10_cool")),
                                listOf(InlineKeyboardButton.CallbackData("[ Да, читал(а)/изучал(а) теорию ]", "ans_Q10_theory")),
                                listOf(InlineKeyboardButton.CallbackData("[ Нет, всю неделю был чисто в рабочих задачах ]", "ans_Q10_none"))
                            )
                        )
                    )
                }
            }
            SurveyStep.Q10_NEWINFO -> {
                if (callbackData != null) {
                    save(state, "Q10_NEWINFO", callbackData.removePrefix("ans_Q10_"))
                    if (callbackData == "ans_Q10_cool") {
                        state.currentStep = SurveyStep.Q10_NEWINFO_SHARE
                        bot.sendMessage(chatId, "Класс! Поделись, что узнал(а)?")
                    } else {
                        endSurvey(bot, tgId, chatId, state)
                    }
                }
            }
            SurveyStep.Q10_NEWINFO_SHARE -> {
                if (textAnswer != null) {
                    save(state, "Q10_NEWINFO_SHARE", textAnswer)
                    endSurvey(bot, tgId, chatId, state)
                }
            }
            SurveyStep.FINISHED -> {}
        }
    }

    private fun goToHubstaffBranch(bot: Bot, tgId: Long, chatId: ChatId, state: SurveyState) {
        val status = DbRepository.getUserHubstaffStatus(tgId)
        if (status == true) {
            state.currentStep = SurveyStep.Q5_HUBSTAFF_PHOTO
            bot.sendMessage(chatId, "Укажи общее отработанное время в Hubstaff. Пришли скриншот:")
        } else if (status == false) {
            askSpeedQuestion(bot, chatId, state)
        } else {
            state.currentStep = SurveyStep.Q5_HUBSTAFF_YESNO
            bot.sendMessage(
                chatId,
                text = "5. Ты работаешь в Hubstaff?",
                replyMarkup = InlineKeyboardMarkup.createSingleRowKeyboard(
                    InlineKeyboardButton.CallbackData("Да", "ans_Q5_yes"),
                    InlineKeyboardButton.CallbackData("Нет", "ans_Q5_no")
                )
            )
        }
    }

    private fun askSpeedQuestion(bot: Bot, chatId: ChatId, state: SurveyState) {
        state.currentStep = SurveyStep.Q6_SPEED
        bot.sendMessage(
            chatId,
            text = "6. Как бы ты оценил(а) свою скорость работы сегодня? Насколько быстро двигались задачи?",
            replyMarkup = InlineKeyboardMarkup.create(
                listOf(
                    listOf(InlineKeyboardButton.CallbackData("[ 5/5 — опережаю график]", "ans_Q6_5")),
                    listOf(InlineKeyboardButton.CallbackData("[ 4/5 — иду строго по плану ]", "ans_Q6_4")),
                    listOf(InlineKeyboardButton.CallbackData("[ 3/5 — отстаю от графика ]", "ans_Q6_3")),
                    listOf(InlineKeyboardButton.CallbackData("[ 1-2/5 — на паузе ]", "ans_Q6_1-2"))
                )
            )
        )
    }

    private fun askAutonomyQuestion(bot: Bot, chatId: ChatId, state: SurveyState) {
        state.currentStep = SurveyStep.Q7_AUTONOMY
        bot.sendMessage(
            chatId,
            text = "7. Насколько самостоятельно удалось поработать сегодня?",
            replyMarkup = InlineKeyboardMarkup.create(
                listOf(
                    listOf(InlineKeyboardButton.CallbackData("[5/5 - сам видел проблемы и решал их на опережение ]", "ans_Q7_5")),
                    listOf(InlineKeyboardButton.CallbackData("[4/5 — работал автономно, вопросов почти не возникало ]", "ans_Q7_4")),
                    listOf(InlineKeyboardButton.CallbackData("[3/5 — требовалось среднее участие команды / Димы ]", "ans_Q7_3")),
                    listOf(InlineKeyboardButton.CallbackData("[1-2/5 — часто застревал, постоянно нужна была помощь ]", "ans_Q7_1-2"))
                )
            )
        )
    }

    private fun askEngagementQuestion(bot: Bot, chatId: ChatId, state: SurveyState) {
        state.currentStep = SurveyStep.Q9_ENGAGE
        bot.sendMessage(
            chatId,
            text = "9. Твой уровень вовлеченности в проекты DK FILMS сегодня",
            replyMarkup = InlineKeyboardMarkup.create(
                listOf(
                    listOf(InlineKeyboardButton.CallbackData("[ 5/5 — Горел делом, искренне вовлечен в результат ]", "ans_Q9_5")),
                    listOf(InlineKeyboardButton.CallbackData("[ 4/5 — Спокойно и качественно делал свою работу ]", "ans_Q9_4")),
                    listOf(InlineKeyboardButton.CallbackData("[ 3/5 — Делал через силу / не понимал ценность задачи ]", "ans_Q9_3")),
                    listOf(InlineKeyboardButton.CallbackData("[ 1-2/5 — Полный расфокус/ Просто присутствовал фоном ]", "ans_Q9_1-2"))
                )
            )
        )
    }

    private fun endSurvey(bot: Bot, tgId: Long, chatId: ChatId, state: SurveyState) {
        state.currentStep = SurveyStep.FINISHED
        SessionManager.activeSurveys.remove(tgId)
        bot.sendMessage(chatId, "✨ Спасибо! Твой ежедневный опрос успешно пройден.")

        // Помечаем отчет пройденным
        DbRepository.markReportCompleted(state.reportId)

        val (fullName, project) = DbRepository.getUserProfile(tgId)
        val empName = fullName ?: "Сотрудник (ID: $tgId)"
        val empProject = project ?: "Неизвестный проект"

        Thread {
            try {
                val openai = OpenAiService(System.getenv("OPENAI_API_KEY") ?: "")
                val (isFlagged, summary) = openai.analyzeDailyReport(empName, empProject, state.reportData)
                if (isFlagged) {
                    // СОХРАНЯЕМ ТЕКСТ АЛЕРТА В БАЗУ ДАННЫХ
                    DbRepository.saveAiAlert(state.reportId, summary)
                    BotDispatcher.notifyTeachers(bot, "🚨 **AI Анализ: Замечены отклонения!**\n👤 **Сотрудник:** $empName\n🎬 **Проект:** $empProject\n\n📝 **Резюме ИИ:** $summary")
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