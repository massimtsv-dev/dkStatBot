package com.statbot.survey

import com.statbot.ai.PlanAssistant
import com.statbot.ai.ReportAnalyst
import com.statbot.config.AppConfig
import com.statbot.db.Repository
import com.statbot.model.Profile
import com.statbot.model.ProfilePatch
import com.statbot.model.Role
import com.statbot.telegram.Button
import com.statbot.telegram.InlineKeyboard
import com.statbot.telegram.Markup
import com.statbot.telegram.ReplyKeyboard
import com.statbot.telegram.TelegramClient
import com.statbot.util.esc
import com.statbot.util.numbered
import com.statbot.util.weekMonday
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

/**
 * Сценарии опросов: онбординг, ежедневные вопросы, понедельник/пятница, периодические вопросы, тестовый режим.
 * Все обращения к одному пользователю сериализуются через [locked].
 */
class SurveyEngine(
    internal val tg: TelegramClient,
    internal val repo: Repository,
    internal val store: SessionStore,
    planner: PlanAssistant,
    private val analyst: ReportAnalyst,
    internal val config: AppConfig,
    private val scope: CoroutineScope,
    private val aiEnabled: Boolean,
) {
    private val log = LoggerFactory.getLogger(SurveyEngine::class.java)
    private val locks = ConcurrentHashMap<Long, Mutex>()
    private val pendingEdits = ConcurrentHashMap<Long, String>()
    internal val planning = PlanningFlow(this, planner, aiEnabled)

    suspend fun <T> locked(tgId: Long, block: suspend () -> T): T =
        locks.computeIfAbsent(tgId) { Mutex() }.withLock { block() }

    fun today(): LocalDate = config.debugDate ?: LocalDate.now(config.zone)

    // ======================= Вспомогательное =======================

    internal suspend fun send(chatId: Long, text: String, markup: Markup? = null): Long? =
        tg.sendMessage(chatId, text, markup, html = true)

    internal suspend fun profileOf(tgId: Long, s: Session?): Profile =
        s?.takeIf { it.test }?.testProfile ?: repo.profile(tgId) ?: Profile(tgId)

    private suspend fun patchProfile(tgId: Long, s: Session, patch: ProfilePatch) {
        if (s.test) s.testProfile = patch.applyTo(s.testProfile ?: Profile(tgId))
        else repo.updateProfile(tgId, patch)
    }

    internal suspend fun planFor(tgId: Long, s: Session?, weekStart: LocalDate): List<String> =
        if (s?.test == true) s.testPlans[weekStart.toString()].orEmpty()
        else repo.planFor(tgId, weekStart).orEmpty()

    internal suspend fun savePlan(tgId: Long, s: Session, weekStart: LocalDate, tasks: List<String>) {
        if (s.test) s.testPlans[weekStart.toString()] = tasks.toMutableList()
        else repo.savePlan(tgId, weekStart, tasks)
    }

    internal fun sessionDate(s: Session): LocalDate = runCatching { LocalDate.parse(s.date) }.getOrElse { today() }

    internal suspend fun record(s: Session, key: String, question: String, answer: String, score: Double? = null) {
        s.answers += QA(key, question, answer)
        val reportId = s.reportId
        if (!s.test && reportId != null && s.kind == SessionKind.DAILY) {
            repo.saveAnswer(reportId, key, question, answer, score)
        }
    }

    private fun plain(html: String) = html.replace(Regex("<[^>]+>"), "")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")

    private fun profileKeyboard() = ReplyKeyboard(listOf(listOf(PROFILE_BUTTON)))

    // ======================= Онбординг =======================

    suspend fun startOnboarding(tgId: Long, test: Boolean, username: String? = null) {
        val s = Session(
            kind = SessionKind.ONBOARDING,
            date = today().toString(),
            queue = mutableListOf(StepIds.REG_CREATE, StepIds.REG_NAME, StepIds.REG_ROLE, StepIds.REG_GOALS_Y, StepIds.REG_GOALS_3M),
            test = test,
            testProfile = if (test) Profile(tgId, username = username) else null,
        )
        if (test) send(tgId, "🧪 <b>Тестовый онбординг.</b> Данные не попадут в базу. Выйти: /test_stop")
        send(tgId, WELCOME)
        store.save(tgId, s)
        delay(2000) // «через пару секунд» по ТЗ
        askCurrent(tgId, s)
    }

    /** Повторно показать текущий вопрос активной сессии. */
    suspend fun resume(tgId: Long, intro: String? = "Продолжим 👇"): Boolean {
        val s = store.active(tgId) ?: return false
        intro?.let { send(tgId, it) }
        reask(tgId, s)
        return true
    }

    internal suspend fun reask(tgId: Long, s: Session) {
        // Убираем кнопки у старой копии вопроса, чтобы актуальной была только новая
        s.promptMessageId?.let { tg.editMarkup(tgId, it, null) }
        val plan = s.plan
        if (plan != null) planning.resend(tgId, s, plan) else askCurrent(tgId, s)
    }

    // ======================= Ежедневный опрос =======================

    /**
     * Запуск сегодняшнего опроса. manual=true — пользователь сам вызвал /survey (отвечаем на любые ситуации),
     * manual=false — рассылка по расписанию (молча пропускаем, если опрос уже был).
     */
    suspend fun startDaily(tgId: Long, manual: Boolean) {
        val profile = repo.profile(tgId) ?: return
        if (profile.role == Role.TEACHER) {
            if (manual) send(tgId, "Опросы приходят только сотрудникам. У тебя роль преподавателя — открой /teacher.")
            return
        }
        if (!profile.isOnboarded) {
            if (manual && !resume(tgId)) startOnboarding(tgId, test = false)
            return
        }
        val today = today()
        store.real(tgId)?.let { existing ->
            if (existing.kind == SessionKind.DAILY && existing.date == today.toString()) {
                if (manual) resume(tgId)
                return
            }
            if (existing.kind == SessionKind.ONBOARDING) return
            // Вчерашний (или более старый) опрос так и не закончен
            existing.reportId?.let { repo.markIgnored(it) }
            existing.plan?.let { tg.deleteMessages(tgId, it.tempMessageIds) }
            store.clear(tgId, existing)
        }

        val report = repo.reportFor(tgId, today)
        if (report != null && (report.completed || !manual)) {
            if (manual) send(tgId, "Сегодня ты уже ответил(а) на все вопросы. Спасибо! 👍")
            return
        }

        val hasPlan = repo.planFor(tgId, today.weekMonday()) != null
        val (day, steps) = Schedule.stepsFor(today, profile, hasPlan)
        if (steps.isEmpty()) {
            if (manual) send(tgId, "Сегодня нет запланированных вопросов. Хорошего дня! 🚀")
            return
        }
        val reportId = report?.id ?: repo.createReport(tgId, today, day)
        val s = Session(kind = SessionKind.DAILY, date = today.toString(), reportId = reportId, cycleDay = day, queue = steps.toMutableList())
        store.save(tgId, s)
        askCurrent(tgId, s)
    }

    // ======================= Показ шага =======================

    internal suspend fun askCurrent(tgId: Long, s: Session) {
        val id = s.currentStepId
        if (id == null) {
            finish(tgId, s)
            return
        }
        val profile = profileOf(tgId, s)
        val ctx = PromptCtx(profile, planFor(tgId, s, sessionDate(s).weekMonday()))
        when (val step = Steps[id]) {
            is Step.Choice -> {
                val kb = InlineKeyboard(step.options.map { row -> row.map { Button(it.label, "a:${step.id}:${it.value}") } })
                s.promptMessageId = send(tgId, step.text(ctx), kb)
            }
            is Step.Open -> {
                val kb = step.skip?.let { InlineKeyboard.of(listOf(Button(it.label, "a:${step.id}:${it.value}"))) }
                s.promptMessageId = send(tgId, step.text(ctx), kb)
            }
            is Step.Special -> when (id) {
                StepIds.MON_CHECK -> askMondayCheck(tgId, s)
                StepIds.PLAN_MON -> planning.start(tgId, s, isMonday = true)
                StepIds.PLAN_FRI -> planning.start(tgId, s, isMonday = false)
                StepIds.FRI_TASKS -> askFridayTasks(tgId, s)
                else -> error("Нет обработчика для $id")
            }
        }
        store.save(tgId, s)
    }

    internal suspend fun advance(tgId: Long, s: Session) {
        s.pos++
        s.promptMessageId = null
        askCurrent(tgId, s)
    }

    // ======================= Входящие ответы =======================

    /** Нажатие кнопки ответа «a:<шаг>:<значение>». */
    suspend fun onAnswerButton(tgId: Long, messageId: Long, stepId: String, value: String, callbackId: String) {
        val s = store.active(tgId)
        if (s == null || s.currentStepId != stepId || s.plan != null || !Steps.exists(stepId)) {
            tg.answerCallback(callbackId, "Этот вопрос уже неактуален")
            tg.editMarkup(tgId, messageId, null)
            return
        }
        tg.answerCallback(callbackId)
        val profile = profileOf(tgId, s)
        val ctx = PromptCtx(profile, planFor(tgId, s, sessionDate(s).weekMonday()))
        when (val step = Steps[stepId]) {
            is Step.Choice -> {
                val opt = step.option(value) ?: return
                tg.editMessageText(tgId, messageId, step.text(ctx) + "\n\n<b>${opt.label.esc()}</b>", html = true)
                onAnswer(tgId, s, step, opt.value, opt.label, opt.score)
            }
            is Step.Open -> {
                val skip = step.skip?.takeIf { it.value == value } ?: return
                tg.editMessageText(tgId, messageId, step.text(ctx) + "\n\n<b>${skip.label.esc()}</b>", html = true)
                onAnswer(tgId, s, step, SKIP_VALUE, skip.label, null)
            }
            is Step.Special -> if (stepId == StepIds.MON_CHECK) onMondayCheck(tgId, s, messageId, value)
        }
    }

    /** Текст (или расшифровка голосового). @return false, если сообщение некуда отнести. */
    suspend fun onText(tgId: Long, text: String, messageId: Long, extraMessageIds: List<Long> = emptyList()): Boolean {
        pendingEdits[tgId]?.let { field ->
            applyProfileEdit(tgId, field, text)
            return true
        }
        val s = store.active(tgId) ?: return false
        val plan = s.plan
        if (plan != null) {
            plan.tempMessageIds += extraMessageIds
            planning.onText(tgId, s, plan, text, messageId)
            return true
        }
        val id = s.currentStepId ?: return false
        when (val step = Steps[id]) {
            is Step.Open -> {
                if (step.skip != null) s.promptMessageId?.let { tg.editMarkup(tgId, it, null) }
                onAnswer(tgId, s, step, text.trim(), text.trim(), null)
            }
            is Step.Choice -> send(tgId, "Пожалуйста, выбери вариант ответа кнопкой выше 👆")
            is Step.Special -> send(
                tgId,
                if (id == StepIds.FRI_TASKS) "Отметь выполненные задачи кнопками выше и нажми «💾 Сохранить» 👆"
                else "Пожалуйста, выбери вариант ответа кнопкой выше 👆",
            )
        }
        return true
    }

    private suspend fun onAnswer(tgId: Long, s: Session, step: Step, value: String, label: String, score: Double?) {
        val profile = profileOf(tgId, s)
        val questionText = plain(
            when (step) {
                is Step.Choice -> step.text(PromptCtx(profile, emptyList()))
                is Step.Open -> step.text(PromptCtx(profile, emptyList()))
                is Step.Special -> step.id
            }
        )
        val skipped = value == SKIP_VALUE

        when (step.id) {
            StepIds.REG_CREATE -> {}
            StepIds.REG_NAME -> patchProfile(tgId, s, ProfilePatch(fullName = value.take(100)))
            StepIds.REG_ROLE -> patchProfile(tgId, s, ProfilePatch(project = value.take(200)))
            StepIds.REG_GOALS_Y ->
                if (skipped) send(tgId, "Ничего страшного! Цель на год можно добавить позже")
                else patchProfile(tgId, s, ProfilePatch(goalsYear = value, goalsYearInitial = value))
            StepIds.REG_GOALS_3M ->
                if (skipped) send(tgId, "Ничего страшного! Цель на 3 месяца можно добавить позже")
                else patchProfile(tgId, s, ProfilePatch(goals3Months = value))
            StepIds.Q_NEW_GOALS -> patchProfile(tgId, s, ProfilePatch(goals3Months = value))
            StepIds.Y_NEXT -> patchProfile(tgId, s, ProfilePatch(goalsYear = value, goalsYearInitial = value))
        }

        if (s.kind == SessionKind.DAILY) record(s, step.id, questionText, label, score)

        if (step is Step.Choice && step.followUp != null && value in step.followUpOn) {
            s.queue.add(s.pos + 1, step.followUp)
        }
        if (step is Step.Open) step.ack?.let { send(tgId, it) }
        advance(tgId, s)
    }

    // ======================= Понедельник =======================

    private suspend fun askMondayCheck(tgId: Long, s: Session) {
        val tasks = planFor(tgId, s, sessionDate(s).weekMonday())
        if (tasks.isEmpty()) {
            // План в пятницу не составлен — сразу «Составь план на эту неделю»
            s.queue[s.pos] = StepIds.PLAN_MON
            askCurrent(tgId, s)
            return
        }
        val kb = InlineKeyboard.of(
            listOf(Button("✅ Да, все актуально", "a:${StepIds.MON_CHECK}:yes")),
            listOf(Button("✏️ Нет, есть изменения", "a:${StepIds.MON_CHECK}:no")),
        )
        s.promptMessageId = send(tgId, mondayCheckText(tasks), kb)
    }

    private fun mondayCheckText(tasks: List<String>) =
        "В пятницу ты запланировал(а) такие задачи на текущую неделю:\n\n${numbered(tasks)}\n\nВсе ли актуально?"

    private suspend fun onMondayCheck(tgId: Long, s: Session, messageId: Long, value: String) {
        val tasks = planFor(tgId, s, sessionDate(s).weekMonday())
        val label = if (value == "yes") "✅ Да, все актуально" else "✏️ Нет, есть изменения"
        tg.editMessageText(tgId, messageId, mondayCheckText(tasks) + "\n\n<b>$label</b>", html = true)
        if (value == "yes") {
            record(s, StepIds.MON_CHECK, "Актуален ли план на неделю?", "Да, все актуально")
            advance(tgId, s)
        } else {
            record(s, StepIds.MON_CHECK, "Актуален ли план на неделю?", "Нет, есть изменения")
            planning.openMondayChange(tgId, s, tasks)
            store.save(tgId, s)
        }
    }

    /** «← Назад» из меню изменений понедельника. */
    internal suspend fun backToMondayCheck(tgId: Long, s: Session) {
        s.plan = null
        askCurrent(tgId, s)
    }

    // ======================= Пятница: отметка задач =======================

    private suspend fun askFridayTasks(tgId: Long, s: Session) {
        val tasks = planFor(tgId, s, sessionDate(s).weekMonday())
        if (tasks.isEmpty()) {
            advance(tgId, s)
            return
        }
        s.friSelected.clear()
        s.promptMessageId = send(tgId, FRI_TASKS_TEXT, fridayKeyboard(tasks, s.friSelected))
    }

    private fun fridayKeyboard(tasks: List<String>, selected: Set<Int>): InlineKeyboard {
        val rows = tasks.mapIndexed { i, t ->
            val short = if (t.length > 50) t.take(48) + "…" else t
            listOf(Button("${if (i in selected) "☑️" else "☐"} $short", "ft:t:$i"))
        } + listOf(listOf(Button("💾 Сохранить", "ft:save")))
        return InlineKeyboard(rows)
    }

    suspend fun onFridayButton(tgId: Long, messageId: Long, action: String, arg: String?, callbackId: String) {
        val s = store.active(tgId)
        if (s == null || s.currentStepId != StepIds.FRI_TASKS || s.promptMessageId != messageId) {
            tg.answerCallback(callbackId, "Этот вопрос уже неактуален")
            tg.editMarkup(tgId, messageId, null)
            return
        }
        val tasks = planFor(tgId, s, sessionDate(s).weekMonday())
        when (action) {
            "t" -> {
                tg.answerCallback(callbackId)
                val i = arg?.toIntOrNull() ?: return
                if (i !in tasks.indices) return
                if (!s.friSelected.add(i)) s.friSelected.remove(i)
                store.save(tgId, s)
                tg.editMarkup(tgId, messageId, fridayKeyboard(tasks, s.friSelected))
            }
            "save" -> {
                tg.answerCallback(callbackId)
                val done = tasks.filterIndexed { i, _ -> i in s.friSelected }
                val missed = tasks.filterIndexed { i, _ -> i !in s.friSelected }
                val pct = if (tasks.isEmpty()) 0 else done.size * 100 / tasks.size
                val summary = tasks.mapIndexed { i, t -> "${if (i in s.friSelected) "☑️" else "☐"} ${t.esc()}" }.joinToString("\n")
                tg.editMessageText(tgId, messageId, "$FRI_TASKS_TEXT\n\n$summary", html = true)
                record(
                    s, StepIds.FRI_TASKS, "Выполнение задач недели",
                    "Выполнено ${done.size} из ${tasks.size} ($pct%). Выполнены: ${done.joinToString("; ").ifEmpty { "—" }}. Не выполнены: ${missed.joinToString("; ").ifEmpty { "—" }}",
                    pct.toDouble(),
                )
                if (missed.isEmpty()) {
                    send(tgId, "Ты выполнил(а) 100% от своего плана. Отличная работа! 🎉")
                } else {
                    s.queue.add(s.pos + 1, StepIds.FRI_MISSED)
                }
                advance(tgId, s)
            }
            else -> tg.answerCallback(callbackId)
        }
    }

    // ======================= Завершение =======================

    internal suspend fun finish(tgId: Long, s: Session) {
        store.clear(tgId, s)
        when (s.kind) {
            SessionKind.ONBOARDING -> {
                val today = today()
                patchProfile(tgId, s, ProfilePatch(onboardedAt = today, cycleStart = Schedule.cycleStartFor(today)))
                send(
                    tgId,
                    "Принято. Все данные можно будет изменить в любое время в разделе «$PROFILE_BUTTON»\n\n" +
                        "Если что-то в работе бота неудобно, не нравится или у тебя есть предложения — обязательно напиши ${config.feedbackContact.esc()}. " +
                        "Это поможет сделать его удобнее для тебя.\n\nДо скорого! 🎬",
                    profileKeyboard(),
                )
                if (s.test) send(tgId, "🧪 Тестовый онбординг завершён. В базу ничего не записано.")
            }
            SessionKind.DAILY -> {
                // Если опрос закончился сохранением плана — финальное сообщение уже отправлено
                val endedWithPlan = s.answers.lastOrNull()?.key == PLAN_KEY
                if (!endedWithPlan) send(tgId, "Спасибо! Ответы приняты ✨")
                if (!s.test) s.reportId?.let { repo.markCompleted(it) }
                if (s.test) send(tgId, "🧪 Тест завершён. В базу ничего не записано.")
                analyzeLater(tgId, s)
            }
        }
    }

    private fun analyzeLater(tgId: Long, s: Session) {
        val qa = s.answers.filter { it.key != StepIds.REG_CREATE }
        if (!aiEnabled || qa.isEmpty()) return
        scope.launch {
            val profile = s.testProfile ?: repo.profile(tgId) ?: Profile(tgId)
            val verdict = analyst.analyze(profile.displayName, profile.project ?: "—", qa.map { it.question to it.answer }) ?: return@launch
            if (s.test) {
                send(
                    tgId,
                    if (verdict.flagged) "🧪 <b>[ТЕСТ] ИИ поднял бы тревогу</b> (преподавателям не отправлено):\n\n${verdict.summary.esc()}"
                    else "🧪 [ТЕСТ] ИИ-анализ: тревожных признаков нет.",
                )
                return@launch
            }
            if (!verdict.flagged) return@launch
            s.reportId?.let { repo.saveAlert(it, verdict.summary) }
            val username = profile.username?.let { " (@${it.esc()})" }.orEmpty()
            val text = "🚨 <b>Сотруднику может понадобиться помощь</b>\n\n" +
                "👤 ${profile.displayName.esc()}$username\n🎬 ${(profile.project ?: "—").esc()}\n📅 ${s.date}\n\n${verdict.summary.esc()}"
            repo.teacherIds().forEach { send(it, text) }
            log.info("AI-алерт по пользователю {}", tgId)
        }
    }

    // ======================= Напоминания =======================

    /** Напоминание о неподтверждённом плане (по ТЗ — одно, через час; в тестах — через минуту). */
    suspend fun checkPlanReminders() {
        val now = System.currentTimeMillis()
        store.allActive().forEach { (tgId, _) ->
            locked(tgId) {
                val s = store.active(tgId) ?: return@locked
                val plan = s.plan ?: return@locked
                val minutes = if (s.test) 1 else config.planReminderMinutes
                if (!plan.reminderSent && now - plan.startedAtMillis >= minutes * 60_000) {
                    plan.reminderSent = true
                    planning.sendReminder(tgId, plan)
                    store.save(tgId, s)
                }
            }
        }
    }

    /** Вечернее напоминание тем, кто не закончил сегодняшний опрос. */
    suspend fun eveningReminders() {
        val today = today().toString()
        store.allReal().forEach { (tgId, _) ->
            locked(tgId) {
                val s = store.real(tgId) ?: return@locked
                if (s.kind != SessionKind.DAILY || s.date != today || s.eveningReminderSent || s.plan != null) return@locked
                s.eveningReminderSent = true
                store.save(tgId, s)
                if (store.active(tgId)?.test == true) return@locked
                send(tgId, "🔔 Ежедневный отчёт\nЕсли ещё не ответил(а) на сегодняшние вопросы — самое время, это займёт пару минут 🙂")
                askCurrent(tgId, s)
            }
        }
    }

    // ======================= Профиль =======================

    suspend fun showProfile(tgId: Long) {
        pendingEdits.remove(tgId)
        val p = repo.profile(tgId)
        if (p == null || !p.isOnboarded) {
            send(tgId, "Профиль ещё не создан. Нажми /start, чтобы его создать.")
            return
        }
        fun v(x: String?) = x?.takeIf { it.isNotBlank() }?.esc() ?: "<i>не указано</i>"
        val text = "👤 <b>Твой рабочий профиль</b>\n\n" +
            "<b>Имя и фамилия:</b> ${v(p.fullName)}\n" +
            "<b>Чем занимаешься в команде:</b> ${v(p.project)}\n" +
            "<b>Цели на год:</b> ${v(p.goalsYear)}\n" +
            "<b>Цели на 3 месяца:</b> ${v(p.goals3Months)}\n\n" +
            "Что хочешь изменить?"
        val kb = InlineKeyboard.of(
            listOf(Button("✏️ Имя", "prof:name"), Button("✏️ Чем занимаюсь", "prof:proj")),
            listOf(Button("✏️ Цели на год", "prof:gy"), Button("✏️ Цели на 3 мес.", "prof:g3")),
            listOf(Button("✖️ Закрыть", "prof:close")),
        )
        send(tgId, text, kb)
    }

    suspend fun onProfileButton(tgId: Long, messageId: Long, action: String, callbackId: String) {
        tg.answerCallback(callbackId)
        val prompt = when (action) {
            "name" -> "Введи новые имя и фамилию:"
            "proj" -> "Опиши, чем ты занимаешься в команде:"
            "gy" -> "Напиши свои цели на год:"
            "g3" -> "Напиши свои цели на 3 месяца:"
            "close" -> { tg.deleteMessages(tgId, listOf(messageId)); pendingEdits.remove(tgId); return }
            "cancel" -> { pendingEdits.remove(tgId); tg.editMessageText(tgId, messageId, "Изменение отменено."); return }
            else -> return
        }
        tg.editMarkup(tgId, messageId, null)
        pendingEdits[tgId] = action
        send(tgId, prompt, InlineKeyboard.of(listOf(Button("Отмена", "prof:cancel"))))
    }

    private suspend fun applyProfileEdit(tgId: Long, field: String, text: String) {
        pendingEdits.remove(tgId)
        val value = text.trim()
        val patch = when (field) {
            "name" -> ProfilePatch(fullName = value.take(100))
            "proj" -> ProfilePatch(project = value.take(200))
            "gy" -> ProfilePatch(goalsYear = value)
            "g3" -> ProfilePatch(goals3Months = value)
            else -> return
        }
        repo.updateProfile(tgId, patch)
        send(tgId, "✅ Сохранено")
        showProfile(tgId)
        // Если пользователь был посреди опроса — вернём его к текущему вопросу
        store.active(tgId)?.let { resume(tgId, intro = "Возвращаемся к опросу 👇") }
    }

    // ======================= Тестовый режим =======================

    /**
     * Запуск сценария в тестовом режиме — сразу с нужного места, без записи в постоянную БД.
     * @return текст ошибки или null.
     */
    suspend fun startTest(tgId: Long, scenario: String, arg: String?, username: String?): String? {
        if (scenario == "onboarding") {
            store.clearTest(tgId)
            startOnboarding(tgId, test = true, username = username)
            return null
        }
        val today = today()
        val base = repo.profile(tgId)
        val profile = (base ?: Profile(tgId)).copy(
            role = Role.STUDENT,
            fullName = base?.fullName ?: "Тестовый Пользователь",
            project = base?.project ?: "тестирую бота",
            goalsYear = base?.goalsYear ?: "Выпустить два собственных проекта",
            goalsYearInitial = "Выпустить один собственный проект",
            goals3Months = base?.goals3Months ?: "Собрать шоурил и обновить портфолио",
            onboardedAt = today.minusYears(1),
            cycleStart = today.minusYears(1),
        )
        val thisWeek = today.weekMonday().toString()
        val sample = repo.planFor(tgId, today.weekMonday())?.takeIf { it.isNotEmpty() } ?: SAMPLE_TASKS

        val (title, queue, plans) = when (scenario) {
            "plan" -> Triple("Сценарий 1 — план на следующую неделю (последний вопрос пятницы)", listOf(StepIds.PLAN_FRI), emptyMap())
            "monday" -> Triple("Понедельник — план из пятницы есть", listOf(StepIds.MON_CHECK), mapOf(thisWeek to sample))
            "monday_noplan" -> Triple("Понедельник — плана нет («Составь план на эту неделю»)", listOf(StepIds.MON_CHECK), emptyMap())
            "friday" -> Triple("Пятница — полный блок", Schedule.FRIDAY, mapOf(thisWeek to sample))
            "friday_noplan" -> Triple("Пятница без плана на неделю — только последний вопрос", listOf(StepIds.PLAN_FRI), emptyMap())
            "day" -> {
                val day = arg?.toIntOrNull()
                if (day == null || day !in Schedule.CYCLE_DAYS) {
                    return "Укажи день цикла: ${Schedule.CYCLE_DAYS.sorted().joinToString(", ")}\nНапример: /test_day 2"
                }
                Triple("День $day 30-дневного цикла", Schedule.stepsForCycleDay(day), emptyMap())
            }
            "monthly" -> Triple("Ежемесячный вопрос", listOf(StepIds.MONTHLY), emptyMap())
            "quarterly" -> Triple("Вопросы раз в 3 месяца", Schedule.QUARTERLY, emptyMap())
            "halfyear" -> Triple("Вопрос раз в полгода", listOf(StepIds.HALF_PROJECTS), emptyMap())
            "yearly" -> Triple("Вопросы раз в год", Schedule.YEARLY, emptyMap())
            else -> return "Неизвестный сценарий. Список: /test"
        }

        store.clearTest(tgId)
        val s = Session(
            kind = SessionKind.DAILY,
            date = today.toString(),
            queue = queue.toMutableList(),
            test = true,
            testProfile = profile,
            testPlans = plans.mapValues { it.value.toMutableList() }.toMutableMap(),
        )
        send(tgId, "🧪 <b>Тест: ${title.esc()}</b>\nОтветы не сохраняются в базу. Выйти: /test_stop")
        store.save(tgId, s)
        askCurrent(tgId, s)
        return null
    }

    /** Сброс незаконченной сессии (например, при получении роли преподавателя). */
    suspend fun dropSession(tgId: Long) {
        store.real(tgId)?.let { s ->
            s.plan?.let { tg.deleteMessages(tgId, it.tempMessageIds) }
            s.reportId?.let { repo.markIgnored(it) }
            store.clear(tgId, s)
        }
        store.clearTest(tgId)
    }

    suspend fun stopTest(tgId: Long) {
        val s = store.active(tgId)
        if (s?.test == true) {
            s.plan?.let { tg.deleteMessages(tgId, it.tempMessageIds) }
            store.clearTest(tgId)
            send(tgId, "🧪 Тестовый режим выключен.")
            store.real(tgId)?.let { resume(tgId, intro = "У тебя есть незаконченный опрос, продолжим 👇") }
        } else {
            send(tgId, "Тестовый режим и так выключен.")
        }
    }

    companion object {
        const val PROFILE_BUTTON = "👤 Мой профиль"
        internal const val SKIP_VALUE = "skip"
        internal const val PLAN_KEY = "PLAN"
        private const val FRI_TASKS_TEXT = "Отметь, какие поставленные задачи на эту неделю ты выполнил(а):"

        private val SAMPLE_TASKS = listOf(
            "Смонтировать черновую версию ролика для соцсетей",
            "Согласовать сценарий нового проекта",
            "Закрыть накопившиеся ошибки в текущей версии бота",
        )

        val WELCOME = """
            Привет!👋
            Я — твой личный помощник в DK FILMS
            Помогу тебе организовать рабочую неделю и быть в курсе своих задач и результатов.

            В течение дня я буду присылать тебе сообщения и напоминания, которые помогут держать рабочий настрой и фокус на важных задачах. Поэтому <b>не отключай уведомления</b>.

            🎙️ Со мной можно общаться текстом или голосовыми сообщениями — как тебе удобнее.
        """.trimIndent()
    }
}
