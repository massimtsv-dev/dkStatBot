package com.statbot.model

import java.time.LocalDate

enum class Role { STUDENT, TEACHER }

data class Profile(
    val tgId: Long,
    val role: Role = Role.STUDENT,
    val username: String? = null,
    val fullName: String? = null,
    val project: String? = null,
    val goalsYear: String? = null,
    val goalsYearInitial: String? = null,
    val goals3Months: String? = null,
    val onboardedAt: LocalDate? = null,
    val cycleStart: LocalDate? = null,
) {
    val firstName: String get() = fullName?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.takeIf { it.isNotBlank() } ?: "Коллега"
    val isOnboarded: Boolean get() = onboardedAt != null && !fullName.isNullOrBlank()
    val displayName: String get() = fullName ?: username?.let { "@$it" } ?: "ID $tgId"
}

/** Изменения профиля: null — поле не трогаем. */
data class ProfilePatch(
    val fullName: String? = null,
    val project: String? = null,
    val goalsYear: String? = null,
    val goalsYearInitial: String? = null,
    val goals3Months: String? = null,
    val onboardedAt: LocalDate? = null,
    val cycleStart: LocalDate? = null,
) {
    fun applyTo(p: Profile) = p.copy(
        fullName = fullName ?: p.fullName,
        project = project ?: p.project,
        goalsYear = goalsYear ?: p.goalsYear,
        goalsYearInitial = goalsYearInitial ?: p.goalsYearInitial,
        goals3Months = goals3Months ?: p.goals3Months,
        onboardedAt = onboardedAt ?: p.onboardedAt,
        cycleStart = cycleStart ?: p.cycleStart,
    )
}
