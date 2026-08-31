package dev.eggmasonvalue.tracky

import android.app.Application
import dev.eggmasonvalue.tracky.core.data.RoomSessionHistoryRepository
import dev.eggmasonvalue.tracky.core.data.SessionHistoryRepository
import dev.eggmasonvalue.tracky.core.data.TrackyDatabase
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class TrackyApplication : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    lateinit var database: TrackyDatabase
        private set

    lateinit var sessionHistoryRepository: SessionHistoryRepository
        private set

    override fun onCreate() {
        super.onCreate()
        database = TrackyDatabase.build(this)
        sessionHistoryRepository = RoomSessionHistoryRepository(database)

        val recoveryCutoff = Instant.now()
        applicationScope.launch {
            sessionHistoryRepository.abortOpenSessionsStartedBefore(recoveryCutoff)
        }
    }
}
