package ai.hybrid.commandcenter.androidbridge

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import rikka.shizuku.Shizuku

/**
 * Shizuku transport (handoff section 7): ADB-level privileges over a Binder
 * connection, with no root required.
 *
 * Honesty contract, enforced by tests:
 * - the mere presence of the Shizuku app grants nothing; only a live binder
 *   plus a granted permission counts as a privilege;
 * - privileged commands come from the same fixed enum as the root path —
 *   a model can never supply shell text;
 * - every state is reported with the reason, so the UI never shows a
 *   capability that would fail when used.
 */
object ShizukuTransport {

    enum class State {
        /** The Shizuku app is not installed at all. */
        NOT_INSTALLED,

        /** Installed, but its service is not running (user has not started it). */
        SERVICE_NOT_RUNNING,

        /** Service is running, but the user has not granted NEXUS permission yet. */
        PERMISSION_REQUIRED,

        /** Granted and usable right now. */
        READY,

        /** The running Shizuku predates the Binder API this app uses. */
        VERSION_TOO_OLD
    }

    data class Status(val state: State, val detail: String) {
        val usable: Boolean get() = state == State.READY
    }

    const val PERMISSION_REQUEST_CODE = 7301
    private const val MIN_SUPPORTED_API = 11

    /** Pure mapping of observed facts to a state, so every branch is tested. */
    fun classify(
        appInstalled: Boolean,
        binderAlive: Boolean,
        preV11: Boolean,
        permissionGranted: Boolean
    ): Status = when {
        !appInstalled -> Status(
            State.NOT_INSTALLED,
            "Приложение Shizuku не установлено. Без него ADB-привилегии недоступны."
        )
        !binderAlive -> Status(
            State.SERVICE_NOT_RUNNING,
            "Shizuku установлен, но служба не запущена. Запустите её в приложении Shizuku."
        )
        preV11 -> Status(
            State.VERSION_TOO_OLD,
            "Версия Shizuku слишком старая: нужен Binder API $MIN_SUPPORTED_API или новее."
        )
        !permissionGranted -> Status(
            State.PERMISSION_REQUIRED,
            "Служба работает, но NEXUS ещё не получил разрешение Shizuku."
        )
        else -> Status(State.READY, "Shizuku подключён: доступны привилегии уровня ADB.")
    }

    /** Observes the real Shizuku state on this device. */
    fun status(appInstalled: Boolean): Status {
        if (!appInstalled) return classify(false, false, false, false)
        val binderAlive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        if (!binderAlive) return classify(true, false, false, false)
        val preV11 = runCatching { Shizuku.isPreV11() }.getOrDefault(false)
        if (preV11) return classify(true, true, true, false)
        val granted = runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        return classify(true, true, false, granted)
    }

    /** Asks Shizuku for permission; the user answers in Shizuku's own dialog. */
    fun requestPermission() {
        runCatching { Shizuku.requestPermission(PERMISSION_REQUEST_CODE) }
    }

    /**
     * Runs one fixed privileged command inside the Shizuku process.
     *
     * Only the command **id** crosses the Binder boundary, and the privileged
     * service refuses any id outside the enum, so no shell text can ever be
     * injected from the app process.
     */
    fun run(
        context: Context,
        command: RootShellExecutor.PrivilegedCommand,
        timeoutSeconds: Long = 15
    ): RootShellExecutor.ShellOutcome {
        val bounded = timeoutSeconds.coerceIn(1L, 60L)
        val args = Shizuku.UserServiceArgs(
            ComponentName(context.packageName, NexusPrivilegedService::class.java.name)
        )
            .daemon(false)
            .processNameSuffix("nexus-privileged")
            .debuggable(false)
            .version(SERVICE_VERSION)

        val latch = CountDownLatch(1)
        var service: INexusPrivilegedService? = null
        var bindError: String? = "Привилегированная служба не поднялась."

        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                if (binder != null && binder.pingBinder()) {
                    service = INexusPrivilegedService.Stub.asInterface(binder)
                    bindError = null
                } else {
                    bindError = "Привилегированная служба вернула мёртвый Binder."
                }
                latch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                service = null
            }
        }

        return try {
            Shizuku.bindUserService(args, connection)
            if (!latch.await(BIND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                return RootShellExecutor.ShellOutcome(
                    false, null, "", "Shizuku не поднял привилегированную службу за $BIND_TIMEOUT_SECONDS с."
                )
            }
            val remote = service
                ?: return RootShellExecutor.ShellOutcome(false, null, "", bindError.orEmpty())
            NexusPrivilegedService.parseReply(remote.runFixedCommand(command.id, bounded))
        } catch (error: Throwable) {
            RootShellExecutor.ShellOutcome(
                false, null, "",
                "Shizuku не выполнил команду: " + (error.message ?: error.javaClass.simpleName)
            )
        } finally {
            runCatching { Shizuku.unbindUserService(args, connection, true) }
        }
    }

    private const val SERVICE_VERSION = 1
    private const val BIND_TIMEOUT_SECONDS = 12L
}
