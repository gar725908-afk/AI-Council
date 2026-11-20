package ai.hybrid.commandcenter.nexus

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.BatteryManager
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import java.util.Calendar
import java.util.Locale

/** Apps NEX may open by name. A closed list: nothing else is launched from words. */
enum class LocalApp(val title: String) { CALCULATOR("калькулятор"), CAMERA("камеру"), CALENDAR("календарь"), SETTINGS("настройки"), BROWSER("браузер") }

/** What the owner asked for, understood without any language model. */
sealed interface LocalIntent {
    data object Time : LocalIntent
    data object Date : LocalIntent
    data object Weekday : LocalIntent
    data object Battery : LocalIntent
    data class Calculate(val expression: String) : LocalIntent
    data class SetAlarm(val hour: Int, val minute: Int, val label: String?) : LocalIntent
    data class SetTimer(val seconds: Int) : LocalIntent
    data object ShowAlarms : LocalIntent
    data class OpenApp(val app: LocalApp) : LocalIntent
    data object MeaningOfLife : LocalIntent
}

/**
 * Russian command understanding for things the phone can do itself. It is
 * deliberately strict: negations («не ставь»), questions about ability
 * («а можешь поставить?»), quoted text and compound requests with an
 * unsupported part are NOT commands — they go to the model as a normal chat.
 */
object LocalIntentParser {
    private val NEGATION = Regex("(^|\\s)(не|нельзя|никогда|отмени|отменить|удали|удалить|выключи|выключить)\\s")
    private val ABILITY_QUESTION = Regex("(можешь|умеешь|сможешь|можно ли|как\\s+(поставить|включить|открыть|сделать))")
    private val QUOTES = Regex("[«»\"“”„]")
    private val COMPOUND = Regex("\\s(и|а потом|затем|потом|после этого)\\s+(напиши|найди|расскажи|переведи|отправь|позвони|сделай|создай|покажи|объясни)")

    fun parse(raw: String): LocalIntent? {
        val text = raw.trim().lowercase(Locale("ru")).replace('ё', 'е').trimEnd('.', '!', '?', ' ')
        if (text.isEmpty() || text.length > 160) return null
        if (QUOTES.containsMatchIn(raw)) return null
        if (COMPOUND.containsMatchIn(" $text ")) return null

        // facts: questions are fine here — they ask for a fact, not an action
        if (Regex("^(сколько\\s+(сейчас\\s+)?времени|который\\s+(сейчас\\s+)?час|сколько\\s+время|время|какое\\s+сейчас\\s+время)$").matches(text)) return LocalIntent.Time
        if (Regex("^(какое\\s+(сегодня\\s+)?число|какая\\s+(сегодня\\s+)?дата|какое\\s+число\\s+сегодня|сегодняшняя\\s+дата|дата)$").matches(text)) return LocalIntent.Date
        if (Regex("^(какой\\s+(сегодня\\s+)?день(\\s+недели)?|какой\\s+день\\s+недели(\\s+сегодня)?|день\\s+недели)$").matches(text)) return LocalIntent.Weekday
        if (Regex("^(сколько\\s+(осталось\\s+)?заряда|какой\\s+заряд(\\s+батареи)?|заряд(\\s+батареи)?|сколько\\s+процентов\\s+(батареи|заряда)|батарея)$").matches(text)) return LocalIntent.Battery
        if (Regex("^((в\\s+)?чем|каков|какой)?\\s*смысл\\s+жизни$").matches(text) || "главный вопрос жизни" in text) return LocalIntent.MeaningOfLife

        // exact arithmetic
        val math = Regex("^(сколько\\s+будет|посчитай|вычисли|реши|подсчитай|calc)\\s+(.+)$").find(text)?.groupValues?.get(2) ?: text
        if (ExactCalculator.looksLikeMath(math) && runCatching { ExactCalculator.evaluate(math) }.isSuccess) {
            return LocalIntent.Calculate(math)
        }

        // actions: never from negations or «а можешь…?»
        if (NEGATION.containsMatchIn(" $text ") || ABILITY_QUESTION.containsMatchIn(text)) return null
        parseAlarm(text)?.let { return it }
        parseTimer(text)?.let { return it }
        if (Regex("^(покажи|открой)\\s+(мои\\s+|все\\s+)?будильники$").matches(text) || text == "мои будильники") return LocalIntent.ShowAlarms
        Regex("^(открой|запусти|включи)\\s+(.+)$").find(text)?.groupValues?.get(2)?.let { target ->
            appFor(target)?.let { return LocalIntent.OpenApp(it) }
        }
        return null
    }

