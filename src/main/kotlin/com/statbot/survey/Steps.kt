package com.statbot.survey

import com.statbot.model.Profile
import com.statbot.util.esc

/** Вариант ответа на кнопке. value попадает в callback_data, поэтому короткий и латиницей. */
data class Opt(val label: String, val value: String, val score: Double? = null)

/** Данные для подстановки в текст вопроса. */
data class PromptCtx(val profile: Profile, val weekTasks: List<String>)

sealed class Step(val id: String) {
    /** Вопрос с кнопками. followUp задаётся, если ответ входит в followUpOn. */
    class Choice(
        id: String,
        val text: (PromptCtx) -> String,
        val options: List<List<Opt>>,
        val followUp: String? = null,
        val followUpOn: Set<String> = emptySet(),
    ) : Step(id) {
        fun option(value: String) = options.flatten().firstOrNull { it.value == value }
    }

    /** Открытый вопрос: текст или голос. skip — необязательная кнопка «Пока не знаю». */
    class Open(
        id: String,
        val text: (PromptCtx) -> String,
        val skip: Opt? = null,
        val ack: String? = null,
    ) : Step(id)

    /** Шаги со своей логикой (план недели, проверка задач и т.п.). */
    class Special(id: String) : Step(id)
}

object StepIds {
    // Онбординг
    const val REG_CREATE = "REG_CREATE"
    const val REG_NAME = "REG_NAME"
    const val REG_ROLE = "REG_ROLE"
    const val REG_GOALS_Y = "REG_GOALS_Y"
    const val REG_GOALS_3M = "REG_GOALS_3M"

    // Неделя
    const val MON_CHECK = "MON_CHECK"
    const val PLAN_MON = "PLAN_M"
    const val PLAN_FRI = "PLAN_F"
    const val FRI_WEEK = "FRI_WEEK"
    const val FRI_CALLS = "FRI_CALLS"
    const val FRI_TASKS = "FRI_TASKS"
    const val FRI_MISSED = "FRI_MISSED"
    const val FRI_EXTRA = "FRI_EXTRA"
    const val FRI_EXTRA_LIST = "FRI_EXTRA_LIST"

    // Периодические
    const val MONTHLY = "MONTHLY"
    const val Q_HEALTH = "Q_HEALTH"
    const val Q_YEAR_PROGRESS = "Q_YEAR_PROG"
    const val Q_ATTENTION = "Q_ATTENTION"
    const val Q_GOALS_RESULT = "Q_GOALS_RES"
    const val Q_GOALS_SAT = "Q_GOALS_SAT"
    const val Q_GOALS_IMPROVE = "Q_GOALS_IMPR"
    const val Q_NEW_GOALS = "Q_NEW_GOALS"
    const val Q_PROJECTS = "Q_PROJECTS"
    const val Q_PROJECTS_LIST = "Q_PROJ_LIST"
    const val HALF_PROJECTS = "HALF_PROJECTS"
    const val Y_RESULT = "Y_RESULT"
    const val Y_SAT = "Y_SAT"
    const val Y_IMPROVE = "Y_IMPROVE"
    const val Y_NEXT = "Y_NEXT"
}

object Steps {
    private fun ratingRow() = listOf(
        listOf(
            Opt("🟢 9–10", "9-10", 9.5),
            Opt("🟡 7–8", "7-8", 7.5),
            Opt("🟠 4–6", "4-6", 5.0),
            Opt("🔴 1–3", "1-3", 2.0),
        )
    )

    private val LOW = setOf("4-6", "1-3")

    private fun rating(id: String, text: String, followUp: String? = null) =
        Step.Choice(id, { text }, ratingRow(), followUp, if (followUp != null) LOW else emptySet())

    private fun open(id: String, text: String, ack: String? = null) = Step.Open(id, { text }, ack = ack)

    private fun goals(text: String?) = text?.takeIf { it.isNotBlank() }?.esc() ?: "<i>(цели пока не указаны)</i>"

    private val SKIP = Opt("🤔 Пока не знаю", "skip")

