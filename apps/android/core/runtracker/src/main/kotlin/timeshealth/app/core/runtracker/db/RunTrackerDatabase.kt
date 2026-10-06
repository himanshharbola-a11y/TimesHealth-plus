package timeshealth.app.core.runtracker.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * The run store. Its schema JSON is exported to core/runtracker/schemas/ and
 * committed: runs already on a phone must survive an app update, so every
 * future change is a versioned, reviewed Migration (as the TS `migrate` was).
 *
 * There is deliberately no `fallbackToDestructiveMigration`: an unsynced run
 * is the user's data, and a missing migration must fail loudly in testing
 * rather than silently wipe runs on a phone.
 */
@Database(
    entities = [RunEntity::class, RunPointEntity::class],
    version = 1,
    exportSchema = true,
)
internal abstract class RunTrackerDatabase : RoomDatabase() {
    abstract fun runDao(): RunDao

    companion object {
        /** Not the RN app's `timeshealth.db`: that lives in files/SQLite/ and is left alone. */
        const val NAME = "runtracker.db"

        fun build(context: Context): RunTrackerDatabase =
            Room.databaseBuilder(context.applicationContext, RunTrackerDatabase::class.java, NAME)
                // WAL (Room's default): the service writes while the UI reads.
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .build()
    }
}
