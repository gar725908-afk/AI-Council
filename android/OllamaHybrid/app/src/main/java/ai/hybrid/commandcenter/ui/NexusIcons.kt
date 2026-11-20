package ai.hybrid.commandcenter.ui

import ai.hybrid.commandcenter.R
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Line icons (Lucide, ISC licence — see third_party_licenses/), shipped as
 * tintable vector drawables generated from the original SVGs.
 */
object NexIcons {
    @DrawableRes val Activity = R.drawable.lu_activity
    @DrawableRes val Archive = R.drawable.lu_archive
    @DrawableRes val ArrowLeft = R.drawable.lu_arrow_left
    @DrawableRes val ArrowUp = R.drawable.lu_arrow_up
    @DrawableRes val AudioLines = R.drawable.lu_audio_lines
    @DrawableRes val BadgeCheck = R.drawable.lu_badge_check
    @DrawableRes val BatteryCharging = R.drawable.lu_battery_charging
    @DrawableRes val BatteryFull = R.drawable.lu_battery_full
    @DrawableRes val BatteryLow = R.drawable.lu_battery_low
    @DrawableRes val BatteryMedium = R.drawable.lu_battery_medium
    @DrawableRes val BookOpen = R.drawable.lu_book_open
    @DrawableRes val Bot = R.drawable.lu_bot
    @DrawableRes val Boxes = R.drawable.lu_boxes
    @DrawableRes val Brain = R.drawable.lu_brain
    @DrawableRes val Bug = R.drawable.lu_bug
    @DrawableRes val ChartColumn = R.drawable.lu_chart_column
    @DrawableRes val ChevronRight = R.drawable.lu_chevron_right
    @DrawableRes val CircleAlert = R.drawable.lu_circle_alert
    @DrawableRes val CircleCheck = R.drawable.lu_circle_check
    @DrawableRes val CircleX = R.drawable.lu_circle_x
    @DrawableRes val Clock = R.drawable.lu_clock
    @DrawableRes val Cloud = R.drawable.lu_cloud
    @DrawableRes val CloudOff = R.drawable.lu_cloud_off
    @DrawableRes val CodeXml = R.drawable.lu_code_xml
    @DrawableRes val Copy = R.drawable.lu_copy
    @DrawableRes val Cpu = R.drawable.lu_cpu
    @DrawableRes val Download = R.drawable.lu_download
    @DrawableRes val ExternalLink = R.drawable.lu_external_link
    @DrawableRes val Eye = R.drawable.lu_eye
    @DrawableRes val FileText = R.drawable.lu_file_text
    @DrawableRes val FolderOpen = R.drawable.lu_folder_open
    @DrawableRes val Gauge = R.drawable.lu_gauge
    @DrawableRes val GitPullRequest = R.drawable.lu_git_pull_request
    @DrawableRes val Globe = R.drawable.lu_globe
    @DrawableRes val HardDrive = R.drawable.lu_hard_drive
    @DrawableRes val History = R.drawable.lu_history
    @DrawableRes val House = R.drawable.lu_house
    @DrawableRes val KeyRound = R.drawable.lu_key_round
    @DrawableRes val Layers = R.drawable.lu_layers
    @DrawableRes val Lightbulb = R.drawable.lu_lightbulb
    @DrawableRes val ListChecks = R.drawable.lu_list_checks
    @DrawableRes val Lock = R.drawable.lu_lock
    @DrawableRes val MemoryStick = R.drawable.lu_memory_stick
    @DrawableRes val MessageSquare = R.drawable.lu_message_square
    @DrawableRes val MessageSquareWarning = R.drawable.lu_message_square_warning
    @DrawableRes val Mic = R.drawable.lu_mic
    @DrawableRes val Moon = R.drawable.lu_moon
    @DrawableRes val Orbit = R.drawable.lu_orbit
    @DrawableRes val Play = R.drawable.lu_play
    @DrawableRes val Plug = R.drawable.lu_plug
    @DrawableRes val Plus = R.drawable.lu_plus
    @DrawableRes val Power = R.drawable.lu_power
    @DrawableRes val RefreshCw = R.drawable.lu_refresh_cw
    @DrawableRes val RotateCcw = R.drawable.lu_rotate_ccw
    @DrawableRes val Route = R.drawable.lu_route
    @DrawableRes val ScanText = R.drawable.lu_scan_text
    @DrawableRes val Search = R.drawable.lu_search
    @DrawableRes val Send = R.drawable.lu_send
    @DrawableRes val Server = R.drawable.lu_server
    @DrawableRes val Settings2 = R.drawable.lu_settings_2
    @DrawableRes val ShieldAlert = R.drawable.lu_shield_alert
    @DrawableRes val ShieldCheck = R.drawable.lu_shield_check
    @DrawableRes val SlidersHorizontal = R.drawable.lu_sliders_horizontal
    @DrawableRes val Smartphone = R.drawable.lu_smartphone
    @DrawableRes val Sparkles = R.drawable.lu_sparkles
    @DrawableRes val Square = R.drawable.lu_square
    @DrawableRes val Terminal = R.drawable.lu_terminal
    @DrawableRes val Thermometer = R.drawable.lu_thermometer
    @DrawableRes val TriangleAlert = R.drawable.lu_triangle_alert
    @DrawableRes val User = R.drawable.lu_user
    @DrawableRes val Waypoints = R.drawable.lu_waypoints
    @DrawableRes val Wifi = R.drawable.lu_wifi
    @DrawableRes val WifiOff = R.drawable.lu_wifi_off
    @DrawableRes val Wrench = R.drawable.lu_wrench
    @DrawableRes val X = R.drawable.lu_x
    @DrawableRes val Zap = R.drawable.lu_zap
}

@Composable
fun NexIcon(
    @DrawableRes icon: Int,
    modifier: Modifier = Modifier,
    tint: Color = NexusColors.TextPrimary,
    size: Dp = 20.dp,
    contentDescription: String? = null
) {
    Icon(
        painter = painterResource(icon),
        contentDescription = contentDescription,
        modifier = modifier.size(size),
        tint = tint
    )
}