    private fun appFor(target: String): LocalApp? = when (target.trim()) {
        "калькулятор" -> LocalApp.CALCULATOR
        "камеру", "камера", "фотоаппарат" -> LocalApp.CAMERA
        "календарь" -> LocalApp.CALENDAR
        "настройки", "настройки телефона" -> LocalApp.SETTINGS
        "браузер", "хром", "chrome", "гугл хром", "интернет" -> LocalApp.BROWSER
        else -> null
    }

    private val NUMBER_WORDS = mapOf(
        "ноль" to 0, "один" to 1, "одну" to 1, "одна" to 1, "два" to 2, "две" to 2, "три" to 3, "четыре" to 4,
        "пять" to 5, "шесть" to 6, "семь" to 7, "восемь" to 8, "девять" to 9, "десять" to 10, "одиннадцать" to 11,
        "двенадцать" to 12, "пятнадцать" to 15, "двадцать" to 20, "тридцать" to 30, "сорок" to 40
    )

    private fun wordsToDigits(text: String): String {
        var s = text
        NUMBER_WORDS.forEach { (word, value) -> s = Regex("(^|\\s)$word(?=\\s|$)").replace(s) { it.groupValues[1] + value } }
        return s
    }

    private fun parseAlarm(raw: String): LocalIntent.SetAlarm? {
        val text = wordsToDigits(raw)
        if (!Regex("(будильник|разбуди)").containsMatchIn(text)) return null
        if (!Regex("^(поставь|поставить|установи|заведи|создай|включи|разбуди|будильник)").containsMatchIn(text)) return null
        // relative: «через 20 минут», «через 1 час 30 минут»
        Regex("через\\s+(?:(\\d{1,2})\\s*(?:час(?:а|ов)?|ч)\\s*)?(?:(\\d{1,3})\\s*(?:минут[уы]?|мин))?").find(text)?.let { m ->
            val hours = m.groupValues[1].toIntOrNull() ?: 0
            val minutes = m.groupValues[2].toIntOrNull() ?: 0
            if (hours + minutes > 0) {
                val at = Calendar.getInstance().apply { add(Calendar.MINUTE, hours * 60 + minutes) }
                return LocalIntent.SetAlarm(at.get(Calendar.HOUR_OF_DAY), at.get(Calendar.MINUTE), null)
            }
        }
        val m = Regex("(?:на|в)\\s+(\\d{1,2})(?:[:.\\s](\\d{2}))?\\s*(утра|вечера|дня|ночи|час(?:а|ов)?)?").find(text) ?: return null
        var hour = m.groupValues[1].toInt()
        val minute = m.groupValues[2].toIntOrNull() ?: 0
        when (m.groupValues[3]) {
            "вечера", "дня" -> if (hour in 1..11) hour += 12
            "ночи" -> if (hour == 12) hour = 0
        }
        if (hour !in 0..23 || minute !in 0..59) return null
        return LocalIntent.SetAlarm(hour, minute, null)
    }

    private fun parseTimer(raw: String): LocalIntent.SetTimer? {
        val text = wordsToDigits(raw)
        if (!Regex("^(поставь|поставить|установи|заведи|запусти|включи|засеки|таймер)").containsMatchIn(text)) return null
        if (!Regex("(таймер|засеки)").containsMatchIn(text)) return null
        if (Regex("полчаса").containsMatchIn(text)) return LocalIntent.SetTimer(30 * 60)
        if (Regex("полтора\\s+час").containsMatchIn(text)) return LocalIntent.SetTimer(90 * 60)
        if (Regex("полторы\\s+минут").containsMatchIn(text)) return LocalIntent.SetTimer(90)
        val hours = Regex("(\\d{1,2})\\s*(?:час(?:а|ов)?|ч)(?![a-zа-я])").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val minutes = Regex("(\\d{1,3})\\s*(?:минут[уы]?|мин)").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val seconds = Regex("(\\d{1,4})\\s*(?:секунд[уы]?|сек)").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val total = hours * 3600 + minutes * 60 + seconds
        if (total <= 0 || total > 24 * 3600) return null
        return LocalIntent.SetTimer(total)
    }
}

/** An action waiting for the owner's explicit «Подтвердить»; it expires after a minute. */
data class PendingLocalAction(val intent: LocalIntent, val summary: String, val createdAt: Long) {
    fun expired(now: Long): Boolean = now - createdAt > TTL_MS

    companion object {
        const val TTL_MS = 60_000L
    }
}

object LocalCommandText {
    private val WEEKDAYS = listOf("воскресенье", "понедельник", "вторник", "среда", "четверг", "пятница", "суббота")
    private val MONTHS = listOf("января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа", "сентября", "октября", "ноября", "декабря")

    fun clock(hour: Int, minute: Int) = String.format(Locale.ROOT, "%02d:%02d", hour, minute)

