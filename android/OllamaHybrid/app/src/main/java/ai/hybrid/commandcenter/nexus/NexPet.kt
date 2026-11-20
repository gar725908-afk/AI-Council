package ai.hybrid.commandcenter.nexus

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/** Something real that happened; only these move NEX's level and mood. */
enum class PetEvent { ANSWER, VOICE_ANSWER, COUNCIL_DONE, ERROR, MODEL_ACTIVATED }

enum class PetMood(val title: String, val note: String) {
    HAPPY("Бодрый", "дела идут хорошо"),
    PROUD("Гордый", "только что вырос в уровне"),
    WORRIED("Встревожен", "последние запросы падали с ошибкой"),
    TIRED("Устал", "заряд телефона почти на нуле"),
    SLEEPY("Сонный", "давно ничего не происходит"),
    LONELY("Скучает", "больше суток без разговоров"),
    CHARGING("Заряжается", "телефон на зарядке")
}

data class PetAchievement(val id: String, val title: String, val description: String, val glyph: String)

/** Everything NEX remembers about its life with the owner. All counters come from real events. */
data class PetSnapshot(
    val xp: Int = 0,
    val answers: Int = 0,
    val voiceAnswers: Int = 0,
    val councilMissions: Int = 0,
    val errors: Int = 0,
    val offlineAnswers: Int = 0,
    val streakDays: Int = 0,
    val lastActiveDay: Long = -1L,
    val lastInteractionAt: Long = 0L,
    val lastLevelUpAt: Long = 0L,
    /** Newest last: 1 = success, 0 = error. */
    val recent: String = "",
    val achievements: Map<String, Long> = emptyMap(),
    val modelsTried: Set<String> = emptySet()
)

object PetRules {
    val achievements = listOf(
        PetAchievement("first_answer", "Первый контакт", "NEX впервые ответил", "初"),
        PetAchievement("answers_10", "Разговорились", "10 ответов", "十"),
        PetAchievement("answers_100", "Сотня", "100 ответов", "百"),
        PetAchievement("voice_1", "Голосовая связь", "Первый вопрос голосом", "声"),
        PetAchievement("council_1", "Совет созван", "Первая миссия совета", "会"),
        PetAchievement("council_10", "Председатель", "10 миссий совета", "議"),
        PetAchievement("offline", "Автономия", "Ответ модели на самом телефоне", "独"),
        PetAchievement("models_3", "Коллекционер", "Попробовал 3 разные модели", "集"),
        PetAchievement("streak_3", "Три дня подряд", "Общение 3 дня без перерыва", "三"),
        PetAchievement("streak_7", "Неделя вместе", "7 дней подряд", "七"),
        PetAchievement("night_owl", "Ночная сова", "Ответ между полуночью и пятью утра", "夜"),
        PetAchievement("comeback", "Снова в строю", "Удачный ответ сразу после ошибки", "復"),
        PetAchievement("level_5", "Ветеран", "5-й уровень", "五")
    )

    private const val XP_STEP = 40

    /** Level L starts at 40·L·(L−1)/2 XP: 0, 40, 120, 240, 400… */
    fun levelStart(level: Int): Int = XP_STEP * level * (level - 1) / 2

    fun level(xp: Int): Int {
        var level = 1
        while (levelStart(level + 1) <= xp) level++
        return level
    }

    fun progress(xp: Int): Float {
        val level = level(xp)
        val start = levelStart(level)
        val end = levelStart(level + 1)
        return ((xp - start).toFloat() / (end - start)).coerceIn(0f, 1f)
    }

    fun xpFor(event: PetEvent): Int = when (event) {
        PetEvent.ANSWER -> 10
        PetEvent.VOICE_ANSWER -> 15
        PetEvent.COUNCIL_DONE -> 30
        PetEvent.MODEL_ACTIVATED -> 20
        PetEvent.ERROR -> 0
    }

    data class Result(val snapshot: PetSnapshot, val unlocked: List<PetAchievement>, val leveledUpTo: Int?)

