package ai.hybrid.commandcenter.ui

import androidx.compose.ui.graphics.Color

/**
 * NEX — the persona and interface layer of NEXUS (handoff section 2):
 * not a separate model, not a claim of consciousness, not a privileged
 * executor. It reflects the real state of the system and says what it can
 * do next; it never invents authority or hides a limitation.
 *
 * Everything here is pure, so every mood and every line is unit-tested.
 */
enum class NexState(val title: String) {
    STANDBY("Готов"),
    LISTENING("Слушаю"),
    THINKING("Работаю"),
    SUCCESS("Готово"),
    ATTENTION("Нужен ваш ответ"),
    ERROR("Ошибка"),
    OFFLINE("Нет связи"),
    SETUP("Нужна модель");

    val color: Color
        get() = when (this) {
            STANDBY -> NexusColors.NeonCyan
            LISTENING -> NexusColors.NeonMagenta
            THINKING -> NexusColors.NeonViolet
            SUCCESS -> NexusColors.SignalGreen
            ATTENTION -> NexusColors.SignalAmber
            ERROR -> NexusColors.SignalRed
            OFFLINE -> NexusColors.TextMuted
            SETUP -> NexusColors.SignalAmber
        }
}

/** Observed facts only. Unknown stays null — NEX never fills gaps with guesses. */
data class NexFacts(
    val nowMillis: Long,
    val listening: Boolean = false,
    val networkAvailable: Boolean = true,
    val localModelName: String? = null,
    val cloudProviderName: String? = null,
    val runningMissionGoal: String? = null,
    val awaitingInputGoal: String? = null,
    val lastCompletedGoal: String? = null,
    val lastCompletedAtMillis: Long? = null,
    val lastErrorSummary: String? = null,
    val lastErrorAtMillis: Long? = null
) {
    val hasModel: Boolean get() = localModelName != null || cloudProviderName != null
    val canWorkNow: Boolean get() = localModelName != null || (cloudProviderName != null && networkAvailable)
}

object NexMind {

    const val RECENT_ERROR_WINDOW_MILLIS = 10 * 60 * 1000L
    const val RECENT_SUCCESS_WINDOW_MILLIS = 3 * 60 * 1000L

    /** Priority order: what the user most needs to know right now wins. */
    fun derive(facts: NexFacts): NexState {
        val errorIsRecent = facts.lastErrorAtMillis?.let { facts.nowMillis - it in 0..RECENT_ERROR_WINDOW_MILLIS } == true
        val errorIsNewerThanSuccess = facts.lastErrorAtMillis != null &&
            (facts.lastCompletedAtMillis == null || facts.lastErrorAtMillis > facts.lastCompletedAtMillis)
        val successIsRecent = facts.lastCompletedAtMillis?.let { facts.nowMillis - it in 0..RECENT_SUCCESS_WINDOW_MILLIS } == true
        return when {
            facts.listening -> NexState.LISTENING
            facts.runningMissionGoal != null -> NexState.THINKING
            errorIsRecent && errorIsNewerThanSuccess -> NexState.ERROR
            facts.awaitingInputGoal != null -> NexState.ATTENTION
            !facts.hasModel -> NexState.SETUP
            !facts.canWorkNow -> NexState.OFFLINE
            successIsRecent -> NexState.SUCCESS
            else -> NexState.STANDBY
        }
    }

    /** What NEX says. Built only from facts; quotes are shortened, never paraphrased. */
    fun line(state: NexState, facts: NexFacts): String = when (state) {
        NexState.LISTENING -> "Слушаю. Говорите задачу — текст появится в поле ввода."
        NexState.THINKING -> "Council работает над задачей «" + short(facts.runningMissionGoal) + "»."
        NexState.ERROR -> "Недавно произошла ошибка: " + short(facts.lastErrorSummary, 70) +
            ". Разобрать её можно в разделе «Исправления»."
        NexState.ATTENTION -> "Council ждёт вашего ответа по задаче «" + short(facts.awaitingInputGoal) + "»."
        NexState.SETUP -> "Подключите модель — локальную или облачную, — и я начну работать."
        NexState.OFFLINE -> "Нет сети, а локальной модели нет. Пока могу показывать данные, но не отвечать."
        NexState.SUCCESS -> "Готово: «" + short(facts.lastCompletedGoal) + "». Результат — в Council."
        NexState.STANDBY -> standbyLine(facts)
    }

    private fun standbyLine(facts: NexFacts): String {
        val engine = when {
            facts.localModelName != null && !facts.networkAvailable ->
                "Сети нет — работаю на локальной модели " + short(facts.localModelName, 32) + "."
            facts.localModelName != null -> "На связи локальная модель " + short(facts.localModelName, 32) + "."
            else -> "На связи " + short(facts.cloudProviderName, 32) + "."
        }
        return "$engine Чем займёмся?"
    }

    private fun short(text: String?, max: Int = 48): String {
        val clean = text.orEmpty().replace(Regex("\\s+"), " ").trim()
        if (clean.isEmpty()) return "без названия"
        return if (clean.length <= max) clean else clean.take(max - 1).trimEnd() + "…"
    }
}

/** One segment of the Core Orbital — measured state only. */
enum class SegmentStatus(val color: Color, val label: String) {
    OK(NexusColors.SignalGreen, "норма"),
    ACTIVE(NexusColors.NeonViolet, "активно"),
    WARN(NexusColors.SignalAmber, "внимание"),
    ERROR(NexusColors.SignalRed, "сбой"),
    UNKNOWN(NexusColors.TextMuted, "нет данных")
}

data class OrbitalSegment(val title: String, val value: String, val status: SegmentStatus)
