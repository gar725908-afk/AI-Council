package ai.hybrid.commandcenter

import ai.hybrid.commandcenter.mesh.AndroidKeystoreIdentity
import ai.hybrid.commandcenter.mesh.MeshFingerprint
import ai.hybrid.commandcenter.mesh.MeshInvite
import ai.hybrid.commandcenter.mesh.MeshNode
import ai.hybrid.commandcenter.mesh.MeshOp
import ai.hybrid.commandcenter.mesh.MeshPolicy
import ai.hybrid.commandcenter.mesh.MeshProfile
import ai.hybrid.commandcenter.mesh.MeshRuntime
import ai.hybrid.commandcenter.mesh.MeshService
import ai.hybrid.commandcenter.mesh.MeshTaskRecord
import ai.hybrid.commandcenter.mesh.PairingState
import ai.hybrid.commandcenter.mesh.TrustedPeer
import ai.hybrid.commandcenter.ui.Cyber
import ai.hybrid.commandcenter.ui.CyberText
import ai.hybrid.commandcenter.ui.HudButton
import ai.hybrid.commandcenter.ui.HudButtonStyle
import ai.hybrid.commandcenter.ui.HudChip
import ai.hybrid.commandcenter.ui.HudDivider
import ai.hybrid.commandcenter.ui.HudLabel
import ai.hybrid.commandcenter.ui.HudPanel
import ai.hybrid.commandcenter.ui.HudSectionTitle
import ai.hybrid.commandcenter.ui.HudTextField
import ai.hybrid.commandcenter.ui.NexIcons
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Switch
import ai.hybrid.commandcenter.ui.HudRow
import ai.hybrid.commandcenter.ui.NexusTypography
import ai.hybrid.commandcenter.ui.SegmentedTabs
import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * NEXUS Mesh Center: this phone's identity and worker, pairing with the
 * owner's other phones, the trusted devices, real tasks and the security log.
 * Every value on this screen comes from a handshake, a message or a measurement.
 */
