package ai.hybrid.commandcenter.mesh

import ai.hybrid.commandcenter.NexusApplication
import ai.hybrid.commandcenter.androidbridge.DeviceInspector
import ai.hybrid.commandcenter.nexus.AndroidCredentialVault
import ai.hybrid.commandcenter.nexus.CompletionRequest
import ai.hybrid.commandcenter.nexus.NexusModelStorage
import ai.hybrid.commandcenter.nexus.ProviderKind
import ai.hybrid.commandcenter.nexus.ProviderMessage
import ai.hybrid.commandcenter.nexus.ProviderSettingsStore
import ai.hybrid.commandcenter.nexus.defaultProviderEndpoint
import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.StatFs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Runs this phone's AI core for trusted peers. Only the three typed operations
 * exist; the prompt goes to whatever core the owner connected (on-device model,
 * home PC or cloud) exactly like a local chat message, keys never leave.
 */
class AppMeshWorker(context: Context) : MeshWorker {
    private val appContext = context.applicationContext
    private val settings = ProviderSettingsStore(appContext)
    private val vault = AndroidCredentialVault(appContext)

    override fun status(): JSONObject {
        val diag = runCatching { DeviceInspector(appContext).diagnostics() }.getOrNull()
        val memory = ActivityManager.MemoryInfo().also {
            (appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it)
        }
        val policy = MeshPolicy.load(appContext)
        return JSONObject()
            .put("android", Build.VERSION.SDK_INT)
            .put("abi", Build.SUPPORTED_ABIS.firstOrNull() ?: "?")
            .put("cores", Runtime.getRuntime().availableProcessors())
            .put("ramTotalMb", memory.totalMem / 1_048_576)
            .put("ramFreeMb", memory.availMem / 1_048_576)
            .put("storageFreeMb", runCatching { StatFs(appContext.filesDir.absolutePath).availableBytes / 1_048_576 }.getOrDefault(-1L))
            .put("battery", diag?.batteryPercent ?: JSONObject.NULL)
            .put("charging", diag?.charging == true)
            .put("thermal", diag?.thermalStatus ?: 0)
            .put("runtimes", JSONArray(listOf("llama.cpp", "litert-lm", "ollama-lan", "cloud")))
            .put("llamaCpu", ai.hybrid.commandcenter.nexus.llamaCpuBackends(appContext) ?: "не загружен")
            .put("core", coreLabel())
            .put("profile", policy.profile.name)
            .put("acceptsTasks", policy.profile != MeshProfile.OFF)
    }

    private fun coreLabel(): String {
        val loaded = settings.load()
        val kind = runCatching { ProviderKind.valueOf(loaded.providerKind) }.getOrNull() ?: return "не подключено"
        return when (kind) {
            ProviderKind.ON_DEVICE_LITERT, ProviderKind.ON_DEVICE_LLAMA_CPP ->
                "на телефоне: " + (NexusModelStorage(appContext).activeModel()?.displayName ?: "модель не выбрана")
            ProviderKind.OLLAMA -> "ПК (Ollama): " + loaded.model
            else -> "облако: " + kind.name.lowercase() + " · " + loaded.model
        }
    }

