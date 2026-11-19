package ai.hybrid.commandcenter

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Void = Color(0xFF080B12)
private val SurfaceDeep = Color(0xFF0E1420)
private val SurfaceRaised = Color(0xFF151E2D)
private val Cyan = Color(0xFF42D9F5)
private val Violet = Color(0xFF9B7BFF)
private val PrimaryText = Color(0xFFF1F5FA)
private val SecondaryText = Color(0xFFA4B1C2)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { NexusApp() }
    }
}

@Composable
private fun NexusApp() {
    val colors = darkColorScheme(
        primary = Cyan,
        secondary = Violet,
        background = Void,
        surface = SurfaceDeep,
        onPrimary = Void,
        onBackground = PrimaryText,
        onSurface = PrimaryText
    )
    MaterialTheme(colorScheme = colors) {
        var selected by remember { mutableIntStateOf(0) }
        val destinations = listOf("Главная", "AI Studio", "Council", "Модели", "Устройство")
        Scaffold(
            containerColor = Void,
            bottomBar = {
                NavigationBar(containerColor = SurfaceDeep) {
                    destinations.forEachIndexed { index, label ->
                        NavigationBarItem(
                            selected = selected == index,
                            onClick = { selected = index },
                            icon = { Text(listOf("⌂", "✧", "◎", "◈", "▣")[index], fontSize = 18.sp) },
                            label = { Text(label, fontSize = 10.sp) }
                        )
                    }
                }
            }
        ) { padding ->
            when (selected) {
                0 -> CommandDeck(Modifier.padding(padding))
                1 -> AiStudioScreen(councilMode = false, modifier = Modifier.padding(padding))
                2 -> AiStudioScreen(councilMode = true, modifier = Modifier.padding(padding))
                else -> PlaceholderScreen(destinations[selected], Modifier.padding(padding))
            }
        }
    }
}

@Composable
private fun CommandDeck(modifier: Modifier = Modifier) {
    LazyColumn(
        modifier = modifier.fillMaxSize().background(Void).padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Spacer(Modifier.height(18.dp))
            Text("NEXUS", color = PrimaryText, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Text("SIRIUS TECHNOLOGY SYSTEM", color = Cyan, fontSize = 11.sp, letterSpacing = 2.sp)
            Spacer(Modifier.height(18.dp))
        }
        item {
            Surface(color = SurfaceDeep, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("GENESIS PROTOCOL", color = Cyan, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text("AI Studio и Council подключены к базовому runtime", color = PrimaryText, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Выберите OpenAI-compatible, Claude или Gemini, проверьте соединение и загрузите модели. Ключи хранятся через Android Keystore; HTTP разрешён только для локальной сети.",
                        color = SecondaryText,
                        fontSize = 13.sp
                    )
                }
            }
        }
        item { StatusCard("NEXUS CORE", "Стартовый runtime и потоковый шлюз", "FOUNDATION") }
        item { StatusCard("AI STUDIO", "Три API-протокола и каталог моделей", "READY TO CONFIGURE") }
        item { StatusCard("AI COUNCIL", "Восемь ролей, маршруты по отдельным провайдерам", "READY TO CONFIGURE") }
        item { StatusCard("DEVICE BRIDGE", "Системные действия пока не подключены", "NOT CONNECTED") }
        item {
            Text("ПЕРВЫЙ СИГНАЛ", color = Violet, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text("Выберите AI Studio для одиночного запроса или Council для коллективного анализа.", color = SecondaryText, fontSize = 14.sp)
            Spacer(Modifier.height(22.dp))
        }
    }
}

@Composable
private fun StatusCard(title: String, detail: String, status: String) {
    Surface(color = SurfaceRaised, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, color = PrimaryText, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text(detail, color = SecondaryText, fontSize = 12.sp)
            }
            Text(status, color = Cyan, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun PlaceholderScreen(title: String, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().background(Void).padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(title.uppercase(), color = Cyan, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        Spacer(Modifier.height(12.dp))
        Text("Раздел предусмотрен архитектурой NEXUS. Реальные функции будут обозначаться только после подключения и проверки.", color = SecondaryText)
    }
}
