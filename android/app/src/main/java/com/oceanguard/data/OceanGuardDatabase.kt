package com.oceanguard.ai.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.oceanguard.ai.data.collection.Achievement
import com.oceanguard.ai.data.collection.AchievementDao
import com.oceanguard.ai.data.collection.MarineDexDao
import com.oceanguard.ai.data.collection.MarineDexEntry
import com.oceanguard.ai.data.converters.DateConverter
import com.oceanguard.ai.data.converters.DebrisListConverter
import com.oceanguard.ai.data.converters.ImageQualityConverter
import com.oceanguard.ai.data.converters.LocationConverter

/**
 * Room database for OceanGuard AI.
 *
 * This is the single source of truth for all locally persisted detection data.
 * It holds one entity ([DetectionSession]) and exposes one DAO
 * ([DetectionSessionDao]).
 *
 * ## Singleton pattern
 *
 * [getInstance] uses double-checked locking with [@Volatile] to guarantee that
 * only one database instance is ever created per process, even under concurrent
 * coroutine access. Always obtain the instance through [getInstance] rather
 * than calling [Room.databaseBuilder] directly.
 *
 * ## TypeConverters
 *
 * All converters are registered at the database level (not just at the entity
 * level) so Room makes them available to every DAO query in this database,
 * including aggregate expressions that reference converted columns.
 *
 * Converters registered:
 *  - [DebrisListConverter]   – List<Debris>  <-> JSON String
 *  - [LocationConverter]     – Location?     <-> JSON String?
 *  - [DateConverter]         – Date?         <-> Long?  (epoch ms)
 *  - [ImageQualityConverter] – ImageQuality  <-> String (enum name)
 *
 * ## Schema migrations
 *
 * version = 1 is the initial release. Future schema changes must supply a
 * [androidx.room.migration.Migration] and increment the version number. Never
 * use [RoomDatabase.Builder.fallbackToDestructiveMigration] in production
 * builds — field observations and session history are irreplaceable user data.
 */
@Database(
    entities = [
        DetectionSession::class,
        GeneratedReport::class,
        MarineDexEntry::class,
        Achievement::class,
    ],
    version = 4,
    exportSchema = false
)
@TypeConverters(
    DebrisListConverter::class,
    LocationConverter::class,
    DateConverter::class,
    ImageQualityConverter::class
)
abstract class OceanGuardDatabase : RoomDatabase() {

    /**
     * Expose the DAO for [DetectionSession] CRUD and reactive queries.
     */
    abstract fun detectionSessionDao(): DetectionSessionDao
    abstract fun generatedReportDao(): GeneratedReportDao
    abstract fun marineDexDao(): MarineDexDao
    abstract fun achievementDao(): AchievementDao

    // -----------------------------------------------------------------------
    // Singleton
    // -----------------------------------------------------------------------

    companion object {

        /**
         * The filename used for the SQLite database file on-device.
         *
         * Changing this constant on an existing installation would orphan the
         * previous file, so treat it as immutable once shipped.
         */
        private const val DATABASE_NAME = "oceanguard.db"

        /**
         * The single shared instance. Marked [@Volatile] so writes from one
         * thread are immediately visible to all other threads, preventing a
         * race that would create two separate database connections.
         */
        @Volatile
        private var instance: OceanGuardDatabase? = null

        /**
         * Return the existing instance, or create it if this is the first call.
         *
         * The outer null-check avoids acquiring the synchronisation lock on
         * every call once the instance has been initialised (fast path). The
         * inner synchronized block with a second null-check guards against two
         * threads both seeing `null` simultaneously and each trying to create
         * the database (double-checked locking pattern).
         *
         * @param context Use the application context to avoid leaking Activity
         *                or Service references into a long-lived object.
         */
        fun getInstance(context: Context): OceanGuardDatabase {
            return instance ?: synchronized(this) {
                instance ?: buildDatabase(context.applicationContext).also {
                    instance = it
                }
            }
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS generated_reports (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        text TEXT NOT NULL,
                        language TEXT NOT NULL,
                        sessionCount INTEGER NOT NULL,
                        usedAi INTEGER NOT NULL,
                        timestamp INTEGER NOT NULL
                    )
                """.trimIndent())
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS marine_dex_entries (
                        debrisType TEXT NOT NULL PRIMARY KEY,
                        firstSeenAt INTEGER NOT NULL,
                        firstSeenSessionId INTEGER NOT NULL,
                        timesDetected INTEGER NOT NULL DEFAULT 1,
                        lastSeenAt INTEGER NOT NULL,
                        isFavorite INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS achievements (
                        id TEXT NOT NULL PRIMARY KEY,
                        unlockedAt INTEGER NOT NULL DEFAULT 0,
                        progress INTEGER NOT NULL DEFAULT 0,
                        target INTEGER NOT NULL
                    )
                """.trimIndent())
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE generated_reports ADD COLUMN locationName TEXT")
                db.execSQL("ALTER TABLE generated_reports ADD COLUMN centroidLat REAL")
                db.execSQL("ALTER TABLE generated_reports ADD COLUMN centroidLon REAL")
                db.execSQL("ALTER TABLE generated_reports ADD COLUMN dateRangeStartMs INTEGER")
                db.execSQL("ALTER TABLE generated_reports ADD COLUMN dateRangeEndMs INTEGER")
            }
        }

        private fun buildDatabase(appContext: Context): OceanGuardDatabase {
            return Room.databaseBuilder(
                appContext,
                OceanGuardDatabase::class.java,
                DATABASE_NAME
            )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                // -----------------------------------------------------------------
                // WAL mode
                // -----------------------------------------------------------------
                // Write-Ahead Logging allows reads and writes to proceed
                // concurrently, which is important since detection sessions can
                // be written from a background coroutine while the UI reads from
                // the main thread via Flow.
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .build()
        }
    }
}
