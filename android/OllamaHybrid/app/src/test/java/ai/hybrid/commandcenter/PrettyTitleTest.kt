package ai.hybrid.commandcenter

import ai.hybrid.commandcenter.ui.prettyTitle
import org.junit.Assert.assertEquals
import org.junit.Test

class PrettyTitleTest {
    @Test
    fun shoutingBecomesSentenceCase() {
        assertEquals("Хранилище и данные", prettyTitle("ХРАНИЛИЩЕ И ДАННЫЕ"))
        assertEquals("О приложении", prettyTitle("О ПРИЛОЖЕНИИ"))
    }

    @Test
    fun acronymsAndNamesSurvive() {
        assertEquals("MCP-коннекторы", prettyTitle("MCP-КОННЕКТОРЫ"))
        assertEquals("Ключ GitHub", prettyTitle("КЛЮЧ GITHUB"))
        assertEquals("Проверка SHA-256", prettyTitle("ПРОВЕРКА SHA-256"))
        assertEquals("Привилегии: Root и Shizuku", prettyTitle("ПРИВИЛЕГИИ: ROOT И SHIZUKU"))
    }

    @Test
    fun mixedCaseIsLeftAlone() {
        assertEquals("Уже нормально", prettyTitle("Уже нормально"))
        assertEquals("", prettyTitle(""))
    }
}
