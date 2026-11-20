package ai.hybrid.commandcenter

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
}