    override fun deviceState(running: Int): MeshPolicy.DeviceState {
        val diag = runCatching { DeviceInspector(appContext).diagnostics() }.getOrNull()
        val connectivity = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val wifi = runCatching {
            connectivity.getNetworkCapabilities(connectivity.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }.getOrDefault(false)
        return MeshPolicy.DeviceState(diag?.batteryPercent, diag?.charging == true, wifi, diag?.thermalStatus ?: 0, running)
    }

    override suspend fun run(op: MeshOp, params: JSONObject, progress: (String) -> Unit): String = when (op) {
        MeshOp.NODE_STATUS -> status().toString(2)
        MeshOp.INFERENCE_RUN -> {
            val prompt = params.optString("prompt").trim()
            require(prompt.isNotEmpty()) { "Пустой запрос." }
            val policy = MeshPolicy.load(appContext)
            progress("Отвечаю: " + coreLabel())
            complete(prompt, params.optString("system").take(2_000), policy.maxPromptChars, policy.maxOutputChars, policy.allowCloudCore)
        }
        MeshOp.RUNTIME_BENCHMARK -> {
            progress("Замер скорости ядра")
            val started = System.nanoTime()
            val answer = complete(
                "Ответь одним коротким предложением: что такое NEXUS Mesh?", "", 400, 1_500, MeshPolicy.load(appContext).allowCloudCore
            )
            val seconds = (System.nanoTime() - started) / 1e9
            JSONObject()
                .put("core", coreLabel())
                .put("seconds", String.format(java.util.Locale.ROOT, "%.2f", seconds))
                .put("chars", answer.length)
                .put("charsPerSecond", String.format(java.util.Locale.ROOT, "%.1f", answer.length / seconds.coerceAtLeast(0.01)))
                .toString()
        }
    }

    private suspend fun complete(prompt: String, system: String, maxChars: Int, maxOutputChars: Int, allowCloud: Boolean): String {
        val loaded = settings.load()
        val kind = runCatching { ProviderKind.valueOf(loaded.providerKind) }.getOrNull()
            ?: throw IllegalStateException("На этом телефоне не подключено ядро ИИ.")
        val endpoint = loaded.endpoint.ifBlank { defaultProviderEndpoint(kind) }
        if (!allowCloud && !MeshPolicy.isLocalCore(kind.name, endpoint)) {
            throw IllegalStateException(
                "Ядро этого телефона — облачный API (${kind.displayName}). Владелец не разрешил тратить его на задачи сети."
            )
        }
        val engines = (appContext as? NexusApplication)?.engines
            ?: throw IllegalStateException("Движок недоступен.")
        val key = runCatching { vault.get(AndroidCredentialVault.providerKeyId(kind.name)) }.getOrNull().orEmpty()
        val messages = buildList {
            add(ProviderMessage("system", system.ifBlank { "Ты NEX — ИИ-узел личной сети NEXUS. Отвечай по существу, на языке вопроса." }))
            add(ProviderMessage("user", prompt.take(maxChars.coerceAtLeast(200))))
        }
        val request = CompletionRequest(
            endpoint = endpoint,
            model = loaded.model,
            apiKey = key,
            messages = messages
        )
        val out = StringBuilder()
        // takeWhile cancels the stream (and the generation behind it) at the owner's output limit.
        withTimeout(240_000) {
            engines.gateway(kind).stream(request).takeWhile { out.length < maxOutputChars.coerceAtLeast(200) }.collect { out.append(it) }
        }
        return out.toString().take(maxOutputChars.coerceAtLeast(200)).trim()
            .ifEmpty { throw IllegalStateException("Модель вернула пустой ответ.") }
    }
}

/**
 * The phone's single Mesh node, shared by the foreground service and the
 * Mesh Center screen, plus LAN discovery over mDNS (DNS-SD).
 */
object MeshRuntime {
    @Volatile private var node: MeshNode? = null
    private val _discovered = MutableStateFlow<Map<String, String>>(emptyMap())
    /** Fingerprint prefix (16 hex) → «host:port» seen on the local network right now. */
    val discovered: StateFlow<Map<String, String>> = _discovered
    private val discoveredMap = ConcurrentHashMap<String, String>()
    private val serviceNames = ConcurrentHashMap<String, String>()
    private var registration: NsdManager.RegistrationListener? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private var multicastLock: android.net.wifi.WifiManager.MulticastLock? = null

    @Synchronized
    fun node(context: Context): MeshNode {
        node?.let { return it }
        val app = context.applicationContext
        val identity = AndroidKeystoreIdentity.loadOrCreate(app)
        val created = MeshNode(
            identity = identity,
            trust = AndroidTrustStore(app),
            worker = AppMeshWorker(app),
            name = { deviceName(app) },
            policy = { MeshPolicy.load(app) },
            hints = { peer -> listOfNotNull(discoveredMap[peer.fingerprint.take(16)]) }
        )
        node = created
        return created
    }