@Composable
fun MeshCenterScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val node = remember { runCatching { MeshRuntime.node(context) } }
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        node.fold(
            onSuccess = { MeshContent(it) },
            onFailure = {
                HudPanel(Modifier.fillMaxWidth(), accent = Cyber.Red, title = "Mesh недоступен", index = "KEY") {
                    Text(
                        "Android Keystore не выдал ключ устройства: " + (it.message ?: it.javaClass.simpleName),
                        style = NexusTypography.bodyMedium, color = Cyber.Text
                    )
                }
            }
        )
        Spacer(Modifier.height(24.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MeshContent(node: MeshNode) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val port by node.port.collectAsState()
    val pairing by node.pairing.collectAsState()
    val sessions by node.sessions.collectAsState()
    val tasks by node.tasks.collectAsState()
    val peers by node.peers.collectAsState()
    val discovered by MeshRuntime.discovered.collectAsState()
    var name by remember { mutableStateOf(MeshRuntime.deviceName(context)) }
    var profile by remember { mutableStateOf(MeshPolicy.load(context).profile) }
    var invite by remember { mutableStateOf<MeshInvite?>(null) }
    var joinLink by remember { mutableStateOf("") }
    var joinError by remember { mutableStateOf<String?>(null) }
    var joining by remember { mutableStateOf(false) }
    val keystore = node.identity is AndroidKeystoreIdentity
    val hardware = remember { runCatching { (node.identity as? AndroidKeystoreIdentity)?.hardwareBacked() == true }.getOrDefault(false) }
    val tls13 = remember {
        runCatching { javax.net.ssl.SSLContext.getDefault().supportedSSLParameters.protocols.contains("TLSv1.3") }.getOrDefault(false)
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    // ------------------------------------------------------------ this phone
    HudSectionTitle("Этот телефон", accent = Cyber.Magenta, trailing = "ノード")
    HudPanel(Modifier.fillMaxWidth().testTag("mesh-node"), accent = Cyber.Magenta, title = node.identity.deviceId, index = if (port != null) "ON" else "OFF") {
        HudTextField(name, { name = it.take(40) }, label = "Имя в сети", placeholder = "Мой телефон", accent = Cyber.Magenta,
            onDone = { MeshRuntime.saveDeviceName(context, name) }, testTag = "mesh-name")
        HudLabel("Ключ: " + MeshFingerprint.pretty(node.identity.fingerprint), color = Cyber.TextDim, maxLines = 2)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            HudChip(
                when {
                    hardware -> "Ключ в защищённом чипе"
                    keystore -> "Ключ в Android Keystore"
                    else -> "Программный ключ (тест)"
                },
                if (hardware) Cyber.Green else if (keystore) Cyber.Cyan else Cyber.Amber
            )
            HudChip(if (tls13) "mTLS 1.3" else "mTLS 1.2", Cyber.Cyan)
            HudChip(if (port != null) "Слушает :$port" else "Узел выключен", if (port != null) Cyber.Green else Cyber.TextDim)
        }
        if (port != null) {
            val addresses = remember(port) { MeshRuntime.addresses(port!!) }
            HudLabel(if (addresses.isEmpty()) "Нет адреса Wi-Fi/Tailscale" else "Адреса: " + addresses.joinToString(", "), color = Cyber.TextDim, maxLines = 3)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (port == null) {
                HudButton("Включить узел", {
                    MeshRuntime.saveDeviceName(context, name)
                    if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    MeshService.start(context)
                }, Modifier.weight(1f).testTag("mesh-start"), icon = NexIcons.Power, accent = Cyber.Magenta)
            } else {
                HudButton("Выключить", { MeshService.stop(context) }, Modifier.weight(1f).testTag("mesh-stop"),
                    style = HudButtonStyle.Secondary, icon = NexIcons.CircleStop, accent = Cyber.Magenta)
            }
        }
        Text(
            "Узел принимает только сопряжённые телефоны и только из частных сетей (Wi-Fi, Tailscale). Выполнять он умеет лишь три операции: состояние, ответ модели и замер скорости. Удалённой командной строки нет.",
            style = NexusTypography.bodySmall, color = Cyber.TextFaint
        )
    }

    // ------------------------------------------------------------ resources
    HudPanel(Modifier.fillMaxWidth(), accent = Cyber.Violet, title = "Сколько ресурсов отдавать", index = "GOV") {
        val profiles = MeshProfile.entries
        SegmentedTabs(
            options = profiles.map { it.title },
            selected = profiles.indexOf(profile),
            onSelect = {
                profile = profiles[it]
                MeshPolicy.save(context, profile)
            }
        )
        Text(profile.note.replaceFirstChar { it.uppercase() } + ".", style = NexusTypography.bodySmall, color = Cyber.TextDim)
        var allowCloud by remember { mutableStateOf(MeshPolicy.load(context).allowCloudCore) }
        HudRow(
            "Облачное ядро для чужих задач",
            subtitle = if (allowCloud) "Разрешено: задачи других телефонов тратят ключ API этого телефона и уходят в облако."
            else "Запрещено: чужие задачи выполняет только модель на телефоне или ПК в своей сети.",
            icon = NexIcons.Cloud, accent = if (allowCloud) Cyber.Amber else Cyber.Violet
        ) {
            Switch(
                checked = allowCloud,
                onCheckedChange = { allowCloud = it; MeshPolicy.saveAllowCloud(context, it) },
                modifier = Modifier.testTag("mesh-allow-cloud"),
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Cyber.OnNeon, checkedTrackColor = Cyber.Amber,
                    uncheckedThumbColor = Cyber.TextDim, uncheckedTrackColor = Cyber.Panel
                )
            )
        }
    }

    // ------------------------------------------------------------ pairing
    HudSectionTitle("Сопряжение", accent = Cyber.Yellow, trailing = "接続")
    val current = pairing
    if (current != null) {
        PairingPanel(current, onConfirm = { node.confirmPairing() }, onReject = { node.rejectPairing() }, onClose = { node.clearPairing() })
    }
    val activeInvite = invite
    if (activeInvite != null && current?.status != PairingState.Status.PAIRED) {
        InvitePanel(activeInvite, onClose = { invite = null; node.cancelInvites() })
    } else if (current == null) {
        HudPanel(Modifier.fillMaxWidth(), accent = Cyber.Yellow, title = "Добавить свой телефон", index = "PAIR") {
            Text(
                "На первом телефоне нажми «Пригласить» — появится QR-код и ссылка. На втором отсканируй код или вставь ссылку. Потом сверь шесть цифр на обоих экранах и подтверди на обоих.",
                style = NexusTypography.bodySmall, color = Cyber.TextDim
            )
            HudButton("Пригласить телефон", {
                val p = port
                if (p == null) {
                    joinError = "Сначала включи узел — приглашению нужен адрес этого телефона."
                } else {
                    invite = node.createInvite(MeshRuntime.addresses(p))
                }
            }, Modifier.fillMaxWidth().testTag("mesh-invite"), icon = NexIcons.Plus, accent = Cyber.Yellow)
            HudDivider(accent = Cyber.Yellow)
            HudTextField(joinLink, { joinLink = it.take(2_000) }, label = "Ссылка приглашения",
                placeholder = "nexus-mesh:1:…", accent = Cyber.Yellow, testTag = "mesh-link")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HudButton("Сканировать QR", {
                    scanQr(context, onResult = { joinLink = it }, onError = { joinError = it })
                }, Modifier.weight(1f), style = HudButtonStyle.Secondary, icon = NexIcons.ScanLine, accent = Cyber.Yellow, height = 44.dp)
                HudButton(if (joining) "Соединяюсь…" else "Подключиться", {
                    joinError = null
                    joining = true
                    scope.launch {
                        runCatching { node.join(MeshInvite.parse(joinLink)) }
                            .onFailure { joinError = it.message ?: "Не удалось соединиться." }
                        joining = false
                    }
                }, Modifier.weight(1f).testTag("mesh-join"), icon = NexIcons.Zap, accent = Cyber.Yellow, enabled = !joining && joinLink.isNotBlank(), height = 44.dp)
            }
        }
    }
    joinError?.let { Text(it, style = NexusTypography.bodyMedium, color = Cyber.Red, modifier = Modifier.testTag("mesh-error")) }

    // ------------------------------------------------------------ devices
    HudSectionTitle("Мои устройства", accent = Cyber.Cyan, trailing = "${peers.count { it.active }}")
    if (peers.isEmpty()) {
        HudLabel("Пока ни одного — пригласи второй телефон", color = Cyber.TextFaint)
    }
    peers.forEach { peer ->
        PeerCard(
            node = node,
            peer = peer,
            online = sessions[peer.fingerprint],
            seenOnLan = discovered.containsKey(peer.fingerprint.take(16))
        )
    }

    // ------------------------------------------------------------ tasks
    if (tasks.isNotEmpty()) {
        HudSectionTitle("Задачи", accent = Cyber.Violet, trailing = "${tasks.size}")
        tasks.take(12).forEach { TaskRow(it) }
    }

    // ------------------------------------------------------------ security log
    val log = remember(peers, pairing, tasks.size) { runCatching { ai.hybrid.commandcenter.mesh.AndroidTrustStore(context).auditLog().takeLast(8).reversed() }.getOrDefault(emptyList()) }
    if (log.isNotEmpty()) {
        HudSectionTitle("Журнал безопасности", accent = Cyber.TextDim)
        val format = remember { SimpleDateFormat("dd.MM HH:mm", Locale.ROOT) }
        log.forEach { event ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(format.format(Date(event.at)), style = CyberText.Label, color = Cyber.TextFaint)
                Text(event.text, style = NexusTypography.bodySmall, color = Cyber.TextDim, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun PairingPanel(state: PairingState, onConfirm: () -> Unit, onReject: () -> Unit, onClose: () -> Unit) {
    val accent = when (state.status) {
        PairingState.Status.WAITING -> Cyber.Yellow
        PairingState.Status.PAIRED -> Cyber.Green
        PairingState.Status.FAILED -> Cyber.Red
    }
    HudPanel(Modifier.fillMaxWidth().testTag("mesh-pairing"), accent = accent,
        title = if (state.incoming) "Запрос от «${state.peerName}»" else "Подключение к «${state.peerName}»", index = state.peerDeviceId) {
        when (state.status) {
            PairingState.Status.WAITING -> {
                Text("Сверь код на обоих телефонах:", style = NexusTypography.bodyMedium, color = Cyber.TextDim)
                Text(state.sas, style = CyberText.Hero.copy(fontSize = 40.sp), modifier = Modifier.fillMaxWidth().testTag("mesh-sas"),
                    textAlign = TextAlign.Center)
                HudLabel(
                    when {
                        state.localConfirmed && !state.remoteConfirmed -> "Ты подтвердил — ждём подтверждения на другом телефоне"
                        state.remoteConfirmed && !state.localConfirmed -> "Другой телефон уже подтвердил"
                        else -> "Если цифры отличаются — отклони: кто-то вмешался в соединение"
                    },
                    color = accent, maxLines = 2
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    HudButton("Код совпадает", onConfirm, Modifier.weight(1f).testTag("mesh-confirm"), icon = NexIcons.Check,
                        accent = Cyber.Green, enabled = !state.localConfirmed)
                    HudButton("Отклонить", onReject, Modifier.testTag("mesh-reject"), style = HudButtonStyle.Danger)
                }
            }
            PairingState.Status.PAIRED -> {
                Text("Сопряжено: «${state.peerName}» теперь доверенное устройство. Канал — взаимный TLS с закреплёнными ключами.",
                    style = NexusTypography.bodyMedium, color = Cyber.Text, modifier = Modifier.testTag("mesh-paired"))
                HudButton("Готово", onClose, style = HudButtonStyle.Secondary, accent = Cyber.Green, height = 40.dp)
            }
            PairingState.Status.FAILED -> {
                Text(state.error ?: "Сопряжение не удалось.", style = NexusTypography.bodyMedium, color = Cyber.Text)
                HudButton("Закрыть", onClose, style = HudButtonStyle.Secondary, accent = Cyber.Red, height = 40.dp)
            }
        }
    }
}

@Composable
private fun InvitePanel(invite: MeshInvite, onClose: () -> Unit) {
    val context = LocalContext.current
    val link = remember(invite) { invite.toLink() }
    var left by remember(invite) { mutableStateOf(invite.expiresAtEpochSec - System.currentTimeMillis() / 1000) }
    LaunchedEffect(invite) {
        while (left > 0) {
            // A wall-clock tick off the UI dispatcher: the countdown follows real time, and UI tests
            // (whose clock skips delays) are not kept busy by a loop that never catches up.
            withContext(Dispatchers.Default) { delay(1_000) }
            left = invite.expiresAtEpochSec - System.currentTimeMillis() / 1000
        }
    }
    HudPanel(Modifier.fillMaxWidth().testTag("mesh-invite-panel"), accent = Cyber.Yellow, title = "Приглашение", index = if (left > 0) "${left / 60}:${"%02d".format(left % 60)}" else "ИСТЕКЛО") {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { QrCode(link, Modifier.size(220.dp)) }
        Text(
            "Отсканируй этот код вторым телефоном в NEXUS → Mesh → «Сканировать QR». Или отправь себе ссылку. Она одноразовая и действует 10 минут, а без подтверждения шести цифр на обоих экранах доступ всё равно не откроется.",
            style = NexusTypography.bodySmall, color = Cyber.TextDim
        )
        Text(link, style = CyberText.Label, color = Cyber.TextFaint, maxLines = 3, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag("mesh-invite-link"))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HudButton("Копировать", {
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("NEXUS Mesh", link))
            }, Modifier.weight(1f), style = HudButtonStyle.Secondary, icon = NexIcons.Copy, accent = Cyber.Yellow, height = 42.dp)
            HudButton("Отправить", {
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link), "Ссылка NEXUS Mesh")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }, Modifier.weight(1f), style = HudButtonStyle.Secondary, icon = NexIcons.Send, accent = Cyber.Yellow, height = 42.dp)
        }
        HudButton("Отменить приглашение", onClose, style = HudButtonStyle.Ghost, accent = Cyber.TextDim, height = 38.dp)
    }
}

