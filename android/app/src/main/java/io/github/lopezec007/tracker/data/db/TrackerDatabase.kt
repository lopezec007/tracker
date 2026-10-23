package io.github.lopezec007.tracker.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration

@Database(entities = [PendingFixEntity::class], version = 1, exportSchema = true)
abstract class TrackerDatabase : RoomDatabase() {
    abstract fun pendingFixes(): PendingFixDao

    companion object {
        val MIGRATIONS = arrayOf<Migration>()

        fun build(context: Context, name: String = "tracker.db"): TrackerDatabase =
            Room.databaseBuilder(context.applicationContext, TrackerDatabase::class.java, name)
                .addMigrations(*MIGRATIONS)
                // Every future schema change should get a migration above so
                // the queue survives an upgrade; the fallback is for a
                // downgrade or a version without one (the queue is then lost).
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