    /** Tests and previews: use a prepared node instead of the Keystore-backed one. */
    @Synchronized
    fun install(prepared: MeshNode?) {
        node?.takeIf { it !== prepared }?.shutdown()
        node = prepared
    }

    fun deviceName(context: Context): String =
        context.getSharedPreferences("nexus_mesh", 0).getString("name", null)
            ?: (Build.MANUFACTURER.replaceFirstChar { it.uppercase() } + " " + Build.MODEL).take(40)

    fun saveDeviceName(context: Context, name: String) {
        context.getSharedPreferences("nexus_mesh", 0).edit().putString("name", name.trim().take(40)).apply()
    }

    fun running(): Boolean = node?.port?.value != null

    /** Starts listening and announces the node on the LAN. Called by the foreground service. */
    fun start(context: Context): Int {
        val current = node(context)
        val port = current.start(MeshProtocol.DEFAULT_PORT)
        advertise(context, current, port)
        return port
    }

    fun stop(context: Context) {
        stopDiscovery(context)
        node?.stop()
    }

    fun addresses(port: Int): List<String> = MeshAddresses.local(port)

    private fun nsd(context: Context) = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager

    private fun advertise(context: Context, node: MeshNode, port: Int) {
        if (registration != null) return
        val info = NsdServiceInfo().apply {
            serviceName = "NEXUS-" + node.identity.deviceId
            serviceType = MeshProtocol.SERVICE_TYPE
            setPort(port)
            setAttribute("id", node.identity.deviceId)
            setAttribute("fp", node.identity.fingerprint.take(16))
            setAttribute("v", MeshProtocol.VERSION.toString())
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) { registration = null }
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
        }
        runCatching {
            nsd(context).registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
            registration = listener
        }
        startDiscovery(context)
    }

    @Suppress("DEPRECATION")
    private fun startDiscovery(context: Context) {
        if (discovery != null) return
        runCatching {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            multicastLock = wifi.createMulticastLock("nexus-mesh").apply { setReferenceCounted(false); acquire() }
        }
        val manager = nsd(context)
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { discovery = null }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                serviceNames.remove(serviceInfo.serviceName)?.let { fp ->
                    discoveredMap.remove(fp)
                    _discovered.value = HashMap(discoveredMap)
                }
            }
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (!serviceInfo.serviceName.startsWith("NEXUS-")) return
                runCatching {
                    manager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
                        override fun onServiceResolved(resolved: NsdServiceInfo) {
                            val fp = resolved.attributes["fp"]?.toString(Charsets.UTF_8) ?: return
                            val host = resolved.host?.hostAddress ?: return
                            discoveredMap[fp] = (if (resolved.host is java.net.Inet6Address) "[$host]" else host) + ":" + resolved.port
                            serviceNames[serviceInfo.serviceName] = fp
                            _discovered.value = HashMap(discoveredMap)
                        }
                    })
                }
            }
        }
        runCatching {
            manager.discoverServices(MeshProtocol.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            discovery = listener
        }
    }

    private fun stopDiscovery(context: Context) {
        val manager = nsd(context)
        registration?.let { runCatching { manager.unregisterService(it) } }
        registration = null
        discovery?.let { runCatching { manager.stopServiceDiscovery(it) } }
        discovery = null
        runCatching { multicastLock?.release() }
        multicastLock = null
        discoveredMap.clear()
        serviceNames.clear()
        _discovered.value = emptyMap()
    }

    /** Addresses to try for a peer: what mDNS sees now first, then the last known ones. */
    fun addressesFor(peer: TrustedPeer): List<String> =
        listOfNotNull(discoveredMap[peer.fingerprint.take(16)]) + peer.addresses
}
