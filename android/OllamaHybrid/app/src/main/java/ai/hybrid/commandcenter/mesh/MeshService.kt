package ai.hybrid.commandcenter.mesh

import ai.hybrid.commandcenter.MainActivity
import ai.hybrid.commandcenter.R
import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * Keeps the Mesh node listening while the app is in the background. It is a
 * visible foreground service with a «Остановить» action — the phone never
 * lends resources silently, and Android may still pause it (honestly shown).
 */
class MeshService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            MeshRuntime.stop(this)
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }
        // Foreground first: Android expects it promptly after startForegroundService(), even if starting the node fails.
        if (!enterForeground(notification(null))) {
            MeshRuntime.stop(this)
            stopSelf()
            return START_NOT_STICKY
        }
        val port = runCatching { MeshRuntime.start(this) }.getOrElse {
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, notification(port))
        return START_STICKY
    }

    /**
     * The owner switches the node on from the open app, which Android always allows.
     * When the system later restarts the sticky service with the app in the
     * background, Android 12+ may refuse the foreground start
     * (ForegroundServiceStartNotAllowedException). Then the node simply stays off
     * until NEXUS is opened again — no crash, no hidden background work.
     */
    private fun enterForeground(notification: Notification): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        true
    } catch (error: Exception) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && error is ForegroundServiceStartNotAllowedException) false else throw error
    }

    override fun onDestroy() {
        MeshRuntime.stop(this)
        super.onDestroy()
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
    }

    private fun notification(port: Int?): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "NEXUS Mesh", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Узел личной сети NEXUS принимает задачи доверенных телефонов"
                }
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, MeshService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL)
        else @Suppress("DEPRECATION") Notification.Builder(this)
        return builder
            .setSmallIcon(R.drawable.lu_network)
            .setContentTitle("NEXUS Mesh: узел активен")
            .setContentText(if (port == null) "Запуск узла…" else "Порт $port · задачи только от сопряжённых телефонов")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Остановить", stop).build())
            .build()
    }

    companion object {
        private const val CHANNEL = "nexus_mesh"
        private const val NOTIFICATION_ID = 4711
        private const val ACTION_STOP = "ai.hybrid.commandcenter.mesh.STOP"

        fun start(context: Context) {
            val intent = Intent(context, MeshService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, MeshService::class.java).setAction(ACTION_STOP))
        }
    }
}
