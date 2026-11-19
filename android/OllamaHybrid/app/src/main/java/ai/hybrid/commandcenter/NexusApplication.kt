package ai.hybrid.commandcenter

import android.app.Application
import ai.hybrid.commandcenter.nexus.MissionJournal

class NexusApplication : Application() {
    lateinit var missionJournal: MissionJournal
        private set

    override fun onCreate() {
        super.onCreate()
        missionJournal = MissionJournal(this).also {
            it.markAbandonedMissionsInterrupted()
        }
    }
}
