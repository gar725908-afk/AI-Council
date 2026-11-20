package ai.hybrid.commandcenter.ui

import ai.hybrid.commandcenter.R
import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Colours of one look. Slots keep their meaning in every theme: primary leads, secondary answers, tertiary accents. */
@Immutable
data class ThemePalette(
    val void: Color,
    val night: Color,
    val panel: Color,
    val panelHigh: Color,
    val primary: Color,
    val primarySoft: Color,
    val secondary: Color,
    val tertiary: Color,
    val highlight: Color,
    val text: Color,
    val textDim: Color,
    val textFaint: Color,
    val onNeon: Color,
    val wordmark: List<Color>
)

/**
 * The owner picks the world NEXUS lives in. Each theme is more than colours:
 * it changes the backdrop scene, the ring around the camera and the HUD captions.
 */
enum class NexusTheme(val id: String, val title: String, val glyph: String, val tagline: String, val palette: ThemePalette) {
    NEON_CITY(
        "neon", "Неон-город", "ネオン", "Ночной мегаполис, неон и сетка до горизонта",
        ThemePalette(
            void = Color(0xFF02040A), night = Color(0xFF050A16), panel = Color(0xFF09101F), panelHigh = Color(0xFF0F1830),
            primary = Color(0xFF00E5FF), primarySoft = Color(0xFF8AF5FF), secondary = Color(0xFFFF2E97),
            tertiary = Color(0xFF8F6BFF), highlight = Color(0xFFFCEE0A),
            text = Color(0xFFEAF6FF), textDim = Color(0xFF93A7C4), textFaint = Color(0xFF56667F),
            onNeon = Color(0xFF00141A),
            wordmark = listOf(Color(0xFFCFFCFF), Color(0xFF00E5FF), Color(0xFFB98CFF), Color(0xFFFF2E97))
        )
    ),
    COSMOS(
        "cosmos", "Космос", "宇宙", "Орбита у газового гиганта, звёзды и туманности",
        ThemePalette(
            void = Color(0xFF01020A), night = Color(0xFF040817), panel = Color(0xFF080D22), panelHigh = Color(0xFF0E1533),
            primary = Color(0xFF6FD8FF), primarySoft = Color(0xFFC6EEFF), secondary = Color(0xFFFF7AD3),
            tertiary = Color(0xFF9D7CFF), highlight = Color(0xFFFFD36E),
            text = Color(0xFFEEF3FF), textDim = Color(0xFF9AA6CF), textFaint = Color(0xFF5A6488),
            onNeon = Color(0xFF031024),
            wordmark = listOf(Color(0xFFFFFFFF), Color(0xFF9FE8FF), Color(0xFFB9A2FF), Color(0xFFFF8AD8))
        )
    ),
    ANIME(
        "anime", "Аниме", "アニメ", "Штаб в духе NERV: оранжевый HUD, катакана и закат",
        ThemePalette(
            void = Color(0xFF070304), night = Color(0xFF0E0607), panel = Color(0xFF170B0C), panelHigh = Color(0xFF221112),
            primary = Color(0xFFFF7A1A), primarySoft = Color(0xFFFFC08A), secondary = Color(0xFFFF2D55),
            tertiary = Color(0xFFA35CFF), highlight = Color(0xFFB6FF3B),
            text = Color(0xFFFFF1E6), textDim = Color(0xFFC9A99A), textFaint = Color(0xFF7D5E54),
            onNeon = Color(0xFF1A0800),
            wordmark = listOf(Color(0xFFFFE3C8), Color(0xFFFF7A1A), Color(0xFFFF2D55), Color(0xFFA35CFF))
        )
    );

    companion object {
        fun byId(id: String?): NexusTheme = entries.firstOrNull { it.id == id } ?: NEON_CITY
    }
}

/** The current theme as snapshot state: every screen that reads a [Cyber] colour follows a switch at once. */
object ThemeState {
    private const val PREFS = "nexus_profile"
    private const val KEY = "theme"

    private const val RING = "camera_ring"

    var theme: NexusTheme by mutableStateOf(NexusTheme.NEON_CITY)

    /** The live ring around the front camera; on by default, the owner can turn it off. */
    var cameraRing: Boolean by mutableStateOf(true)
        private set

    val palette: ThemePalette get() = theme.palette

    fun load(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        theme = NexusTheme.byId(prefs.getString(KEY, null))
        cameraRing = prefs.getBoolean(RING, true)
    }

    fun saveCameraRing(context: Context, enabled: Boolean) {
        cameraRing = enabled
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(RING, enabled).apply()
    }

    fun save(context: Context, value: NexusTheme) {
        theme = value
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, value.id).apply()
    }
}

/** Japanese captions (katakana, kanji) in a bundled Noto Sans JP subset, so they never render as boxes. */
val JpFamily = FontFamily(Font(R.font.nexus_jp_bold, FontWeight.Bold))

val JpLabel = TextStyle(fontFamily = JpFamily, fontWeight = FontWeight.Bold, fontSize = 10.sp, letterSpacing = 1.sp)

fun String.hasCjk(): Boolean = any { ch ->
    val c = ch.code
    c in 0x3040..0x30FF || c in 0x4E00..0x9FFF || c in 0xFF61..0xFF9F
}

/** HUD captions per theme: katakana under the dock tabs in the anime world. */
object ThemeCaptions {
    fun tab(label: String): String? = if (ThemeState.theme != NexusTheme.ANIME) null else when (label.lowercase()) {
        "чат" -> "チャット"
        "совет" -> "評議会"
        "модули" -> "モジュール"
        "настройки" -> "設定"
        else -> null
    }

    /** NEX state words for the camera ring and the hologram plate in the anime world. */
    fun state(state: NexState, speaking: Boolean = false): String = if (speaking) "発話" else when (state) {
        NexState.STANDBY -> "待機"
        NexState.LISTENING -> "聴取"
        NexState.THINKING -> "思考"
        NexState.SUCCESS -> "成功"
        NexState.ATTENTION -> "注意"
        NexState.ERROR -> "異常"
        NexState.OFFLINE -> "未接続"
        NexState.SETUP -> "起動"
    }
}


/**
 * Wraps runs of Japanese characters in the bundled JP font so mixed HUD
 * captions like «審議中 · 8» render crisply next to the mono Latin/Cyrillic.
 */
fun cjkAware(text: String): androidx.compose.ui.text.AnnotatedString {
    if (!text.hasCjk()) return androidx.compose.ui.text.AnnotatedString(text)
    return androidx.compose.ui.text.buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            val cjk = text.substring(i, i + 1).hasCjk()
            var j = i + 1
            while (j < text.length && text.substring(j, j + 1).hasCjk() == cjk) j++
            if (cjk) {
                pushStyle(androidx.compose.ui.text.SpanStyle(fontFamily = JpFamily, fontWeight = FontWeight.Bold))
                append(text.substring(i, j))
                pop()
            } else append(text.substring(i, j))
            i = j
        }
    }
}
