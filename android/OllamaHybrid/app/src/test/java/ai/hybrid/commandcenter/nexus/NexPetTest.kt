package ai.hybrid.commandcenter.nexus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class NexPetTest {
    private val noon = 12
    private val day = 20_000L
    private val now = TimeUnit.DAYS.toMillis(day) + TimeUnit.HOURS.toMillis(12)

    @Test fun levelsGrowWithRealWork() {
        assertEquals(1, PetRules.level(0))
        assertEquals(2, PetRules.level(40))
        assertEquals(3, PetRules.level(120))
        assertEquals(0.5f, PetRules.progress(80), 0.001f)
        var pet = PetSnapshot()
        repeat(4) { pet = PetRules.apply(pet, PetEvent.ANSWER, now, noon, day).snapshot }
        assertEquals(40, pet.xp)
        assertEquals(2, PetRules.level(pet.xp))
        assertEquals(4, pet.answers)
    }

    @Test fun errorsGiveNoExperienceAndWorryNex() {
        var pet = PetSnapshot()
        pet = PetRules.apply(pet, PetEvent.ERROR, now, noon, day).snapshot
        pet = PetRules.apply(pet, PetEvent.ERROR, now, noon, day).snapshot
        assertEquals(0, pet.xp)
        assertEquals(2, pet.errors)
        assertEquals(PetMood.WORRIED, PetRules.mood(pet, 80, false, now, noon, 0L))
        // recovering right after an error earns «Снова в строю»
        val result = PetRules.apply(pet, PetEvent.ANSWER, now, noon, day)
        assertTrue(result.unlocked.any { it.id == "comeback" })
    }

    @Test fun achievementsUnlockOnceFromFacts() {
        var pet = PetSnapshot()
        val first = PetRules.apply(pet, PetEvent.VOICE_ANSWER, now, 3, day, onDevice = true)
        assertEquals(setOf("first_answer", "voice_1", "offline", "night_owl"), first.unlocked.map { it.id }.toSet())
        pet = first.snapshot
        val again = PetRules.apply(pet, PetEvent.VOICE_ANSWER, now, 3, day, onDevice = true)
        assertTrue("nothing unlocks twice", again.unlocked.isEmpty())
        val council = PetRules.apply(again.snapshot, PetEvent.COUNCIL_DONE, now, noon, day)
        assertEquals(listOf("council_1"), council.unlocked.map { it.id })
    }

    @Test fun streakCountsConsecutiveDaysAndLevelUpIsReported() {
        var pet = PetSnapshot()
        pet = PetRules.apply(pet, PetEvent.ANSWER, now, noon, day).snapshot
        pet = PetRules.apply(pet, PetEvent.ANSWER, now, noon, day + 1).snapshot
        pet = PetRules.apply(pet, PetEvent.ANSWER, now, noon, day + 2).snapshot
        assertEquals(3, pet.streakDays)
        assertTrue("streak_3" in pet.achievements)
        pet = PetRules.apply(pet, PetEvent.ANSWER, now, noon, day + 5).snapshot
        assertEquals(1, pet.streakDays)
        val big = PetRules.apply(PetSnapshot(xp = 35), PetEvent.ANSWER, now, noon, day)
        assertEquals(2, big.leveledUpTo)
        assertNull(PetRules.apply(PetSnapshot(xp = 0), PetEvent.ERROR, now, noon, day).leveledUpTo)
    }

    @Test fun aNewModelRewardsOnlyOnce() {
        var pet = PetRules.apply(PetSnapshot(), PetEvent.MODEL_ACTIVATED, now, noon, day, modelId = "qwen").snapshot
        assertEquals(20, pet.xp)
        pet = PetRules.apply(pet, PetEvent.MODEL_ACTIVATED, now, noon, day, modelId = "qwen").snapshot
        assertEquals(20, pet.xp)
    }

    @Test fun moodFollowsBatteryTimeAndCharging() {
        val pet = PetSnapshot(recent = "111", lastInteractionAt = now)
        assertEquals(PetMood.HAPPY, PetRules.mood(pet, 80, false, now, noon, 0L))
        assertEquals(PetMood.TIRED, PetRules.mood(pet, 9, false, now, noon, 0L))
        assertEquals(PetMood.CHARGING, PetRules.mood(pet, 9, true, now, noon, 0L))
        assertEquals(PetMood.SLEEPY, PetRules.mood(pet, 80, false, now, noon, TimeUnit.MINUTES.toMillis(5)))
        assertEquals(PetMood.LONELY, PetRules.mood(pet.copy(lastInteractionAt = now - TimeUnit.HOURS.toMillis(30)), 80, false, now, noon, 0L))
        assertTrue(PetRules.sleeping(TimeUnit.MINUTES.toMillis(4), noon))
        assertFalse(PetRules.sleeping(TimeUnit.SECONDS.toMillis(20), noon))
    }

    @Test fun reactionsQuoteRealNumbers() {
        val pet = PetSnapshot(xp = 130, answers = 12, streakDays = 4)
        val line = PetRules.reaction(pet, PetMood.TIRED, 7, noon, "Али")
        assertTrue(line, line.contains("7%"))
        val lines = (0 until 30).map { PetRules.reaction(pet, PetMood.HAPPY, 80, noon, "Али", Random(it)) }.toSet()
        assertTrue(lines.any { it.contains("Уровень 3") && it.contains("110") })
        assertTrue(lines.any { it.contains("4 дн.") })
    }

    @Test fun petSurvivesASaveAndLoad() {
        val pet = PetRules.apply(PetSnapshot(), PetEvent.VOICE_ANSWER, now, 3, day, onDevice = true, modelId = null).snapshot
            .copy(modelsTried = setOf("a", "b"))
        assertEquals(pet, PetRules.decode(PetRules.encode(pet)))
        assertEquals(PetSnapshot(), PetRules.decode("{broken"))
    }

    /** Every Japanese caption in the code must exist in the bundled font subset. */
    @Test fun bundledJapaneseFontCoversEveryCaption() {
        val covered = TtfCmap.codepoints(File("src/main/res/font/nexus_jp_bold.ttf").readBytes())
        val used = File("src/main/java").walkTopDown().filter { it.extension == "kt" }
            .flatMap { it.readText().asSequence() }
            .filter { val c = it.code; c in 0x3040..0x30FF || c in 0x4E00..0x9FFF || c in 0xFF61..0xFF9F }
            .toSet()
        assertTrue(used.size > 20)
        val missing = used.filterNot { it.code in covered }
        assertTrue("glyphs missing from nexus_jp_bold.ttf: " + missing.joinToString(""), missing.isEmpty())
    }
}

