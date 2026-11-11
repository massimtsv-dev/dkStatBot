package com.statbot.survey

import com.statbot.ai.ChatMessage
import com.statbot.db.Repository
import com.statbot.model.Profile
import com.statbot.util.gson
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

enum class SessionKind { ONBOARDING, DAILY }

enum class PlanMode { INPUT, REVIEW, EDIT_SELECT, EDIT_TEXT, ADD_TEXT, DELETE_SELECT, REWRITE, AI_ADJUST }

/** Состояние сценария «план недели». */
class PlanState(
    var isMonday: Boolean = false,
    /** Открыт из понедельничного «Нет, есть изменения» — показываем «← Назад». */
    var mondayChange: Boolean = false,
    var mode: PlanMode = PlanMode.INPUT,
    var tasks: MutableList<String> = mutableListOf(),
    var note: String = "",
    var history: MutableList<ChatMessage> = mutableListOf(),
    var reviewMessageId: Long? = null,
    var editIndex: Int = -1,
    /** Временные сообщения сессии — удаляются после сохранения плана. */
    var tempMessageIds: MutableList<Long> = mutableListOf(),
    var startedAtMillis: Long = System.currentTimeMillis(),
    var reminderSent: Boolean = false,
)

data class QA(val key: String = "", val question: String = "", val answer: String = "")

/** Состояние пользователя в текущем опросе. Сериализуется в users.session_json. */
class Session(
    var kind: SessionKind = SessionKind.DAILY,
    var date: String = "",
    var reportId: Int? = null,
    var cycleDay: Int = 0,
    var queue: MutableList<String> = mutableListOf(),
    var pos: Int = 0,
    var answers: MutableList<QA> = mutableListOf(),
    var promptMessageId: Long? = null,
    var plan: PlanState? = null,
    var friSelected: MutableSet<Int> = mutableSetOf(),
    var eveningReminderSent: Boolean = false,

    // ---- Тестовый режим: ничего не пишется в постоянную БД ----
    var test: Boolean = false,
    var testProfile: Profile? = null,
    /** Планы в тестовом режиме: weekStart(ISO) -> задачи. */
    var testPlans: MutableMap<String, MutableList<String>> = mutableMapOf(),
) {
    val currentStepId: String? get() = queue.getOrNull(pos)
}

/**
 * Хранилище активных сессий. Реальные сессии дублируются в БД (переживают перезапуск),
 * тестовые живут только в памяти и «перекрывают» реальную, не затирая её.
 */
class SessionStore(private val repo: Repository) {
    private val log = LoggerFactory.getLogger(SessionStore::class.java)
    private val real = ConcurrentHashMap<Long, Session>()
    private val test = ConcurrentHashMap<Long, Session>()

    suspend fun load() {
        repo.loadSessions().forEach { (tgId, json) ->
            runCatching { gson.fromJson(json, Session::class.java) }
                .onSuccess { s -> if (s != null && s.queue.isNotEmpty()) real[tgId] = s }
                .onFailure { log.warn("Не удалось восстановить сессию {}: {}", tgId, it.toString()) }
        }
        log.info("Восстановлено активных опросов: {}", real.size)
    }

    fun active(tgId: Long): Session? = test[tgId] ?: real[tgId]
    fun real(tgId: Long): Session? = real[tgId]
    fun allReal(): List<Pair<Long, Session>> = real.entries.map { it.key to it.value }
    fun allActive(): List<Pair<Long, Session>> = (real.keys + test.keys).mapNotNull { id -> active(id)?.let { id to it } }

    suspend fun save(tgId: Long, s: Session) {
        if (s.test) {
            test[tgId] = s
        } else {
            real[tgId] = s
            repo.saveSession(tgId, gson.toJson(s))
        }
    }

    suspend fun clear(tgId: Long, s: Session) {
        if (s.test) {
            test.remove(tgId)
        } else {
            real.remove(tgId)
            repo.saveSession(tgId, null)
        }
    }

    fun clearTest(tgId: Long): Boolean = test.remove(tgId) != null
}