    private val all: Map<String, Step> = listOf(
        // ---------- Онбординг ----------
        Step.Choice(StepIds.REG_CREATE, { "Давай создадим твой рабочий профиль" }, listOf(listOf(Opt("Создать", "go")))),
        open(StepIds.REG_NAME, "Напиши свое имя и фамилию\n<i>Пример: Иван Смирнов</i>"),
        Step.Open(StepIds.REG_ROLE, {
            "${it.profile.firstName.esc()}, чем ты занимаешься в команде?\n<i>Пример: продюсирую проекты, веду соцсети, монтирую видео</i>"
        }),
        Step.Open(
            StepIds.REG_GOALS_Y,
            { "Каких результатов ты хочешь достичь за этот год, чтобы продвинуться к своим долгосрочным целям? Можешь указать как профессиональные, так и личные цели" },
            skip = SKIP,
        ),
        Step.Open(
            StepIds.REG_GOALS_3M,
            { "Каких конкретных результатов ты хочешь достичь в ближайшие 3 месяца? Можешь указать как профессиональные, так и личные цели" },
            skip = SKIP,
        ),

        // ---------- 30-дневный цикл ----------
        rating("ENERGY", "На сколько ты энергичен(а)?"),
        rating("SPEED", "Оцени скорость выполнения задач:", followUp = "SPEED_WHY"),
        open("SPEED_WHY", "Что помешало тебе работать на максимальной скорости?"),
        Step.Choice(
            "NEW_INFO", { "Узнавал(а) ли что-то новое по своей профессии за последнее время?" },
            listOf(
                listOf(Opt("🟢 Да, ежедневно", "daily", 9.5)),
                listOf(Opt("🟡 Да, периодически", "sometimes", 6.0)),
                listOf(Opt("🔴 Нет времени", "no_time", 2.0)),
            ),
        ),
        rating("ENGAGEMENT", "Оцени уровень вовлеченности в проекты DK FILMS:", followUp = "ENGAGEMENT_WHY"),
        open("ENGAGEMENT_WHY", "Что могло бы помочь повысить твою вовлеченность в проекты?"),
        rating("INITIATIVE", "Оцени свою инициативность:"),
        rating("PLANNING_Q", "Оцени качество своего планирования задач на неделю:"),
        rating("NOTES_Q", "Насколько качественно фиксируешь задачи после созвона?"),
        rating("CALL_ENG", "Оцени свою включенность на созвонах:"),
        rating("WORKLOAD", "Насколько тебе комфортна рабочая нагрузка?"),
        rating("EMOTIONAL", "Оцени свой эмоциональный настрой:"),
        rating("CALL_VALUE", "Как ты оцениваешь ценность созвонов для себя?"),
        Step.Choice(
            "EXPERT", { "Оцени свой уровень как специалиста в своей сфере, где:\n1 — начинающий;\n5 — уверенный специалист;\n10 — эксперт" },
            listOf(
                (1..5).map { Opt("$it", "$it", it.toDouble()) },
                (6..10).map { Opt("$it", "$it", it.toDouble()) },
            ),
        ),
        Step.Choice(
            "UNFINISHED", { "Сколько у тебя сейчас есть начатых, но недоделанных задач или проектов?" },
            listOf(listOf(Opt("🟢 0–1", "0-1"), Opt("🟡 2", "2"), Opt("🟠 3–4", "3-4"), Opt("🔴 5+", "5+"))),
        ),
        rating("AUTONOMY", "Насколько самостоятельно ты справляешься с задачами?"),
        rating("HAPPINESS", "На сколько ты счастлив(а) в компании?"),
        rating("HARD_TASKS", "Насколько легко тебе даются сложные задачи, даже когда не хочется их делать?"),
        Step.Choice(
            "HOURS", { "Сколько часов в среднем в день ты работал(а)?" },
            listOf(listOf(Opt("🕐 0–1", "0-1"), Opt("🕑 2–3", "2-3"), Opt("🕓 4–6", "4-6"), Opt("🕖 7+", "7+"))),
        ),
        rating("POTENTIAL", "Насколько удалось реализовать свой потенциал в этом месяце?"),

        // ---------- Понедельник / пятница ----------
        Step.Special(StepIds.MON_CHECK),
        Step.Special(StepIds.PLAN_MON),
        Step.Special(StepIds.PLAN_FRI),
        rating(StepIds.FRI_WEEK, "Как прошла рабочая неделя?"),
        Step.Choice(
            StepIds.FRI_CALLS, { "Сколько дней ты был(а) на созвонах за эту неделю?" },
            listOf((1..5).map { Opt("$it", "$it", it.toDouble()) }),
        ),
        Step.Special(StepIds.FRI_TASKS),
        open(StepIds.FRI_MISSED, "Расскажи, почему не получилось выполнить все задачи?", ack = "Принято 👌"),
        Step.Choice(
            StepIds.FRI_EXTRA, { "Были ли у тебя дополнительные задачи помимо запланированных?" },
            listOf(listOf(Opt("➕ Да, были", "yes"), Opt("➖ Нет, не было", "no"))),
            followUp = StepIds.FRI_EXTRA_LIST, followUpOn = setOf("yes"),
        ),
        open(StepIds.FRI_EXTRA_LIST, "Какие именно?"),

        // ---------- Раз в месяц / квартал / полгода / год ----------
        Step.Open(StepIds.MONTHLY, {
            "Твои цели на 3 месяца:\n${goals(it.profile.goals3Months)}\n\nЧто ты сделал(а) за этот месяц, чтобы приблизиться к каждой из этих целей?"
        }),
        rating(StepIds.Q_HEALTH, "Как бы ты оценил(а) свое общее состояние своего здоровья?"),
        Step.Open(StepIds.Q_YEAR_PROGRESS, {
            "Твои цели на год:\n${goals(it.profile.goalsYear)}\n\nЧто ты сделал(а) за последние 3 месяца, чтобы приблизиться к каждой из этих целей?"
        }),
        rating(StepIds.Q_ATTENTION, "Насколько ты чувствуешь, что компания уделяет тебе время, внимание и ресурсы для твоего развития?"),
        Step.Open(StepIds.Q_GOALS_RESULT, {
            "3 месяца назад ты поставил(а) себе цели:\n${goals(it.profile.goals3Months)}\n\nКакой результат получился по каждой из них?"
        }),
        rating(StepIds.Q_GOALS_SAT, "Насколько ты доволен(на) полученным результатом?", followUp = StepIds.Q_GOALS_IMPROVE),
        open(StepIds.Q_GOALS_IMPROVE, "Как ты думаешь, что можно было бы изменить в своих действиях или подходе, чтобы в следующий раз добиться большего?"),
        open(StepIds.Q_NEW_GOALS, "Каких конкретных результатов ты хочешь достичь за следующие 3 месяца?"),
        Step.Choice(
            StepIds.Q_PROJECTS, { "Появились ли за последние 3 месяца новые завершенные проекты, которые обсуждались на созвонах?" },
            listOf(listOf(Opt("➕ Да, появились", "yes"), Opt("➖ Нет, не появились", "no"))),
            followUp = StepIds.Q_PROJECTS_LIST, followUpOn = setOf("yes"),
        ),
        open(StepIds.Q_PROJECTS_LIST, "Какие проекты добавились?"),
        open(
            StepIds.HALF_PROJECTS,
            "Сколько завершенных проектов в твоём портфолио выполнены на мировом уровне? Укажи количество и перечисли проекты, которые обсуждались на созвонах",
        ),
        Step.Open(StepIds.Y_RESULT, {
            val p = it.profile
            val initial = p.goalsYearInitial ?: p.goalsYear
            val changed = !p.goalsYear.isNullOrBlank() && !initial.isNullOrBlank() && p.goalsYear.trim() != initial.trim()
            buildString {
                append("Год назад ты поставил(а) перед собой следующие цели:\n${goals(initial)}")
                if (changed) append("\n\nВ течение года ты корректировал(а) их. Последняя версия целей выглядит так:\n${goals(p.goalsYear)}")
                append("\n\nКаких результатов удалось достичь по этим целям?")
            }
        }),
        rating(StepIds.Y_SAT, "Насколько ты доволен(на) полученным результатом?", followUp = StepIds.Y_IMPROVE),
        open(StepIds.Y_IMPROVE, "Как ты думаешь, что можно было бы изменить в своих действиях или подходе, чтобы в следующий раз добиться большего?"),
        open(StepIds.Y_NEXT, "Каких главных результатов ты хочешь достичь за следующий год? Опиши свои цели в работе, профессиональном развитии и проектах"),
    ).associateBy { it.id }

    operator fun get(id: String): Step = all[id] ?: error("Неизвестный шаг $id")
    fun exists(id: String) = id in all

    /** Короткое название шага для отчётов преподавателю. */
    fun questionText(id: String, profile: Profile): String = when (val s = all[id]) {
        is Step.Choice -> s.text(PromptCtx(profile, emptyList()))
        is Step.Open -> s.text(PromptCtx(profile, emptyList()))
        else -> when (id) {
            StepIds.MON_CHECK -> "Актуален ли план на неделю?"
            StepIds.FRI_TASKS -> "Выполнение задач недели"
            else -> id
        }
    }.replace(Regex("<[^>]+>"), "")
}
