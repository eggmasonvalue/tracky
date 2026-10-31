package dev.eggmasonvalue.tracky.core.data

/** Exercises the production ledger repository without an Android SQLite runtime. */
internal class TestTrackyDao : TrackyDao {
    val entries = linkedMapOf<String, ImportLedgerEntity>()
    var state: SyncStateEntity? = null

    override suspend fun findImport(clientRecordId: String) = entries[clientRecordId]
    override suspend fun upsertImport(entry: ImportLedgerEntity) {
        entries[entry.clientRecordId] = entry
    }
    override suspend fun findSyncState(stateId: String) = state?.takeIf { it.stateId == stateId }
    override suspend fun upsertSyncState(state: SyncStateEntity) { this.state = state }
    override suspend fun updateCursor(stateId: String, cursor: String?) {
        state = state?.takeIf { it.stateId == stateId }?.copy(cursor = cursor)
    }
}
