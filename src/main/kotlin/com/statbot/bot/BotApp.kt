package com.statbot.bot

import com.statbot.ai.OpenAiClient
import com.statbot.config.AppConfig
import com.statbot.db.Repository
import com.statbot.model.Role
import com.statbot.survey.SurveyEngine
import com.statbot.telegram.ReplyKeyboard
import com.statbot.telegram.TelegramClient
import com.statbot.telegram.TgCallbackQuery
import com.statbot.telegram.TgMessage
import com.statbot.telegram.TgUpdate
import com.statbot.util.esc
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Приём апдейтов (long polling) и маршрутизация.
 * У каждого пользователя своя очередь: его апдейты обрабатываются строго по порядку,
 * разные пользователи обрабатываются параллельно.
 */
class BotApp(
    private val tg: TelegramClient,
    private val engine: SurveyEngine,
    private val repo: Repository,
    private val ai: OpenAiClient,
    private val teacher: TeacherPanel,
    private val config: AppConfig,
    private val scope: CoroutineScope,
) {
    private val log = LoggerFactory.getLogger(BotApp::class.java)
    private val inboxes = ConcurrentHashMap<Long, Channel<TgUpdate>>()

    suspend fun runPolling() {
        var offset = 0L
        while (scope.isActive) {
            try {
                val updates = tg.getUpdates(offset)
                for (u in updates) {
                    offset = maxOf(offset, u.updateId + 1)
                    val userId = u.message?.from?.id ?: u.callbackQuery?.from?.id ?: continue
                    inbox(userId).send(u)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Ошибка получения апдейтов: {}", e.toString())
                delay(3000)
            }
        }
    }

    private fun inbox(userId: Long): Channel<TgUpdate> = inboxes.computeIfAbsent(userId) {
        Channel<TgUpdate>(Channel.UNLIMITED).also { ch ->
            scope.launch {
                for (u in ch) {
                    try {
                        engine.locked(userId) { handle(u) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log.error("Ошибка обработки апдейта {} от {}", u.updateId, userId, e)
                        runCatching { tg.sendMessage(userId, "⚠️ Что-то пошло не так. Попробуй ещё раз или нажми /start.") }
                    }
                }
            }
        }
    }

    private suspend fun handle(u: TgUpdate) {
        u.callbackQuery?.let { onCallback(it); return }
        u.message?.let { onMessage(it) }
    }

    // ---------------- Сообщения ----------------

    private suspend fun onMessage(m: TgMessage) {
        if (m.chat.type != null && m.chat.type != "private") return
        val user = m.from ?: return
        val tgId = user.id
        repo.ensureUser(tgId, user.username)

        val text = m.text?.trim()
        when {
            text != null && text.startsWith("/") -> onCommand(tgId, m, text)
            text == SurveyEngine.PROFILE_BUTTON -> engine.showProfile(tgId)
            text != null -> if (!engine.onText(tgId, text, m.messageId)) idleHint(tgId)
            m.voice != null || m.audio != null || m.videoNote != null -> onVoice(tgId, m)
            else -> tg.sendMessage(tgId, "Я понимаю текст и голосовые сообщения 🙂")
        }
    }

    private suspend fun idleHint(tgId: Long) {
        tg.sendMessage(
            tgId,
            "Сейчас нет активных вопросов — я напишу, когда придёт время следующего опроса 🙂\n\n" +
                "/survey — пройти сегодняшний опрос\n/profile — мой профиль",
        )
    }

    private suspend fun onVoice(tgId: Long, m: TgMessage) {
        if (!ai.enabled) {
            tg.sendMessage(tgId, "Распознавание голосовых сейчас недоступно — напиши, пожалуйста, текстом.")
            return
        }
        val fileId = (m.voice ?: m.audio ?: m.videoNote)!!.fileId
        val path = tg.getFile(fileId)?.filePath
        val text = try {
            if (path == null) null else ai.transcribe(tg.downloadFile(path))
        } catch (e: Exception) {
            log.warn("Ошибка распознавания голосового: {}", e.toString())
            null
        }
        if (text.isNullOrBlank()) {
            tg.sendMessage(tgId, "Не получилось распознать голосовое 😔 Попробуй ещё раз или напиши текстом.")
            return
        }
        val echoId = tg.sendMessage(tgId, "🗣 <i>Расшифровка:</i> «${text.esc()}»", html = true)
        if (!engine.onText(tgId, text, m.messageId, listOfNotNull(echoId))) idleHint(tgId)
    }

    private suspend fun onCommand(tgId: Long, m: TgMessage, raw: String) {
        val parts = raw.split(Regex("\\s+"), limit = 2)
        val command = parts[0].removePrefix("/").substringBefore('@').lowercase()
        val arg = parts.getOrNull(1)?.trim()
        val profile = repo.profile(tgId)

        when (command) {
            "start" -> when {
                profile?.role == Role.TEACHER -> teacher.showMenu(tgId)
                profile?.isOnboarded != true -> {
                    if (!engine.resume(tgId, intro = "Продолжим создание профиля 👇")) {
                        engine.startOnboarding(tgId, test = false)
                    }
                }
                else -> {
                    tg.sendMessage(
                        tgId,
                        "С возвращением, ${profile.firstName}! 👋\nВопросы приходят автоматически — я напишу, когда придёт время.",
                        ReplyKeyboard(listOf(listOf(SurveyEngine.PROFILE_BUTTON))),
                    )
                    engine.resume(tgId, intro = "У тебя есть незаконченный опрос 👇")
                }
            }
            "profile" -> engine.showProfile(tgId)
            "survey" -> engine.startDaily(tgId, manual = true)
            "teacher" ->
                if (profile?.role == Role.TEACHER) teacher.showMenu(tgId)
                else tg.sendMessage(tgId, "❌ У тебя нет доступа к панели преподавателя.")
            "iamteacher" -> {
                tg.deleteMessages(tgId, listOf(m.messageId)) // не оставляем ключ в чате
                val expected = config.teacherPassword
                if (expected == null) {
                    tg.sendMessage(tgId, "Выдача роли преподавателя отключена: на сервере не задан TEACHER_PASSWORD.")
                } else if (arg != null && MessageDigest.isEqual(arg.toByteArray(), expected.toByteArray())) {
                    repo.setRole(tgId, Role.TEACHER)
                    engine.dropSession(tgId)
                    tg.sendMessage(tgId, "✅ Роль преподавателя получена! Сюда будут приходить уведомления, если кому-то из команды нужна помощь.")
                    teacher.showMenu(tgId)
                } else {
                    tg.sendMessage(tgId, "❌ Неверный ключ авторизации.")
                }
            }
            "iamstudent" -> {
                repo.setRole(tgId, Role.STUDENT)
                tg.sendMessage(tgId, "Роль сотрудника установлена. Если профиль ещё не создан — нажми /start.")
            }
            "test" -> if (config.testCommandsEnabled) tg.sendMessage(tgId, TEST_HELP, html = true) else unknown(tgId)
            "test_stop" -> if (config.testCommandsEnabled) engine.stopTest(tgId) else unknown(tgId)
            "help" -> tg.sendMessage(tgId, HELP)
            else -> {
                if (config.testCommandsEnabled && command.startsWith("test_")) {
                    val scenario = command.removePrefix("test_")
                    val (name, dayArg) = when {
                        // /test_day 2 или /test_day2
                        scenario.startsWith("day") -> "day" to (arg ?: scenario.removePrefix("day").removePrefix("_").ifBlank { null })
                        else -> scenario to arg
                    }
                    engine.startTest(tgId, name, dayArg, m.from?.username)?.let { tg.sendMessage(tgId, it) }
                } else {
                    unknown(tgId)
                }
            }
        }
    }

    private suspend fun unknown(tgId: Long) = tg.sendMessage(tgId, "Неизвестная команда. /help — список команд.")

    // ---------------- Кнопки ----------------

    private suspend fun onCallback(cq: TgCallbackQuery) {
        val tgId = cq.from.id
        val messageId = cq.message?.messageId
        val data = cq.data
        if (messageId == null || data == null) {
            tg.answerCallback(cq.id)
            return
        }
        val parts = data.split(':')
        when (parts[0]) {
            "a" -> if (parts.size >= 3) engine.onAnswerButton(tgId, messageId, parts[1], parts.drop(2).joinToString(":"), cq.id)
            else tg.answerCallback(cq.id)
            "pl" -> engine.planning.onButton(tgId, messageId, parts.getOrElse(1) { "" }, parts.getOrNull(2), cq.id)
            "ft" -> engine.onFridayButton(tgId, messageId, parts.getOrElse(1) { "" }, parts.getOrNull(2), cq.id)
            "prof" -> engine.onProfileButton(tgId, messageId, parts.getOrElse(1) { "" }, cq.id)
            "t" -> if (teacher.isTeacher(tgId)) teacher.onButton(tgId, parts.getOrElse(1) { "" }, parts.getOrNull(2), cq.id)
            else tg.answerCallback(cq.id, "Нет доступа")
            else -> {
                // Кнопки из старой версии бота
                tg.answerCallback(cq.id, "Эта кнопка устарела")
                tg.editMarkup(tgId, messageId, null)
            }
        }
    }

    companion object {
        val HELP = """
            Команды:
            /start — начать / главное меню
            /profile — мой профиль
            /survey — пройти сегодняшний опрос
            /teacher — панель преподавателя
            /test — тестовые сценарии
        """.trimIndent()

        val TEST_HELP = """
            🧪 <b>Тестовые сценарии</b>
            Каждая команда сразу запускает нужную ветку. Ответы <b>не сохраняются</b> в постоянную базу, твой настоящий опрос (если есть) не затрагивается. Напоминание о неподтверждённом плане в тесте приходит через 1 минуту.

            /test_onboarding — старт и онбординг
            /test_plan — Сценарий 1: «Составь план на следующую неделю» (последний вопрос пятницы)
            /test_friday — вся пятница (оценка недели → созвоны → отметка задач → доп. задачи → план)
            /test_friday_noplan — пятница, если плана на неделю нет (только последний вопрос)
            /test_monday — понедельник, план из пятницы есть
            /test_monday_noplan — понедельник без плана («Составь план на эту неделю»)
            /test_day N — день 30-дневного цикла (2, 3, 4, 9, 10, 11, 16, 17, 18, 23, 24, 25, 30)
            /test_monthly — раз в месяц
            /test_quarterly — раз в 3 месяца
            /test_halfyear — раз в полгода
            /test_yearly — раз в год
            /test_stop — выйти из теста
        """.trimIndent()
    }
}
