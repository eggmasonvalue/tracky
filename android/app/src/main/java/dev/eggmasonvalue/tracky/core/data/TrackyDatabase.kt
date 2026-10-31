package dev.eggmasonvalue.tracky.core.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ImportLedgerEntity::class, SyncStateEntity::class],
    version = 3,
    exportSchema = true,
)
@TypeConverters(TrackyTypeConverters::class)
abstract class TrackyDatabase : RoomDatabase() {
    internal abstract fun trackyDao(): TrackyDao

    companion object {
        const val DATABASE_NAME: String = "tracky-import-ledger.db"

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE import_ledger ADD COLUMN health_connect_record_ids TEXT NOT NULL DEFAULT '[]'")
                val converters = TrackyTypeConverters()
                db.query("SELECT client_record_id, health_connect_record_id FROM import_ledger").use { rows ->
                    while (rows.moveToNext()) {
                        val ids = if (rows.isNull(1)) emptyList() else listOf(rows.getString(1))
                        db.execSQL(
                            "UPDATE import_ledger SET health_connect_record_ids = ? WHERE client_record_id = ?",
                            arrayOf(converters.recordIdsToStorage(ids), rows.getString(0)),
                        )
                    }
                }
                db.execSQL("ALTER TABLE import_ledger DROP COLUMN health_connect_record_id")
                db.execSQL("ALTER TABLE sync_state ADD COLUMN derivation_version INTEGER NOT NULL DEFAULT 0")
                // v1 cursor/terminal entries must not suppress the first complete v2 replay.
                db.execSQL("UPDATE sync_state SET cursor = NULL")
            }
        }

        fun build(context: Context): TrackyDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                TrackyDatabase::class.java,
                DATABASE_NAME,
            ).addMigrations(MIGRATION_2_3).build()
    }
}