    /**
     * Applies one real event. [onDevice] marks answers produced by a model on the
     * phone; [modelId] identifies the model for MODEL_ACTIVATED.
     */
    fun apply(
        before: PetSnapshot,
        event: PetEvent,
        nowMillis: Long,
        hourOfDay: Int,
        epochDay: Long,
        onDevice: Boolean = false,
        modelId: String? = null
    ): Result {
        val success = event != PetEvent.ERROR
        val newModel = event == PetEvent.MODEL_ACTIVATED && modelId != null && modelId !in before.modelsTried
        val gained = if (event == PetEvent.MODEL_ACTIVATED && !newModel) 0 else xpFor(event)
        val streak = when {
            !success -> before.streakDays
            before.lastActiveDay == epochDay -> before.streakDays.coerceAtLeast(1)
            before.lastActiveDay == epochDay - 1 -> before.streakDays + 1
            else -> 1
        }
        val dailyBonus = if (success && before.lastActiveDay != epochDay && before.lastActiveDay >= 0) 5 else 0
        val answered = event == PetEvent.ANSWER || event == PetEvent.VOICE_ANSWER
        var after = before.copy(
            xp = before.xp + gained + dailyBonus,
            answers = before.answers + if (answered) 1 else 0,
            voiceAnswers = before.voiceAnswers + if (event == PetEvent.VOICE_ANSWER) 1 else 0,
            councilMissions = before.councilMissions + if (event == PetEvent.COUNCIL_DONE) 1 else 0,
            errors = before.errors + if (!success) 1 else 0,
            offlineAnswers = before.offlineAnswers + if (answered && onDevice) 1 else 0,
            streakDays = streak,
            lastActiveDay = if (success) epochDay else before.lastActiveDay,
            lastInteractionAt = nowMillis,
            recent = (before.recent + if (success) "1" else "0").takeLast(20),
            modelsTried = if (newModel) before.modelsTried + modelId!! else before.modelsTried
        )
        val levelBefore = level(before.xp)
        val levelAfter = level(after.xp)
        if (levelAfter > levelBefore) after = after.copy(lastLevelUpAt = nowMillis)

        val earned = buildList {
            if (after.answers >= 1) add("first_answer")
            if (after.answers >= 10) add("answers_10")
            if (after.answers >= 100) add("answers_100")
            if (after.voiceAnswers >= 1) add("voice_1")
            if (after.councilMissions >= 1) add("council_1")
            if (after.councilMissions >= 10) add("council_10")
            if (after.offlineAnswers >= 1) add("offline")
            if (after.modelsTried.size >= 3) add("models_3")
            if (after.streakDays >= 3) add("streak_3")
            if (after.streakDays >= 7) add("streak_7")
            if (answered && hourOfDay in 0..4) add("night_owl")
            if (success && before.recent.endsWith("0")) add("comeback")
            if (levelAfter >= 5) add("level_5")
        }.filter { it !in before.achievements }
        if (earned.isNotEmpty()) after = after.copy(achievements = after.achievements + earned.associateWith { nowMillis })
        return Result(after, earned.mapNotNull { id -> achievements.firstOrNull { it.id == id } }, levelAfter.takeIf { it > levelBefore })
    }

    /** Mood from facts only: recent outcomes, battery, time since the owner was here. */
    fun mood(
        pet: PetSnapshot,
        batteryPercent: Int?,
        charging: Boolean,
        nowMillis: Long,
        hourOfDay: Int,
        idleMillis: Long
    ): PetMood {
        val lastThree = pet.recent.takeLast(3)
        return when {
            charging -> PetMood.CHARGING
            batteryPercent != null && batteryPercent in 0..15 -> PetMood.TIRED
            lastThree.count { it == '0' } >= 2 -> PetMood.WORRIED
            nowMillis - pet.lastLevelUpAt in 0..TimeUnit.MINUTES.toMillis(10) && pet.lastLevelUpAt > 0 -> PetMood.PROUD
            pet.lastInteractionAt > 0 && nowMillis - pet.lastInteractionAt > TimeUnit.HOURS.toMillis(24) -> PetMood.LONELY
            idleMillis > TimeUnit.MINUTES.toMillis(3) || hourOfDay in 1..5 -> PetMood.SLEEPY
            else -> PetMood.HAPPY
        }
    }

    /** Asleep after a quiet stretch; a tap wakes it up. */
    fun sleeping(idleMillis: Long, hourOfDay: Int): Boolean =
        idleMillis > TimeUnit.MINUTES.toMillis(3) || (hourOfDay in 1..5 && idleMillis > TimeUnit.MINUTES.toMillis(1))