/** Minimal TrueType cmap reader (formats 4 and 12): which code points have a glyph. */
private object TtfCmap {
    fun codepoints(bytes: ByteArray): Set<Int> {
        val b = java.nio.ByteBuffer.wrap(bytes)
        val tables = b.getShort(4).toInt() and 0xFFFF
        var cmap = -1
        for (i in 0 until tables) {
            val rec = 12 + i * 16
            val tag = String(bytes, rec, 4, Charsets.US_ASCII)
            if (tag == "cmap") cmap = b.getInt(rec + 8)
        }
        require(cmap >= 0) { "no cmap table" }
        val result = HashSet<Int>()
        val subtables = b.getShort(cmap + 2).toInt() and 0xFFFF
        for (i in 0 until subtables) {
            val offset = cmap + b.getInt(cmap + 4 + i * 8 + 4)
            when (b.getShort(offset).toInt() and 0xFFFF) {
                4 -> {
                    val segX2 = b.getShort(offset + 6).toInt() and 0xFFFF
                    val ends = offset + 14
                    val starts = ends + segX2 + 2
                    val deltas = starts + segX2
                    val ranges = deltas + segX2
                    for (s in 0 until segX2 / 2) {
                        val end = b.getShort(ends + s * 2).toInt() and 0xFFFF
                        val start = b.getShort(starts + s * 2).toInt() and 0xFFFF
                        val delta = b.getShort(deltas + s * 2).toInt()
                        val range = b.getShort(ranges + s * 2).toInt() and 0xFFFF
                        for (c in start..end) {
                            if (c == 0xFFFF) continue
                            val glyph = if (range == 0) (c + delta) and 0xFFFF else {
                                val at = ranges + s * 2 + range + (c - start) * 2
                                val raw = b.getShort(at).toInt() and 0xFFFF
                                if (raw == 0) 0 else (raw + delta) and 0xFFFF
                            }
                            if (glyph != 0) result += c
                        }
                    }
                }
                12 -> {
                    val groups = b.getInt(offset + 12)
                    for (g in 0 until groups) {
                        val start = b.getInt(offset + 16 + g * 12)
                        val end = b.getInt(offset + 20 + g * 12)
                        for (c in start..end) result += c
                    }
                }
            }
        }
        return result
    }
}
