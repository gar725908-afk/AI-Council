package ai.hybrid.commandcenter

import ai.hybrid.commandcenter.nexus.ErrorJournal
import android.app.Application
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import ai.hybrid.commandcenter.nexus.MissionJournal
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class NexusApplication : Application() {
    lateinit var missionJournal: MissionJournal
        private set

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val startupRecovery = CompletableDeferred<Result<Unit>>()

    override fun onCreate() {
        super.onCreate()
        installCrashHandler()
        // The owner's chosen world (Неон-город / Космос / Аниме) before the first frame.
        runCatching { ai.hybrid.commandcenter.ui.ThemeState.load(this) }
        // Fonts/resources for PDF text extraction (Archive); cheap, one-time.
        PDFBoxResourceLoader.init(applicationContext)
        missionJournal = MissionJournal(this)
        applicationScope.launch {
            val result = runCatching {
                missionJournal.markAbandonedMissionsInterrupted()
                Unit
            }
            startupRecovery.complete(result)
        }
    }

    suspend fun awaitStartupRecovery(): Result<Unit> = startupRecovery.await()

    /** Local error journal; nothing leaves the device without the user sending it. */
    val errorJournal: ErrorJournal by lazy { ErrorJournal(this) }

    /**
     * Records an uncaught crash before handing control to the previous handler,
     * so the app still fails visibly instead of swallowing the error.
     */
    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                errorJournal.recordThrowable("crash", error, BuildConfig.VERSION_NAME)
            }
            previous?.uncaughtException(thread, error)
        }
    }
}