/** QR code drawn from ZXing's bit matrix: no bitmap, crisp at any size. */
@Composable
private fun QrCode(text: String, modifier: Modifier) {
    val matrix = remember(text) {
        runCatching {
            QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0,
                mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 2))
        }.getOrNull()
    }
    Canvas(modifier.background(Color.White).testTag("mesh-qr")) {
        val m = matrix ?: return@Canvas
        val cell = size.minDimension / m.width
        for (y in 0 until m.height) for (x in 0 until m.width) {
            if (m.get(x, y)) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
        }
    }
}

private fun scanQr(context: Context, onResult: (String) -> Unit, onError: (String) -> Unit) {
    runCatching {
        com.google.mlkit.vision.codescanner.GmsBarcodeScanning.getClient(context).startScan()
            .addOnSuccessListener { barcode -> barcode.rawValue?.let(onResult) ?: onError("QR-код пустой.") }
            .addOnFailureListener { onError("Сканер недоступен: " + (it.message ?: "нет сервисов Google") + ". Вставь ссылку вручную.") }
    }.onFailure { onError("Сканер недоступен на этом телефоне — вставь ссылку вручную.") }
}

@Composable
private fun PeerCard(node: MeshNode, peer: TrustedPeer, online: ai.hybrid.commandcenter.mesh.SessionInfo?, seenOnLan: Boolean) {
    val scope = rememberCoroutineScope()
    var prompt by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val accent = when {
        !peer.active -> Cyber.TextFaint
        online != null -> Cyber.Green
        seenOnLan -> Cyber.Cyan
        else -> Cyber.Violet
    }
    HudPanel(Modifier.fillMaxWidth().testTag("mesh-peer-" + peer.deviceId), accent = accent, title = peer.name, index = peer.deviceId) {
        HudLabel(
            when {
                !peer.active -> "Доверие отозвано"
                online != null -> "На связи · " + online.tls + (online.rttMs?.let { " · $it мс" } ?: "") + " · " + online.address
                seenOnLan -> "Найден в этой Wi-Fi сети"
                peer.lastSeenAt > 0 -> "Не в сети · был " + SimpleDateFormat("dd.MM HH:mm", Locale.ROOT).format(Date(peer.lastSeenAt))
                else -> "Не в сети"
            },
            color = accent, maxLines = 2
        )
        online?.capabilities?.let { caps ->
            HudLabel(
                "Ядро: " + caps.optString("core") + " · ОЗУ свободно " + caps.optLong("ramFreeMb") + " МБ · заряд " +
                    caps.opt("battery") + "% · режим " + caps.optString("profile"),
                color = Cyber.TextDim, maxLines = 3
            )
        }
        if (peer.active) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HudButton(if (online != null) "Проверить связь" else "Подключить", {
                    busy = true
                    scope.launch {
                        result = runCatching {
                            if (online == null) node.connect(peer, MeshRuntime.addressesFor(peer))
                            val rtt = node.ping(peer.fingerprint)
                            if (rtt != null) "Связь есть: $rtt мс туда-обратно" else "Ответа нет"
                        }.getOrElse { it.message ?: "Не удалось соединиться." }
                        busy = false
                    }
                }, Modifier.weight(1f).testTag("mesh-connect-" + peer.deviceId), style = HudButtonStyle.Secondary,
                    icon = NexIcons.Activity, accent = accent, enabled = !busy, height = 40.dp)
                HudButton("Отозвать", { node.revoke(peer.fingerprint) }, style = HudButtonStyle.Danger, height = 40.dp)
            }
            HudTextField(prompt, { prompt = it.take(4_000) }, label = "Задача для этого телефона",
                placeholder = "Например: кратко объясни, что такое NEXUS", accent = accent, testTag = "mesh-task-" + peer.deviceId)
            HudButton(if (busy) "Выполняется…" else "Выполнить там", {
                busy = true
                result = null
                scope.launch {
                    val outcome = node.runRemote(peer.fingerprint, MeshOp.INFERENCE_RUN, JSONObject().put("prompt", prompt))
                    result = if (outcome.ok) outcome.output else "Отказ: " + outcome.error
                    busy = false
                }
            }, Modifier.fillMaxWidth().testTag("mesh-run-" + peer.deviceId), icon = NexIcons.Send, accent = accent,
                enabled = !busy && prompt.isNotBlank(), height = 42.dp)
        }
        result?.let { Text(it, style = NexusTypography.bodyMedium, color = Cyber.Text, modifier = Modifier.testTag("mesh-result")) }
    }
}