    /** What NEX says when the owner taps it — built from real numbers, never invented. */
    fun reaction(
        pet: PetSnapshot,
        mood: PetMood,
        batteryPercent: Int?,
        hourOfDay: Int,
        name: String,
        random: Random = Random.Default
    ): String {
        val who = name.ifBlank { "друг" }
        val level = level(pet.xp)
        val toNext = levelStart(level + 1) - pet.xp
        val lines = buildList {
            when (mood) {
                PetMood.TIRED -> add("Заряд ${batteryPercent ?: 0}% — подключи телефон, а то я засыпаю.")
                PetMood.CHARGING -> add("Заряжаюсь${batteryPercent?.let { " — уже $it%" } ?: ""}. Спасибо!")
                PetMood.WORRIED -> add("Последние запросы падали. Если повторится — проверь ядро в «Настройках».")
                PetMood.PROUD -> add("Я вырос до уровня $level! Спасибо, $who.")
                PetMood.LONELY -> add("Давно не болтали, $who. Есть задача для меня?")
                PetMood.SLEEPY -> add("Ммм… я задремал. Я здесь!")
                PetMood.HAPPY -> add(
                    when (hourOfDay) {
                        in 5..11 -> "Доброе утро, $who!"
                        in 12..17 -> "Добрый день, $who!"
                        in 18..22 -> "Добрый вечер, $who!"
                        else -> "Не спится, $who?"
                    }
                )
            }
            add("Уровень $level: до следующего ещё $toNext опыта.")
            if (pet.answers > 0) add("Мы уже решили задач: ${pet.answers + pet.councilMissions}.")
            if (pet.streakDays >= 2) add("Серия: ${pet.streakDays} дн. подряд — не прерывай!")
            if (pet.councilMissions == 0) add("Попробуй «Совет»: восемь агентов разберут сложную задачу.")
            if (pet.voiceAnswers == 0) add("Нажми кнопку с микрофоном внизу — поговорим голосом.")
        }
        return if (mood == PetMood.HAPPY) lines[random.nextInt(lines.size)] else lines.first()
    }

    fun encode(pet: PetSnapshot): String = JSONObject()
        .put("xp", pet.xp).put("answers", pet.answers).put("voice", pet.voiceAnswers)
        .put("council", pet.councilMissions).put("errors", pet.errors).put("offline", pet.offlineAnswers)
        .put("streak", pet.streakDays).put("day", pet.lastActiveDay).put("seen", pet.lastInteractionAt)
        .put("levelUp", pet.lastLevelUpAt).put("recent", pet.recent)
        .put("achievements", JSONObject().apply { pet.achievements.forEach { (k, v) -> put(k, v) } })
        .put("models", JSONArray(pet.modelsTried.toList()))
        .toString()

    fun decode(raw: String?): PetSnapshot {
        if (raw.isNullOrBlank()) return PetSnapshot()
        return runCatching {
            val json = JSONObject(raw)
            val achievements = json.optJSONObject("achievements")?.let { obj ->
                obj.keys().asSequence().associateWith { obj.optLong(it) }
            } ?: emptyMap()
            val models = json.optJSONArray("models")?.let { array -> (0 until array.length()).map { array.getString(it) }.toSet() } ?: emptySet()
            PetSnapshot(
                xp = json.optInt("xp"), answers = json.optInt("answers"), voiceAnswers = json.optInt("voice"),
                councilMissions = json.optInt("council"), errors = json.optInt("errors"),
                offlineAnswers = json.optInt("offline"), streakDays = json.optInt("streak"),
                lastActiveDay = json.optLong("day", -1L), lastInteractionAt = json.optLong("seen"),
                lastLevelUpAt = json.optLong("levelUp"), recent = json.optString("recent"),
                achievements = achievements, modelsTried = models
            )
        }.getOrDefault(PetSnapshot())
    }
}

/** Persists the pet; one small JSON in its own preferences file. */
class NexPetStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("nexus_pet", Context.MODE_PRIVATE)

    fun load(): PetSnapshot = PetRules.decode(prefs.getString("pet", null))

    fun save(pet: PetSnapshot) {
        prefs.edit().putString("pet", PetRules.encode(pet)).apply()
    }

    companion object {
        fun hourNow(): Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)

        fun epochDayNow(): Long {
            val calendar = Calendar.getInstance()
            val offset = calendar.get(Calendar.ZONE_OFFSET) + calendar.get(Calendar.DST_OFFSET)
            return TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis() + offset)
        }
    }
}
