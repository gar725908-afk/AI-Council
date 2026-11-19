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
        val destinations = listOf("Главная", "AI Studio", "Council", "Модели", "Устройство", "Центр")
        Scaffold(
            containerColor = Void,
            bottomBar = {
                NavigationBar(containerColor = SurfaceDeep) {
                    destinations.forEachIndexed { index, label ->
                        NavigationBarItem(
                            selected = selected == index,
                            onClick = { selected = index },
                            icon = { Text(listOf("⌂", "✧", "◎", "◈", "▣", "＋")[index], fontSize = 18.sp) },
                            label = { Text(label, fontSize = 10.sp) }
                        )
                    }
                }
            }
        ) { padding ->
            when (selected) {
                0 -> CommandDeckScreen(onNavigate = { selected = it }, modifier = Modifier.padding(padding))
                1 -> AiStudioScreen(councilMode = false, modifier = Modifier.padding(padding))
                2 -> AiStudioScreen(councilMode = true, modifier = Modifier.padding(padding))
                3 -> ModelCenterScreen(Modifier.padding(padding))
                4 -> DeviceCoreScreen(Modifier.padding(padding))
                else -> PlatformCenterScreen(Modifier.padding(padding))
            }
        }
    }
}

