package dev.eggmasonvalue.tracky.core.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [SessionEntity::class, RawFrameEntity::class],
    version = 1,
    exportSchema = true,
)
@TypeConverters(TrackyTypeConverters::class)
abstract class TrackyDatabase : RoomDatabase() {
    internal abstract fun trackyDao(): TrackyDao

    companion object {
        const val DATABASE_NAME: String = "tracky.db"

        fun build(context: Context): TrackyDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                TrackyDatabase::class.java,
                DATABASE_NAME,
            ).build()
    }
}
