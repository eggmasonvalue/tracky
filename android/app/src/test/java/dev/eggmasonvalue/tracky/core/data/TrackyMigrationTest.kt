package dev.eggmasonvalue.tracky.core.data

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Test

class TrackyMigrationTest {
    @Test
    fun `v2 migration preserves old IDs as JSON lists and clears the obsolete cursor`() {
        val oldRows = listOf("imported" to "hc-\"quoted\\id", "pending" to null)
        var row = -1
        val statements = mutableListOf<Pair<String, List<Any?>>>()
        val cursor = Proxy.newProxyInstance(Cursor::class.java.classLoader, arrayOf(Cursor::class.java)) { _, method, args ->
            when (method.name) {
                "moveToNext" -> ++row < oldRows.size
                "isNull" -> oldRows[row].second == null
                "getString" -> if (args!![0] == 0) oldRows[row].first else oldRows[row].second
                "close" -> null
                else -> error("Unexpected cursor call: ${method.name}")
            }
        } as Cursor
        val database = Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader, arrayOf(SupportSQLiteDatabase::class.java),
        ) { _, method, args ->
            when (method.name) {
                "query" -> {
                    assertEquals("SELECT client_record_id, health_connect_record_id FROM import_ledger", args!![0])
                    cursor
                }
                "execSQL" -> {
                    val parameters = if (args!!.size == 2) (args[1] as Array<*>).toList() else emptyList()
                    statements.add(args[0] as String to parameters)
                    null
                }
                else -> error("Unexpected database call: ${method.name}")
            }
        } as SupportSQLiteDatabase

        TrackyDatabase.MIGRATION_2_3.migrate(database)

        assertEquals("ALTER TABLE import_ledger ADD COLUMN health_connect_record_ids TEXT NOT NULL DEFAULT '[]'", statements[0].first)
        val converter = TrackyTypeConverters()
        assertEquals(listOf(oldRows[0].second), converter.storageToRecordIds(statements[1].second[0] as String))
        assertEquals("imported", statements[1].second[1])
        assertEquals(emptyList<String>(), converter.storageToRecordIds(statements[2].second[0] as String))
        assertEquals("pending", statements[2].second[1])
        assertEquals(listOf(
            "ALTER TABLE import_ledger DROP COLUMN health_connect_record_id",
            "ALTER TABLE sync_state ADD COLUMN derivation_version INTEGER NOT NULL DEFAULT 0",
            "UPDATE sync_state SET cursor = NULL",
        ), statements.drop(3).map { it.first })
    }
}
