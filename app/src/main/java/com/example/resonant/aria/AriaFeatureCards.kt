package com.example.resonant.aria

import com.example.resonant.data.network.AriaActionContentDTO
import com.example.resonant.data.network.AriaStreakDTO
import com.example.resonant.data.network.StreakStatusDTO
import kotlin.math.roundToInt

/** Streak card of an Aria message (`content.kind = "streak"`). Only built when it can be painted. */
data class AriaStreakCard(
    val currentDays: Int,
    val longestDays: Int,
    val todaySeconds: Int,
    val goalSeconds: Int,
    val todayQualified: Boolean,
    val freezesAvailable: Int,
    val nextMilestone: Int?
) {
    /** Today's progress, 0-100, rounded (420 of 900 s → 47). */
    val progressPercent: Int
        get() = (todaySeconds * 100.0 / goalSeconds).roundToInt().coerceIn(0, 100)
}

/** "Why did you recommend this" card (`content.kind = "pick_explanation"`). */
data class AriaPickExplanationCard(
    val songId: String,
    val title: String,
    val artist: String?,
    val coverUrl: String?,
    /** Spanish label of the origin, or null for `generic`/unknown. */
    val sourceLabel: String?,
    val reason: String?,
    /** At most [MAX_EVIDENCE] sentences, shown as chips without action. */
    val evidence: List<String>
) {
    companion object {
        const val MAX_EVIDENCE = 3
    }
}

/** Pure "can this card be painted?" logic; missing mandatory fields → no card, only the text. */
object AriaFeatureCards {
    const val KIND_STREAK = "streak"
    const val KIND_PICK_EXPLANATION = "pick_explanation"

    /**
     * `current_days` and a positive `goal_seconds` are mandatory. Each field is read from `content`
     * and, when absent there, from the action's `streak` object (same fields).
     */
    fun streakCard(content: AriaActionContentDTO?, streak: AriaStreakDTO?): AriaStreakCard? {
        if (content?.kind != KIND_STREAK) return null
        val currentDays = (content.currentDays ?: streak?.currentDays)?.takeIf { it >= 0 } ?: return null
        val goalSeconds = (content.goalSeconds ?: streak?.goalSeconds)?.takeIf { it > 0 } ?: return null
        val todaySeconds = (content.todaySeconds ?: streak?.todaySeconds ?: 0).coerceAtLeast(0)
        return AriaStreakCard(
            currentDays = currentDays,
            longestDays = (content.longestDays ?: streak?.longestDays ?: currentDays).coerceAtLeast(currentDays),
            todaySeconds = todaySeconds,
            goalSeconds = goalSeconds,
            todayQualified = content.todayQualified ?: streak?.todayQualified ?: (todaySeconds >= goalSeconds),
            freezesAvailable = (content.freezesAvailable ?: streak?.freezesAvailable ?: 0).coerceAtLeast(0),
            nextMilestone = (content.nextMilestone ?: streak?.nextMilestone)?.takeIf { it > 0 }
        )
    }

    /** The song (id and title) is mandatory; without it there is no card. */
    fun pickExplanationCard(content: AriaActionContentDTO?): AriaPickExplanationCard? {
        if (content?.kind != KIND_PICK_EXPLANATION) return null
        val song = content.song ?: return null
        val songId = song.id.clean() ?: return null
        val title = song.title.clean() ?: return null
        return AriaPickExplanationCard(
            songId = songId,
            title = title,
            artist = song.artist.clean(),
            coverUrl = song.coverUrl.clean(),
            sourceLabel = sourceLabel(content.source),
            reason = content.reason.clean(),
            evidence = content.evidence.orEmpty()
                .mapNotNull { it.clean() }
                .take(AriaPickExplanationCard.MAX_EVIDENCE)
        )
    }

    fun sourceLabel(source: String?): String? = when (source?.trim()?.lowercase()) {
        "aria_pick" -> "Elegida por Aria"
        "explore" -> "Explorar"
        "flow" -> "Flow"
        else -> null
    }

    /** Status for `StreakDetailsBottomSheet`, built from the card (the sheet only reads these). */
    fun toStreakStatus(card: AriaStreakCard): StreakStatusDTO = StreakStatusDTO(
        currentDays = card.currentDays,
        longestDays = card.longestDays,
        todaySeconds = card.todaySeconds,
        goalSeconds = card.goalSeconds,
        todayQualified = card.todayQualified,
        freezesAvailable = card.freezesAvailable,
        nextMilestone = card.nextMilestone
    )

    /** "Hoy: 7 de 15 min" or "Hoy cumplido". */
    fun todayText(card: AriaStreakCard): String =
        if (card.todayQualified) "Hoy cumplido"
        else "Hoy: ${card.todaySeconds / 60} de ${card.goalSeconds / 60} min"

    fun daysText(days: Int): String = if (days == 1) "1 día" else "$days días"

    fun freezesText(card: AriaStreakCard): String = when (card.freezesAvailable) {
        0 -> "Sin protectores disponibles"
        1 -> "1 protector disponible"
        else -> "${card.freezesAvailable} protectores disponibles"
    }

    fun milestoneText(card: AriaStreakCard): String? =
        card.nextMilestone?.let { "Próximo hito: ${daysText(it)}" }

    private fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
}
