package ai.hybrid.commandcenter.nexus

import android.content.Context
import android.provider.AlarmClock
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

class ExactCalculatorTest {
    private fun calc(text: String) = ExactCalculator.format(ExactCalculator.evaluate(text))

    @Test fun decimalsAreExactNotFloatingPoint() {
        assertEquals("0,3", calc("0,1 + 0,2"))
        assertEquals("14", calc("2+3*4"))
        assertEquals("20", calc("(2+3)*4"))
        assertEquals("0,33333333333333333333", calc("1/3"))
        assertEquals("1 267 650 600 228 229 401 496 703 205 376", calc("2^100"))
    }

    @Test fun russianWordsAndPercents() {
        assertEquals("360", calc("15% от 2400"))
        assertEquals("360", calc("15 процентов от 2400"))
        assertEquals("21", calc("7 умножить на 3"))
        assertEquals("2,5", calc("10 разделить на 4"))
        assertEquals("12", calc("корень из 144"))
        assertEquals("1 000 001", calc("1 000 000 + 1"))
        assertEquals("-5", calc("минус 2 минус 3"))
    }

    @Test fun mistakesAreExplainedNotGuessed() {
        try {
            ExactCalculator.evaluate("5/0")
            throw AssertionError("division by zero accepted")
        } catch (e: ExactCalculator.CalcException) {
            assertTrue(e.message!!.contains("ноль"))
        }
        assertFalse(ExactCalculator.looksLikeMath("привет как дела"))
        assertTrue(ExactCalculator.looksLikeMath("12*7"))
    }
}

class LocalIntentParserTest {
    private fun parse(text: String) = LocalIntentParser.parse(text)

    @Test fun factsFromThePhone() {
        assertEquals(LocalIntent.Time, parse("Который час?"))
        assertEquals(LocalIntent.Time, parse("сколько времени"))
        assertEquals(LocalIntent.Date, parse("Какое сегодня число?"))
        assertEquals(LocalIntent.Weekday, parse("какой сегодня день недели"))
        assertEquals(LocalIntent.Battery, parse("сколько заряда"))
        assertEquals(LocalIntent.Calculate("0,1+0,2"), parse("сколько будет 0,1+0,2"))
        assertEquals(LocalIntent.MeaningOfLife, parse("В чём смысл жизни?"))
    }

    @Test fun alarmsAndTimers() {
        assertEquals(LocalIntent.SetAlarm(7, 30, null), parse("Поставь будильник на 7:30"))
        assertEquals(LocalIntent.SetAlarm(6, 0, null), parse("разбуди меня в 6 утра"))
        assertEquals(LocalIntent.SetAlarm(19, 0, null), parse("будильник на 7 вечера"))
        assertEquals(LocalIntent.SetTimer(300), parse("поставь таймер на 5 минут"))
        assertEquals(LocalIntent.SetTimer(180), parse("засеки три минуты"))
        assertEquals(LocalIntent.SetTimer(1800), parse("таймер на полчаса"))
        assertEquals(LocalIntent.SetTimer(5400), parse("таймер на полтора часа"))
        assertEquals(LocalIntent.SetTimer(4800), parse("таймер на 1 час 20 минут"))
        assertEquals(LocalIntent.ShowAlarms, parse("покажи мои будильники"))
        assertEquals(LocalIntent.OpenApp(LocalApp.CALCULATOR), parse("открой калькулятор"))
    }

    @Test fun negationsQuestionsQuotesAndCompoundsAreNotCommands() {
        assertNull(parse("не ставь будильник на 7"))
        assertNull(parse("а ты можешь поставить будильник на 7?"))
        assertNull(parse("как поставить таймер на 5 минут"))
        assertNull(parse("он сказал «поставь будильник на 7»"))
        assertNull(parse("поставь будильник на 7 и напиши письмо маме"))
        assertNull(parse("открой дверь"))
        assertNull(parse("расскажи про будильники"))
    }

    @Test fun onlyVoiceAlarmsWaitForConfirmation() {
        val alarm = LocalIntent.SetAlarm(7, 0, null)
        assertTrue(alarm.needsConfirmation(fromVoice = true))
        assertFalse(alarm.needsConfirmation(fromVoice = false))
        assertFalse(LocalIntent.OpenApp(LocalApp.CAMERA).needsConfirmation(fromVoice = true))
        assertFalse(LocalIntent.Time.isAction())
        val pending = PendingLocalAction(alarm, "Будильник на 07:00", createdAt = 1_000L)
        assertFalse(pending.expired(50_000L))
        assertTrue(pending.expired(62_000L))
    }

    @Test fun durationsAndSummariesReadNaturally() {
        assertEquals("1 ч 20 мин", LocalCommandText.duration(4800))
        assertEquals("Будильник на 07:05", LocalCommandText.summary(LocalIntent.SetAlarm(7, 5, null)))
    }
}

class SentenceChunkerTest {
    @Test fun speaksFinishedSentencesAndKeepsDecimals() {
        val chunker = SentenceChunker()
        assertEquals(listOf("Привет!"), chunker.add("Привет! Цена 3.5 тыс"))
        assertEquals(listOf("Цена 3.5 тыс.", "Скидка?"), chunker.add(". Скидка? Ещё"))
        assertEquals("Ещё", chunker.flush())
        assertNull(chunker.flush())
        assertEquals(VoiceOutput.voiceLabel("ru-ru-x-ruc-local"), "Голос RUC · на телефоне")
        assertEquals(VoiceOutput.voiceLabel("ru-ru-x-rud-network"), "Голос RUD · через интернет")
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalCommandExecutorTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun alarmIsHandedToAndroidAndSaidHonestly() {
        val result = LocalCommandExecutor(context).perform(LocalIntent.SetAlarm(7, 30, null))
        val started = shadowOf(context as android.app.Application).nextStartedActivity
        assertEquals(AlarmClock.ACTION_SET_ALARM, started.action)
        assertEquals(7, started.getIntExtra(AlarmClock.EXTRA_HOUR, -1))
        assertEquals(30, started.getIntExtra(AlarmClock.EXTRA_MINUTES, -1))
        assertFalse(started.getBooleanExtra(AlarmClock.EXTRA_SKIP_UI, true))
        assertTrue(result, result.startsWith("Передано Android"))
    }

    @Test fun timerAndFacts() {
        val executor = LocalCommandExecutor(context)
        executor.perform(LocalIntent.SetTimer(300))
        val started = shadowOf(context as android.app.Application).nextStartedActivity
        assertEquals(AlarmClock.ACTION_SET_TIMER, started.action)
        assertEquals(300, started.getIntExtra(AlarmClock.EXTRA_LENGTH, -1))
        assertTrue(executor.answer(LocalIntent.Time).startsWith("Сейчас "))
        assertEquals("= 0,3", executor.answer(LocalIntent.Calculate("0,1+0,2")))
        assertTrue(executor.answer(LocalIntent.Calculate("5/0")).startsWith("Не смог посчитать"))
    }
}