    fun duration(seconds: Int): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return listOfNotNull(
            if (h > 0) "$h ч" else null,
            if (m > 0) "$m мин" else null,
            if (s > 0) "$s с" else null
        ).joinToString(" ")
    }

    fun summary(intent: LocalIntent): String = when (intent) {
        is LocalIntent.SetAlarm -> "Будильник на " + clock(intent.hour, intent.minute)
        is LocalIntent.SetTimer -> "Таймер на " + duration(intent.seconds)
        LocalIntent.ShowAlarms -> "Показать будильники"
        is LocalIntent.OpenApp -> "Открыть " + intent.app.title
        else -> ""
    }

    fun time(now: Calendar = Calendar.getInstance()): String =
        "Сейчас " + clock(now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE)) + "."

    fun date(now: Calendar = Calendar.getInstance()): String =
        "Сегодня " + now.get(Calendar.DAY_OF_MONTH) + " " + MONTHS[now.get(Calendar.MONTH)] + " " + now.get(Calendar.YEAR) +
            ", " + WEEKDAYS[now.get(Calendar.DAY_OF_WEEK) - 1] + "."

    fun weekday(now: Calendar = Calendar.getInstance()): String = "Сегодня " + WEEKDAYS[now.get(Calendar.DAY_OF_WEEK) - 1] + "."
}

/** Actions that change something on the phone. Facts and arithmetic never need confirmation. */
fun LocalIntent.needsConfirmation(fromVoice: Boolean): Boolean = when (this) {
    is LocalIntent.SetAlarm, is LocalIntent.SetTimer -> fromVoice
    else -> false
}

fun LocalIntent.isAction(): Boolean =
    this is LocalIntent.SetAlarm || this is LocalIntent.SetTimer || this == LocalIntent.ShowAlarms || this is LocalIntent.OpenApp

/**
 * Answers facts from the phone and hands actions to Android. A successful
 * intent means «передано Android» — the result line says so honestly instead
 * of claiming the alarm already exists.
 */
class LocalCommandExecutor(private val context: Context) {
    fun answer(intent: LocalIntent): String = when (intent) {
        LocalIntent.Time -> LocalCommandText.time()
        LocalIntent.Date -> LocalCommandText.date()
        LocalIntent.Weekday -> LocalCommandText.weekday()
        LocalIntent.Battery -> battery()
        is LocalIntent.Calculate -> runCatching {
            "= " + ExactCalculator.format(ExactCalculator.evaluate(intent.expression))
        }.getOrElse { "Не смог посчитать: " + (it.message ?: "ошибка в выражении") }
        LocalIntent.MeaningOfLife -> "42. Главное — без паники. 🌌"
        else -> perform(intent)
    }

    private fun battery(): String {
        val manager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val percent = manager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }
            ?: return "Android не сообщил заряд батареи."
        val charging = manager.isCharging
        return "Заряд $percent%" + if (charging) ", телефон заряжается." else "."
    }

    fun perform(intent: LocalIntent): String {
        val launch = when (intent) {
            is LocalIntent.SetAlarm -> Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, intent.hour)
                .putExtra(AlarmClock.EXTRA_MINUTES, intent.minute)
                .putExtra(AlarmClock.EXTRA_MESSAGE, intent.label ?: "NEXUS")
                .putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            is LocalIntent.SetTimer -> Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, intent.seconds)
                .putExtra(AlarmClock.EXTRA_MESSAGE, "NEXUS")
                .putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            LocalIntent.ShowAlarms -> Intent(AlarmClock.ACTION_SHOW_ALARMS)
            is LocalIntent.OpenApp -> when (intent.app) {
                LocalApp.CALCULATOR -> Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALCULATOR)
                LocalApp.CAMERA -> Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
                LocalApp.CALENDAR -> Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALENDAR)
                LocalApp.SETTINGS -> Intent(Settings.ACTION_SETTINGS)
                LocalApp.BROWSER -> Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com"))
            }
            else -> return ""
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(launch)
            when (intent) {
                is LocalIntent.SetAlarm -> "Передано Android: будильник на " + LocalCommandText.clock(intent.hour, intent.minute) +
                    ". Проверь в «Часах», что он появился."
                is LocalIntent.SetTimer -> "Передано Android: таймер на " + LocalCommandText.duration(intent.seconds) + "."
                else -> "Открываю: " + LocalCommandText.summary(intent).removePrefix("Открыть ").removePrefix("Показать ") + "."
            }
        } catch (_: ActivityNotFoundException) {
            "На телефоне нет приложения для этого действия."
        } catch (error: SecurityException) {
            "Android не разрешил действие: " + (error.message ?: "нет доступа") + "."
        }
    }
}