@Composable
private fun TaskRow(task: MeshTaskRecord) {
    val color = when (task.status) {
        MeshTaskRecord.Status.DONE -> Cyber.Green
        MeshTaskRecord.Status.REJECTED, MeshTaskRecord.Status.FAILED -> Cyber.Red
        MeshTaskRecord.Status.CANCELLED -> Cyber.TextDim
        else -> Cyber.Yellow
    }
    Row(
        Modifier.fillMaxWidth().testTag(
            "mesh-task-" + (if (task.outgoing) "out-" else "in-") + task.op.name.lowercase() + "-" + task.status.name.lowercase()
        ),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top
    ) {
        Text(if (task.outgoing) "→" else "←", style = CyberText.Title, color = color)
        Column(Modifier.weight(1f)) {
            Text(task.op.title + " · " + task.peerName, style = CyberText.Heading.copy(fontSize = 13.sp), color = Cyber.Text, maxLines = 1)
            val duration = task.finishedAt?.let { " · " + String.format(Locale.ROOT, "%.1f с", (it - task.startedAt) / 1000.0) } ?: ""
            HudLabel(task.status.title + duration + if (task.note.isNotBlank()) " · " + task.note else "", color = color, maxLines = 2)
            task.output?.let { Text(it.take(160), style = NexusTypography.bodySmall, color = Cyber.TextDim, maxLines = 3) }
        }
    }
}
