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
import java.time.temporal.ChronoUnit

object SurveyManager {

    private val STANDARD_RATING = listOf(
        listOf(InlineKeyboardButton.CallbackData("9-10", "ans_9-10"), InlineKeyboardButton.CallbackData("7-8", "ans_7-8")),
        listOf(InlineKeyboardButton.CallbackData("4-6", "ans_4-6"), InlineKeyboardButton.CallbackData("1-3", "ans_1-3"))
    )

    private val UNKNOWN_GOAL_BUTTON = InlineKeyboardMarkup.create(
        listOf(listOf(InlineKeyboardButton.CallbackData("🤔 Пока не знаю", "ans_goal_unknown")))
    )

    private fun extractMessageId(result: Any?): Long? {
        if (result == null) return null
        val queue = ArrayDeque<Any>()
        queue.add(result)
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
                        if (name == "getmessageid" || name == "messageid") {
                            val res = method.invoke(current)
                            if (res is Number) return res.toLong()
                        }
                    }
                }
                for (method in cls.methods) {
                    if (method.parameterCount == 0 && method.name != "getClass" && method.name != "hashCode" && method.name != "toString") {
                        val name = method.name.lowercase()
                        if (name == "body" || name == "getvalue" || name == "getfirst" || name == "getsecond" || name == "component1" || name == "component2" || name == "getor null" || name == "get") {
                            val inner = method.invoke(current)
                            if (inner != null) queue.add(inner)
                        }
                    }
                }
            } catch (_: Exception) {}
        }
        return null
    }

    fun startSurvey(bot: Bot, tgId: Long, chatId: ChatId) {
        val reportId = DbRepository.getOrCreateReport(tgId)
        val userData = DbRepository.getUserData(tgId)

        val fullName = userData?.get(com.statbot.db.Users.fullName)
        val project = userData?.get(com.statbot.db.Users.project)

        // 1. Старт онбординга (создание профиля)
        if (fullName.isNullOrEmpty() || project.isNullOrEmpty()) {
            SessionManager.activeSurveys[tgId] = SurveyState(SurveyStep.REG_WELCOME, reportId)
            bot.sendMessage(
                chatId = chatId,
                text = "Давай создадим твой рабочий профиль",
                replyMarkup = InlineKeyboardMarkup.create(
                    listOf(listOf(InlineKeyboardButton.CallbackData("Создать", "ans_reg_start")))
                )
            )
            return
        }

        // 2. Первичный ввод целей
        val startDate = userData.get(com.statbot.db.Users.startDate)
        if (startDate == null) {
            startYearGoalQuestion(bot, chatId, tgId, reportId)
            return
        }

        val today = LocalDate.now()

        // В день первого заполнения анкеты регулярный опрос не запрашивается
        if (startDate == today) {
            bot.sendMessage(
                chatId,
                "👋 Профиль и цели успешно созданы! Сбор статистики начнется со завтрашнего дня по расписанию."
            )
            return
        }

        val dayOfWeek = today.dayOfWeek
        val cycleDay = DbRepository.getCalendarCycleDay(today)
        val daysFromStart = ChronoUnit.DAYS.between(startDate, today).toInt()

        val state = SurveyState(reportId = reportId)
        SessionManager.activeSurveys[tgId] = state

        when (dayOfWeek) {
            DayOfWeek.MONDAY -> startMondaySurvey(bot, chatId, state, tgId)
            DayOfWeek.FRIDAY -> startFridaySurvey(bot, chatId, state, tgId)
            else -> startDailySurveyByCycle(bot, chatId, state, cycleDay, tgId, daysFromStart)
        }
    }

    private fun startYearGoalQuestion(bot: Bot, chatId: ChatId, tgId: Long, reportId: Int) {
        SessionManager.activeSurveys[tgId] = SurveyState(SurveyStep.INIT_GOALS_YEAR, reportId)
        bot.sendMessage(
            chatId = chatId,
            text = "Дальше для всех:\nКаких результатов ты хочешь достичь за этот год, чтобы продвинуться к своим долгосрочным целям? Можешь указать как профессиональные, так и личные цели",
            replyMarkup = UNKNOWN_GOAL_BUTTON
        )
    }

    private fun start3MonthGoalQuestion(bot: Bot, chatId: ChatId) {
        bot.sendMessage(
            chatId = chatId,
            text = "Каких конкретных результатов ты хочешь достичь в ближайшие 3 месяца? Можешь указать как профессиональные, так и личные цели",
            replyMarkup = UNKNOWN_GOAL_BUTTON
        )
    }

    private fun finishOnboarding(bot: Bot, chatId: ChatId, tgId: Long) {
        DbRepository.setUserGoals(tgId, null, null)

        SessionManager.activeSurveys.remove(tgId)
        bot.sendMessage(
            chatId = chatId,
            text = "👋 Профиль и цели успешно созданы! Сбор статистики начнется со завтрашнего дня по расписанию.\n\n" +
                    "Все данные можно будет изменить в любое время в разделе «👤 Мой профиль».\n" +
                    "Если что-то в работе бота неудобно — обязательно напиши @ninafns 🎬"
        )
    }

    fun startGoalUpdate(bot: Bot, tgId: Long, chatId: ChatId) {
        val reportId = DbRepository.getOrCreateReport(tgId)
        SessionManager.activeSurveys[tgId] = SurveyState(SurveyStep.UPDATE_GOALS_YEAR, reportId)
        bot.sendMessage(
            chatId,
            "🔄 Режим обновления целей\n\n1. Каких новых результатов ты хочешь достичь за этот год?"
        )
    }

    private fun startMondaySurvey(bot: Bot, chatId: ChatId, state: SurveyState, tgId: Long) {
        val tasks = DbRepository.getUserData(tgId)?.get(com.statbot.db.Users.currentWeeklyTasks)
        if (tasks.isNullOrBlank()) {
            // Если планов с пятницы нет — запуск составления плана на эту неделю
            startPlanningFlow(bot, chatId, state, isMonday = true)
        } else {
            state.currentStep = SurveyStep.MON_TASKS_CHECK
            bot.sendMessage(
                chatId,
                text = "В пятницу ты запланировал(а) такие задачи на текущую неделю:\n\n$tasks\n\nВсе ли актуально?",
                replyMarkup = InlineKeyboardMarkup.create(
                    listOf(
                        listOf(InlineKeyboardButton.CallbackData("Да, все актуально", "ans_mon_yes")),
                        listOf(InlineKeyboardButton.CallbackData("Нет, есть изменения", "ans_mon_no"))
                    )
                )
            )
        }
    }

    private fun showMondayChangeMenu(bot: Bot, chatId: ChatId, state: SurveyState) {
        state.currentStep = SurveyStep.MON_CHANGE_MENU
        val markup = InlineKeyboardMarkup.create(
            listOf(
                listOf(InlineKeyboardButton.CallbackData("✏️ Изменить одну задачу", "ans_mon_m_edit")),
                listOf(InlineKeyboardButton.CallbackData("➕ Добавить задачу", "ans_mon_m_add")),
                listOf(InlineKeyboardButton.CallbackData("❌ Удалить задачу", "ans_mon_m_delete")),
                listOf(InlineKeyboardButton.CallbackData("🔄 Переписать план целиком", "ans_mon_m_rewrite")),
                listOf(InlineKeyboardButton.CallbackData("⬅️ Назад", "ans_mon_m_back")),
                listOf(InlineKeyboardButton.CallbackData("✅ Все готово", "ans_mon_m_done"))
            )
        )
        bot.sendMessage(chatId, "Что нужно изменить?", replyMarkup = markup)
    }

    private fun startFridaySurvey(bot: Bot, chatId: ChatId, state: SurveyState, tgId: Long) {
        val tasksText = DbRepository.getUserData(tgId)?.get(com.statbot.db.Users.currentWeeklyTasks)

        if (tasksText.isNullOrBlank()) {
            // Если ни в понедельник, ни в пятницу не было плана — сразу запрашиваем план на следующую неделю
            startPlanningFlow(bot, chatId, state, isMonday = false)
        } else {
            state.currentStep = SurveyStep.FRI_WEEK_SCORE
            bot.sendMessage(
                chatId,
                text = "Как прошла рабочая неделя?",
                replyMarkup = InlineKeyboardMarkup.create(STANDARD_RATING)
            )
        }
    }

    private fun startPlanningFlow(bot: Bot, chatId: ChatId, state: SurveyState, isMonday: Boolean) {
        state.isMondayPlanning = isMonday
        state.currentStep = SurveyStep.PLAN_RAW_INPUT

        val title = if (isMonday) "Составь план на эту неделю" else "Составь план на следующую неделю"
        bot.sendMessage(
            chatId,
            "$title\n\nРасскажи одним сообщением:\n1. Над каким проектом или проектами ты будешь работать?\n2. Какие конкретные задачи ты хочешь выполнить?\n3. Какой законченный и ценный результат должен появиться к концу недели?"
        )
    }

    private fun buildPlanReviewKeyboard(): InlineKeyboardMarkup {
        return InlineKeyboardMarkup.create(
            listOf(
                listOf(InlineKeyboardButton.CallbackData("✅ Всё верно, сохранить", "ans_plan_save")),
                listOf(InlineKeyboardButton.CallbackData("✏️ Изменить одну задачу", "ans_plan_edit")),
                listOf(InlineKeyboardButton.CallbackData("➕ Добавить задачу", "ans_plan_add")),
                listOf(InlineKeyboardButton.CallbackData("🗑️ Удалить задачу", "ans_plan_delete")),
                listOf(InlineKeyboardButton.CallbackData("🔄 Переписать план целиком", "ans_plan_rewrite"))
            )
        )
    }

    private fun showPlanReviewMenu(bot: Bot, chatId: ChatId, state: SurveyState) {
        state.currentStep = SurveyStep.PLAN_REVIEW_MENU
        val tasksText = state.tempTasks.mapIndexed { idx, t -> "${idx + 1}. $t" }.joinToString("\n")

        val messageText = "Я структурировал твои задачи:\n\n$tasksText\n\nПроверь, нужно что-то изменить?"
        val res = bot.sendMessage(chatId, messageText, replyMarkup = buildPlanReviewKeyboard())
        extractMessageId(res)?.let { state.tempMessageIds.add(it) }
    }

    private fun buildFridayTasksKeyboard(taskList: List<String>, selectedIndices: Set<Int>): InlineKeyboardMarkup {
        val rows = mutableListOf<List<InlineKeyboardButton>>()
        taskList.forEachIndexed { index, task ->
            val isChecked = index in selectedIndices
            val icon = if (isChecked) "✅" else "☐"
            val displayTask = if (task.length > 30) task.take(27) + "..." else task
            rows.add(listOf(InlineKeyboardButton.CallbackData("$icon $displayTask", "ans_fri_toggle_$index")))
        }
        rows.add(listOf(InlineKeyboardButton.CallbackData("💾 Сохранить", "ans_fri_tasks_save")))
        return InlineKeyboardMarkup.create(rows)
    }

    fun processAnswer(
        bot: Bot,
        tgId: Long,
        chatId: ChatId,
        textAnswer: String?,
        callbackData: String?,
        photoUrl: String? = null,
        messageId: Long? = null
    ) {
        val state = SessionManager.activeSurveys[tgId] ?: return
        val valData = callbackData?.removePrefix("ans_")

        when (state.currentStep) {
            // --- ОНБОРДИНГ ПРОФИЛЯ ---
            SurveyStep.REG_WELCOME -> {
                if (valData == "reg_start") {
                    state.currentStep = SurveyStep.REG_FULL_NAME
                    bot.sendMessage(chatId, "Напиши свое имя и фамилию\nПример: Иван Смирнов")
                }
            }
            SurveyStep.REG_FULL_NAME -> {
                if (textAnswer != null) {
                    val firstName = textAnswer.split(" ").firstOrNull() ?: textAnswer
                    DbRepository.updateOnboardingProfile(tgId, fullName = textAnswer)
                    state.currentStep = SurveyStep.REG_ROLE_DESC
                    bot.sendMessage(chatId, "$firstName, чем ты занимаешься в команде?\nПример: продюсирую проекты, веду соцсети, монтирую видео")
                }
            }
            SurveyStep.REG_ROLE_DESC -> {
                if (textAnswer != null) {
                    DbRepository.updateOnboardingProfile(tgId, project = textAnswer)
                    state.currentStep = SurveyStep.REG_WORK_TYPE
                    val markup = InlineKeyboardMarkup.create(
                        listOf(
                            listOf(
                                InlineKeyboardButton.CallbackData("💼 За зарплату", "ans_work_salary"),
                                InlineKeyboardButton.CallbackData("🎥 Для портфолио", "ans_work_portfolio")
                            )
                        )
                    )
                    bot.sendMessage(chatId, "Как ты сейчас работаешь?", replyMarkup = markup)
                }
            }
            SurveyStep.REG_WORK_TYPE -> {
                if (valData == "work_salary") {
                    DbRepository.updateOnboardingProfile(tgId, workType = "За зарплату")
                    state.currentStep = SurveyStep.REG_WORK_HOURS
                    bot.sendMessage(chatId, "Если “за зарплату” - Во сколько ты обычно начинаешь и заканчиваешь рабочий день?\nПример: 09:00 — 18:00")
                } else if (valData == "work_portfolio") {
                    DbRepository.updateOnboardingProfile(tgId, workType = "Для портфолио", workHours = "-")
                    startYearGoalQuestion(bot, chatId, tgId, state.reportId)
                }
            }
            SurveyStep.REG_WORK_HOURS -> {
                if (textAnswer != null) {
                    DbRepository.updateOnboardingProfile(tgId, workHours = textAnswer)
                    startYearGoalQuestion(bot, chatId, tgId, state.reportId)
                }
            }

            // --- ПЕРВИЧНАЯ ПОСТАНОВКА ЦЕЛЕЙ ---
            SurveyStep.INIT_GOALS_YEAR -> {
                if (valData == "goal_unknown") {
                    bot.sendMessage(chatId, "Ничего страшного! Цель на год можно добавить позже")
                    DbRepository.setUserGoals(tgId, "Не указано", null)
                    state.currentStep = SurveyStep.INIT_GOALS_3MONTHS
                    start3MonthGoalQuestion(bot, chatId)
                } else if (textAnswer != null) {
                    DbRepository.setUserGoals(tgId, textAnswer, null)
                    state.currentStep = SurveyStep.INIT_GOALS_3MONTHS
                    start3MonthGoalQuestion(bot, chatId)
                }
            }
            SurveyStep.INIT_GOALS_3MONTHS -> {
                if (valData == "goal_unknown") {
                    DbRepository.setUserGoals(tgId, null, "Не указано")
                    finishOnboarding(bot, chatId, tgId)
                } else if (textAnswer != null) {
                    DbRepository.setUserGoals(tgId, null, textAnswer)
                    finishOnboarding(bot, chatId, tgId)
                }
            }

            // --- ИИ-СЦЕНАРИЙ ПЛАНИРОВАНИЯ ЗАДАЧ ---
            SurveyStep.PLAN_RAW_INPUT, SurveyStep.PLAN_REWRITE_TEXT -> {
                if (textAnswer != null) {
                    val progressRes = bot.sendMessage(chatId, "🤖 Структурирую твои задачи...")
                    extractMessageId(progressRes)?.let { state.tempMessageIds.add(it) }

                    Thread {
                        val apiKey = getApiKey()
                        val openai = OpenAiService(apiKey)
                        val structuredText = openai.structureWeeklyTasks(textAnswer)

                        state.tempTasks = parseTaskList(structuredText).toMutableList()
                        showPlanReviewMenu(bot, chatId, state)
                    }.start()
                }
            }

            SurveyStep.PLAN_REVIEW_MENU -> {
                when (valData) {
                    "plan_save" -> {
                        val formattedTasks = state.tempTasks.mapIndexed { idx, task -> "${idx + 1}. $task" }.joinToString("\n")
                        DbRepository.setWeeklyTasks(tgId, formattedTasks)

                        // Удаляем временные сообщения текущей сессии
                        state.tempMessageIds.forEach { msgId ->
                            try { bot.deleteMessage(chatId, msgId) } catch (_: Exception) {}
                        }

                        val finalMessage = if (state.isMondayPlanning) {
                            "План на эту неделю сохранён. Удачной рабочей недели!"
                        } else {
                            "План на следующую неделю сохранён. Хороших выходных!"
                        }

                        bot.sendMessage(chatId, finalMessage)
                        endSurvey(bot, tgId, chatId, state)
                    }
                    "plan_edit" -> {
                        state.currentStep = SurveyStep.PLAN_EDIT_SELECT
                        val buttons = state.tempTasks.mapIndexed { idx, _ ->
                            listOf(InlineKeyboardButton.CallbackData("${idx + 1}", "ans_p_edit_idx_$idx"))
                        } + listOf(listOf(InlineKeyboardButton.CallbackData("← Назад", "ans_p_back")))

                        bot.sendMessage(chatId, "Как изменить эту задачу?", replyMarkup = InlineKeyboardMarkup.create(buttons))
                    }
                    "plan_add" -> {
                        state.currentStep = SurveyStep.PLAN_ADD_TEXT
                        bot.sendMessage(chatId, "Напиши новую задачу, которую нужно добавить в план")
                    }
                    "plan_delete" -> {
                        state.currentStep = SurveyStep.PLAN_DELETE_SELECT
                        val buttons = state.tempTasks.mapIndexed { idx, _ ->
                            listOf(InlineKeyboardButton.CallbackData("${idx + 1}", "ans_p_del_idx_$idx"))
                        } + listOf(listOf(InlineKeyboardButton.CallbackData("← Назад", "ans_p_back")))

                        bot.sendMessage(chatId, "Выбери номер задачи для удаления:", replyMarkup = InlineKeyboardMarkup.create(buttons))
                    }
                    "plan_rewrite" -> {
                        state.currentStep = SurveyStep.PLAN_REWRITE_TEXT
                        val title = if (state.isMondayPlanning) "Составь новый план на эту неделю" else "Составь новый план на следующую неделю"
                        bot.sendMessage(chatId, title)
                    }
                }
            }

            SurveyStep.PLAN_EDIT_SELECT -> {
                if (valData == "p_back") {
                    showPlanReviewMenu(bot, chatId, state)
                } else if (valData?.startsWith("p_edit_idx_") == true) {
                    val idx = valData.removePrefix("p_edit_idx_").toIntOrNull()
                    if (idx != null && idx in state.tempTasks.indices) {
                        state.editingTaskIndex = idx
                        state.currentStep = SurveyStep.PLAN_EDIT_TEXT
                        bot.sendMessage(chatId, "Введи новый текст для задачи №${idx + 1}:")
                    }
                }
            }

            SurveyStep.PLAN_EDIT_TEXT -> {
                if (textAnswer != null) {
                    if (state.editingTaskIndex in state.tempTasks.indices) {
                        state.tempTasks[state.editingTaskIndex] = textAnswer
                    }
                    bot.sendMessage(chatId, "Задача изменена👍\n\nТы можешь продолжить редактирование или сохранить план, нажав кнопку «✅ Всё верно, сохранить» выше")
                    showPlanReviewMenu(bot, chatId, state)
                }
            }

            SurveyStep.PLAN_ADD_TEXT -> {
                if (textAnswer != null) {
                    Thread {
                        val openai = OpenAiService(getApiKey())
                        val structuredNewTask = openai.structureWeeklyTasks(textAnswer)
                        val newItems = parseTaskList(structuredNewTask)
                        if (newItems.isNotEmpty()) state.tempTasks.addAll(newItems) else state.tempTasks.add(textAnswer)

                        bot.sendMessage(chatId, "Задача добавлена👍\n\nТы можешь продолжить редактирование или сохранить план, нажав кнопку «✅ Всё верно, сохранить» выше")
                        showPlanReviewMenu(bot, chatId, state)
                    }.start()
                }
            }

            SurveyStep.PLAN_DELETE_SELECT -> {
                if (valData == "p_back") {
                    showPlanReviewMenu(bot, chatId, state)
                } else if (valData?.startsWith("p_del_idx_") == true) {
                    val idx = valData.removePrefix("p_del_idx_").toIntOrNull()
                    if (idx != null && idx in state.tempTasks.indices) {
                        state.tempTasks.removeAt(idx)
                        bot.sendMessage(chatId, "Задача удалена👍\n\nТы можешь продолжить редактирование или сохранить план, нажав кнопку «✅ Всё верно, сохранить» выше")
                        showPlanReviewMenu(bot, chatId, state)
                    }
                }
            }

            // --- ОБНОВЛЕНИЕ ЦЕЛЕЙ ВРУЧНУЮ ---
            SurveyStep.UPDATE_GOALS_YEAR -> {
                if (textAnswer != null) {
                    DbRepository.setUserGoals(tgId, textAnswer, null)
                    state.currentStep = SurveyStep.UPDATE_GOALS_3MONTHS
                    bot.sendMessage(chatId, "Отлично! Теперь напиши свои новые цели на ближайшие 3 месяца:")
                }
            }
            SurveyStep.UPDATE_GOALS_3MONTHS -> {
                if (textAnswer != null) {
                    DbRepository.setUserGoals(tgId, null, textAnswer)
                    SessionManager.activeSurveys.remove(tgId)
                    bot.sendMessage(chatId, "✅ Твои цели успешно обновлены и сохранены в базе!")
                }
            }

            // --- ПОНЕДЕЛЬНИК ---
            SurveyStep.MON_TASKS_CHECK -> {
                if (valData == "mon_yes") {
                    save(state, "MON_TASKS", "Да, все актуально")
                    checkPeriodicOrDailyCycle(bot, tgId, chatId, state)
                } else if (valData == "mon_no") {
                    showMondayChangeMenu(bot, chatId, state)
                }
            }
            SurveyStep.MON_CHANGE_MENU -> {
                when (valData) {
                    "mon_m_back" -> startMondaySurvey(bot, chatId, state, tgId)
                    "mon_m_done" -> {
                        save(state, "MON_TASKS", "Изменения внесены")
                        checkPeriodicOrDailyCycle(bot, tgId, chatId, state)
                    }
                    "mon_m_edit" -> {
                        val tasksText = DbRepository.getUserData(tgId)?.get(com.statbot.db.Users.currentWeeklyTasks)
                        val taskList = parseTaskList(tasksText)
                        if (taskList.isEmpty()) {
                            state.currentStep = SurveyStep.MON_ADD_TASK_TEXT
                            bot.sendMessage(chatId, "Список задач пуст. Введи текст новой задачи:")
                        } else {
                            state.currentStep = SurveyStep.MON_EDIT_SELECT_TASK
                            val buttons = taskList.mapIndexed { idx, task ->
                                listOf(InlineKeyboardButton.CallbackData("${idx + 1}. $task", "ans_mon_edit_idx_$idx"))
                            } + listOf(listOf(InlineKeyboardButton.CallbackData("⬅️ Назад в меню", "ans_mon_m_show")))
                            bot.sendMessage(chatId, "Выбери задачу, которую хочешь изменить:", replyMarkup = InlineKeyboardMarkup.create(buttons))
                        }
                    }
                    "mon_m_add" -> {
                        state.currentStep = SurveyStep.MON_ADD_TASK_TEXT
                        bot.sendMessage(chatId, "Введи текст новой задачи:")
                    }
                    "mon_m_delete" -> {
                        val tasksText = DbRepository.getUserData(tgId)?.get(com.statbot.db.Users.currentWeeklyTasks)
                        val taskList = parseTaskList(tasksText)
                        if (taskList.isEmpty()) {
                            bot.sendMessage(chatId, "Список задач пуст.")
                            showMondayChangeMenu(bot, chatId, state)
                        } else {
                            state.currentStep = SurveyStep.MON_DELETE_SELECT_TASK
                            val buttons = taskList.mapIndexed { idx, task ->
                                listOf(InlineKeyboardButton.CallbackData("❌ ${idx + 1}. $task", "ans_mon_del_idx_$idx"))
                            } + listOf(listOf(InlineKeyboardButton.CallbackData("⬅️ Назад в меню", "ans_mon_m_show")))
                            bot.sendMessage(chatId, "Выбери задачу для удаления:", replyMarkup = InlineKeyboardMarkup.create(buttons))
                        }
                    }
                    "mon_m_rewrite" -> {
                        state.currentStep = SurveyStep.MON_REWRITE_TASKS
                        bot.sendMessage(chatId, "Напиши полный новый список задач на эту неделю (каждую задачу с новой строки):")
                    }
                }
            }
            SurveyStep.MON_EDIT_SELECT_TASK -> {
                if (valData == "mon_m_show") {
                    showMondayChangeMenu(bot, chatId, state)
                } else if (valData?.startsWith("mon_edit_idx_") == true) {
                    val idx = valData.removePrefix("mon_edit_idx_").toIntOrNull()
                    if (idx != null) {
                        state.editingTaskIndex = idx
                        state.currentStep = SurveyStep.MON_EDIT_TASK_TEXT
                        val tasksText = DbRepository.getUserData(tgId)?.get(com.statbot.db.Users.currentWeeklyTasks)
                        val taskList = parseTaskList(tasksText)
                        val currentTask = taskList.getOrNull(idx) ?: ""
                        bot.sendMessage(chatId, "Введи новый текст для задачи №${idx + 1}:\n\"$currentTask\"")
                    }
                }
            }
            SurveyStep.MON_EDIT_TASK_TEXT -> {
                if (textAnswer != null) {
                    val tasksText = DbRepository.getUserData(tgId)?.get(com.statbot.db.Users.currentWeeklyTasks)
                    val taskList = parseTaskList(tasksText).toMutableList()
                    if (state.editingTaskIndex in taskList.indices) {
                        taskList[state.editingTaskIndex] = textAnswer
                    } else {
                        taskList.add(textAnswer)
                    }
                    val formatted = formatTaskList(taskList)
                    DbRepository.setWeeklyTasks(tgId, formatted)
                    bot.sendMessage(chatId, "✅ Задача обновлена!\n\nТекущие задачи:\n$formatted")
                    showMondayChangeMenu(bot, chatId, state)
                }
            }
            SurveyStep.MON_ADD_TASK_TEXT -> {
                if (textAnswer != null) {
                    val tasksText = DbRepository.getUserData(tgId)?.get(com.statbot.db.Users.currentWeeklyTasks)
                    val taskList = parseTaskList(tasksText).toMutableList()
                    taskList.add(textAnswer)
                    val formatted = formatTaskList(taskList)
                    DbRepository.setWeeklyTasks(tgId, formatted)
                    bot.sendMessage(chatId, "✅ Задача добавлена!\n\nТекущие задачи:\n$formatted")
                    showMondayChangeMenu(bot, chatId, state)
                }
            }
            SurveyStep.MON_DELETE_SELECT_TASK -> {
                if (valData == "mon_m_show") {
                    showMondayChangeMenu(bot, chatId, state)
                } else if (valData?.startsWith("mon_del_idx_") == true) {
                    val idx = valData.removePrefix("mon_del_idx_").toIntOrNull()
                    if (idx != null) {
                        val tasksText = DbRepository.getUserData(tgId)?.get(com.statbot.db.Users.currentWeeklyTasks)
                        val taskList = parseTaskList(tasksText).toMutableList()
                        if (idx in taskList.indices) {
                            taskList.removeAt(idx)
                        }
                        val formatted = formatTaskList(taskList)
                        DbRepository.setWeeklyTasks(tgId, formatted)
                        bot.sendMessage(chatId, "✅ Задача удалена!\n\nТекущие задачи:\n${formatted.ifEmpty { "Список пуст" }}")
                        showMondayChangeMenu(bot, chatId, state)
                    }
                }
            }
            SurveyStep.MON_REWRITE_TASKS -> {
                if (textAnswer != null) {
                    val taskList = parseTaskList(textAnswer)
                    val formatted = formatTaskList(taskList)
                    DbRepository.setWeeklyTasks(tgId, formatted)
                    bot.sendMessage(chatId, "✅ План на неделю успешно переписан!\n\nТекущие задачи:\n$formatted")
                    showMondayChangeMenu(bot, chatId, state)
                }
            }

            // --- ПЯТНИЦА ---
            SurveyStep.FRI_WEEK_SCORE -> {
                if (valData != null) {
                    save(state, "FRI_WEEK_SCORE", valData)
                    state.currentStep = SurveyStep.FRI_CALL_DAYS
                    bot.sendMessage(
                        chatId,
                        "Сколько дней ты был(а) на созвонах за эту неделю?",
                        replyMarkup = InlineKeyboardMarkup.create(
                            listOf(listOf("1", "2", "3", "4", "5").map { InlineKeyboardButton.CallbackData(it, "ans_$it") })
                        )
                    )
                }
            }
            SurveyStep.FRI_CALL_DAYS -> {
                if (valData != null) {
                    save(state, "FRI_CALL_DAYS", valData)
                    state.currentStep = SurveyStep.FRI_SELECT_TASKS
                    state.selectedTaskIndices.clear()

                    val tasksText = DbRepository.getUserData(tgId)?.get(com.statbot.db.Users.currentWeeklyTasks)
                    val taskList = parseTaskList(tasksText)

                    if (taskList.isEmpty()) {
                        askFriExtraTasks(bot, chatId, state)
                    } else {
                        bot.sendMessage(
                            chatId,
                            "Отметь, какие поставленные задачи на эту неделю ты выполнил(а):",
                            replyMarkup = buildFridayTasksKeyboard(taskList, state.selectedTaskIndices)
                        )
                    }
                }
            }
            SurveyStep.FRI_SELECT_TASKS -> {
                val tasksText = DbRepository.getUserData(tgId)?.get(com.statbot.db.Users.currentWeeklyTasks)
                val taskList = parseTaskList(tasksText)

                if (valData?.startsWith("fri_toggle_") == true) {
                    val idx = valData.removePrefix("fri_toggle_").toIntOrNull()
                    if (idx != null && idx in taskList.indices) {
                        if (idx in state.selectedTaskIndices) {
                            state.selectedTaskIndices.remove(idx)
                        } else {
                            state.selectedTaskIndices.add(idx)
                        }
                        if (messageId != null) {
                            try {
                                bot.editMessageReplyMarkup(
                                    chatId = chatId,
                                    messageId = messageId,
                                    replyMarkup = buildFridayTasksKeyboard(taskList, state.selectedTaskIndices)
                                )
                            } catch (_: Exception) {}
                        }
                    }
                } else if (valData == "fri_tasks_save") {
                    val doneCount = state.selectedTaskIndices.size
                    val totalCount = taskList.size

                    val completed = state.selectedTaskIndices.mapNotNull { taskList.getOrNull(it) }
                    val missed = taskList.indices.filter { it !in state.selectedTaskIndices }.mapNotNull { taskList.getOrNull(it) }

                    save(state, "FRI_COMPLETED_TASKS", completed.joinToString("; "))
                    save(state, "FRI_MISSED_TASKS", missed.joinToString("; "))

                    if (doneCount == totalCount && totalCount > 0) {
                        save(state, "FRI_TASK_PCT", "100%")
                        bot.sendMessage(chatId, "Ты выполнил 100% от своего плана. Отличная работа! 🎉")
                        askFriExtraTasks(bot, chatId, state)
                    } else {
                        val pctStr = if (totalCount > 0) "${(doneCount * 100) / totalCount}%" else "<30%"
                        save(state, "FRI_TASK_PCT", pctStr)
                        state.currentStep = SurveyStep.FRI_MISSED_REASON
                        bot.sendMessage(chatId, "Расскажи, почему не получилось выполнить все задачи?")
                    }
                }
            }
            SurveyStep.FRI_MISSED_REASON -> {
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
                startPlanningFlow(bot, chatId, state, isMonday = false)
            }

            // --- ДНИ ЦИКЛА И ДРУГИЕ ВОПРОСЫ ---
            SurveyStep.DAY2_ENERGY -> {
                if (valData != null) {
                    save(state, "DAY2_ENERGY", valData)
                    state.currentStep = SurveyStep.DAY2_SPEED
                    bot.sendMessage(chatId, "Оцени скорость выполнения задач:", replyMarkup = InlineKeyboardMarkup.create(STANDARD_RATING))
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
            SurveyStep.DAY3_NEW_INFO -> {
                if (valData != null) {
                    save(state, "DAY3_NEW_INFO", valData)
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

    private fun startDailySurveyByCycle(bot: Bot, chatId: ChatId, state: SurveyState, cycleDay: Int, tgId: Long, daysFromStart: Int) {
        when (cycleDay) {
            2 -> {
                state.currentStep = SurveyStep.DAY2_ENERGY
                bot.sendMessage(chatId, "На сколько ты энергичен(а)?", replyMarkup = InlineKeyboardMarkup.create(STANDARD_RATING))
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
                bot.sendMessage(chatId, "Оцени свою инициативность:", replyMarkup = InlineKeyboardMarkup.create(STANDARD_RATING))
            }
            10 -> {
                state.currentStep = SurveyStep.DAY10_NOTES_QUALITY
                bot.sendMessage(chatId, "Насколько качественно фиксируешь задачи после созвона?", replyMarkup = InlineKeyboardMarkup.create(STANDARD_RATING))
            }
            11 -> {
                state.currentStep = SurveyStep.DAY11_CALL_ENGAGEMENT
                bot.sendMessage(chatId, "Оцени свою включенность на созвонах:", replyMarkup = InlineKeyboardMarkup.create(STANDARD_RATING))
            }
            16 -> {
                state.currentStep = SurveyStep.DAY16_EMOTIONAL
                bot.sendMessage(chatId, "Оцени свой эмоциональный настрой:", replyMarkup = InlineKeyboardMarkup.create(STANDARD_RATING))
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
                bot.sendMessage(chatId, "Как ты оцениваешь ценность созвонов для себя?", replyMarkup = InlineKeyboardMarkup.create(STANDARD_RATING))
            }
            23 -> {
                state.currentStep = SurveyStep.DAY23_NOTES_QUALITY
                bot.sendMessage(chatId, "Насколько качественно фиксируешь задачи после созвона?", replyMarkup = InlineKeyboardMarkup.create(STANDARD_RATING))
            }
            24 -> {
                state.currentStep = SurveyStep.DAY24_AUTONOMY
                bot.sendMessage(chatId, "Насколько самостоятельно ты справляешься с задачами?", replyMarkup = InlineKeyboardMarkup.create(STANDARD_RATING))
            }
            25 -> {
                state.currentStep = SurveyStep.DAY25_HAPPINESS
                bot.sendMessage(chatId, "На сколько ты счастлив(а) в компании?", replyMarkup = InlineKeyboardMarkup.create(STANDARD_RATING))
            }
            30 -> {
                state.currentStep = SurveyStep.DAY30_HOURS
                bot.sendMessage(chatId, "Сколько часов в среднем в день ты работал(а)?", replyMarkup = InlineKeyboardMarkup.create(
                    listOf(
                        listOf(InlineKeyboardButton.CallbackData("0-1", "ans_0-1"), InlineKeyboardButton.CallbackData("2-3", "ans_2-3")),
                        listOf(InlineKeyboardButton.CallbackData("4-6", "ans_4-6"), InlineKeyboardButton.CallbackData("7+", "ans_7+"))
                    )
                ))
            }
            else -> {
                checkPeriodicSurvey(bot, tgId, chatId, state, daysFromStart)
            }
        }
    }

    private fun checkPeriodicSurvey(bot: Bot, tgId: Long, chatId: ChatId, state: SurveyState, daysFromStart: Int) {
        val userData = DbRepository.getUserData(tgId)
        val yearGoals = userData?.get(com.statbot.db.Users.goalsYear) ?: "Не указаны"
        val threeMonthGoals = userData?.get(com.statbot.db.Users.goals3Months) ?: "Не указаны"

        when {
            daysFromStart > 0 && daysFromStart % 365 == 0 -> {
                state.currentStep = SurveyStep.YEARLY_GOALS_RESULT_TEXT
                bot.sendMessage(chatId, "Год назад ты поставил(а) перед собой следующие цели:\n$yearGoals\n\nКаких результатов удалось достичь по этим целям?")
            }
            daysFromStart > 0 && daysFromStart % 180 == 0 -> {
                state.currentStep = SurveyStep.HALF_YEAR_WORLD_PROJECTS
                bot.sendMessage(chatId, "Сколько завершенных проектов в твоём портфолио выполнены на мировом уровне? Укажи количество и перечисли проекты, которые обсуждались на созвонах:")
            }
            daysFromStart > 0 && daysFromStart % 90 == 0 -> {
                state.currentStep = SurveyStep.Q_HEALTH_SCORE
                bot.sendMessage(chatId, "Как бы ты оценил(а) свое общее состояние своего здоровья?", replyMarkup = InlineKeyboardMarkup.create(STANDARD_RATING))
            }
            daysFromStart > 0 && daysFromStart % 30 == 0 -> {
                state.currentStep = SurveyStep.MONTHLY_GOALS_PROGRESS
                bot.sendMessage(chatId, "Твои цели на 3 месяца:\n$threeMonthGoals\n\nЧто ты сделал(а) за этот месяц, чтобы приблизиться к каждой из этих целей?")
            }
            else -> {
                SessionManager.activeSurveys.remove(tgId)
                DbRepository.markReportCompleted(state.reportId)
                bot.sendMessage(chatId, "Сегодня нет обязательных вопросов по графику. Хорошего рабочего дня! 🚀")
            }
        }
    }

    private fun checkPeriodicOrDailyCycle(bot: Bot, tgId: Long, chatId: ChatId, state: SurveyState) {
        val userData = DbRepository.getUserData(tgId)
        val startDate = userData?.get(com.statbot.db.Users.startDate) ?: LocalDate.now()
        val today = LocalDate.now()
        val daysFromStart = ChronoUnit.DAYS.between(startDate, today).toInt()

        if (daysFromStart > 0 && (daysFromStart % 30 == 0 || daysFromStart % 90 == 0 || daysFromStart % 180 == 0 || daysFromStart % 365 == 0)) {
            checkPeriodicSurvey(bot, tgId, chatId, state, daysFromStart)
        } else {
            endSurvey(bot, tgId, chatId, state)
        }
    }

    private fun askFriExtraTasks(bot: Bot, chatId: ChatId, state: SurveyState) {
        state.currentStep = SurveyStep.FRI_EXTRA_TASKS_YESNO
        bot.sendMessage(chatId, "Были ли у тебя дополнительные задачи помимо запланированных?", replyMarkup = InlineKeyboardMarkup.create(
            listOf(
                listOf(InlineKeyboardButton.CallbackData("+ Да, были", "ans_extra_yes")),
                listOf(InlineKeyboardButton.CallbackData("- Нет, не было", "ans_extra_no"))
            )
        ))
    }

    private fun askFriNextWeekTasks(bot: Bot, chatId: ChatId, state: SurveyState) {
        state.currentStep = SurveyStep.FRI_NEXT_WEEK_TASKS
        startPlanningFlow(bot, chatId, state, isMonday = false)
    }

    private fun parseTaskList(tasksText: String?): List<String> {
        if (tasksText.isNullOrBlank()) return emptyList()
        return tasksText.lines()
            .map { it.replace(Regex("^\\d+\\.\\s*"), "").trim() }
            .filter { it.isNotBlank() }
    }

    private fun formatTaskList(taskList: List<String>): String {
        return taskList.mapIndexed { index, task -> "${index + 1}. $task" }.joinToString("\n")
    }

    private fun save(state: SurveyState, key: String, value: String) {
        DbRepository.saveAnswer(state.reportId, key, value)
        state.reportData[key] = value
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
                val apiKey = getApiKey()
                if (apiKey.isBlank()) {
                    println("❌ [AI ERROR] OPENAI_API_KEY не найден!")
                    return@Thread
                }

                val openai = OpenAiService(apiKey)
                val (isFlagged, summary) = openai.analyzeDailyReport(empName, empProject, state.reportData)
                if (isFlagged) {
                    DbRepository.saveAiAlert(state.reportId, summary)
                    BotDispatcher.notifyTeachers(bot, "🚨 AI Анализ ТЗ:\n👤 Сотрудник: $empName\n🎬 Проект: $empProject\n\n📝 Вывод: $summary")
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    private fun getApiKey(): String {
        return System.getenv("OPENAI_API_KEY") ?: java.util.Properties().apply {
            val file = java.io.File("local.properties")
            if (file.exists()) load(file.inputStream())
        }.getProperty("OPENAI_API_KEY") ?: ""
    }
}