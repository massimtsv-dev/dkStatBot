package com.statbot.bot

import com.statbot.ai.OpenAiClient
import com.statbot.ai.PlanAssistant
import com.statbot.ai.ReportAnalyst
import com.statbot.config.AppConfig
import com.statbot.db.Repository
import com.statbot.notifications.SchedulerService
import com.statbot.survey.SessionStore
import com.statbot.survey.SurveyEngine
import com.statbot.telegram.TelegramClient
import com.statbot.telegram.TgUser
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("Main")

private const val DESCRIPTION = """📅 Помогает планировать неделю
Определяет основные задачи и цели на ближайшую неделю.

💬 Задаёт вопросы для обратной связи
Периодически спрашивает о работе, результатах и возникающих сложностях.

На открытые вопросы отвечай удобным способом: текстом или голосовым сообщением. Если предложены варианты ответа — просто выбери подходящий."""

private const val SHORT_DESCRIPTION = "Личный помощник DK FILMS: план недели и регулярная обратная связь."

fun main() = runBlocking {
    val config = AppConfig.load()
    val repo = Repository(config.dbPath)
    val tg = TelegramClient(config.botToken, config.telegramApiUrl)
    val ai = OpenAiClient(config)
    if (!ai.enabled) log.warn("OPENAI_API_KEY не задан: план будет разбираться без ИИ, голосовые и ИИ-анализ отключены")

    val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> log.error("Необработанная ошибка", e) }
    )

    val store = SessionStore(repo).also { it.load() }
    val engine = SurveyEngine(
        tg = tg,
        repo = repo,
        store = store,
        planner = PlanAssistant(ai),
        analyst = ReportAnalyst(ai, config.openAiAnalysisModel),
        config = config,
        scope = scope,
        aiEnabled = ai.enabled,
    )
    val teacher = TeacherPanel(tg, repo, config.zone)

    // Проверяем токен (с повтором, если сеть ещё не поднялась)
    var me: TgUser? = null
    for (attempt in 1..5) {
        try {
            me = tg.getMe()
            break
        } catch (e: Exception) {
            if (attempt == 5) throw IllegalStateException("Не удалось подключиться к Telegram: ${e.message}", e)
            log.warn("Telegram недоступен ({}), повтор через 5 c", e.message)
            delay(5000)
        }
    }

    tg.setMyDescription(DESCRIPTION, SHORT_DESCRIPTION)
    tg.setMyCommands(
        buildList {
            add("start" to "Запустить бота / главное меню")
            add("profile" to "👤 Мой профиль")
            add("survey" to "Пройти сегодняшний опрос")
            add("teacher" to "Панель преподавателя")
            if (config.testCommandsEnabled) add("test" to "🧪 Тестовые сценарии")
        }
    )

    SchedulerService(engine, repo, config.zone, scope).start()

    log.info("====================================================")
    log.info("БОТ СБОРА СТАТИСТИКИ ЗАПУЩЕН: @{}", me?.username)
    log.info("====================================================")

    BotApp(tg, engine, repo, ai, teacher, config, scope).runPolling()
}
