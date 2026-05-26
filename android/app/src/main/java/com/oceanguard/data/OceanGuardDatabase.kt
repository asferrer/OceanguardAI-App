package com.oceanguard.ai.data

import android.content.Context
import android.util.Log
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
import com.oceanguard.ai.data.contribution.ContributionQueueDao
import com.oceanguard.ai.data.contribution.ContributionQueueItem
import com.oceanguard.ai.data.converters.BoundingBoxConverter
import com.oceanguard.ai.data.converters.DateConverter
import com.oceanguard.ai.data.converters.DebrisListConverter
import com.oceanguard.ai.data.converters.ImageQualityConverter
import com.oceanguard.ai.data.converters.LocationConverter
import com.oceanguard.ai.data.species.SpeciesDexDao
import com.oceanguard.ai.data.species.SpeciesDexEntry
import com.oceanguard.ai.data.species.SpeciesObservation
import com.oceanguard.ai.data.species.SpeciesObservationDao

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
        VideoAnalysis::class,
        ContributionQueueItem::class,
        SpeciesObservation::class,
        SpeciesDexEntry::class,
    ],
    version = 12,
    exportSchema = false
)
@TypeConverters(
    DebrisListConverter::class,
    LocationConverter::class,
    DateConverter::class,
    ImageQualityConverter::class,
    BoundingBoxConverter::class,
)
abstract class OceanGuardDatabase : RoomDatabase() {

    /**
     * Expose the DAO for [DetectionSession] CRUD and reactive queries.
     */
    abstract fun detectionSessionDao(): DetectionSessionDao
    abstract fun generatedReportDao(): GeneratedReportDao
    abstract fun marineDexDao(): MarineDexDao
    abstract fun achievementDao(): AchievementDao
    abstract fun videoAnalysisDao(): VideoAnalysisDao
    abstract fun contributionQueueDao(): ContributionQueueDao
    abstract fun speciesObservationDao(): SpeciesObservationDao
    abstract fun speciesDexDao(): SpeciesDexDao

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

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS video_analyses (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        sourceVideoUri TEXT NOT NULL,
                        outputVideoUri TEXT,
                        thumbnailUri TEXT,
                        durationMs INTEGER NOT NULL,
                        totalFrameCount INTEGER NOT NULL,
                        processedFrameCount INTEGER NOT NULL,
                        uniqueDebrisCount INTEGER NOT NULL,
                        classCounts TEXT NOT NULL,
                        totalProcessingTimeMs INTEGER NOT NULL,
                        avgInferenceTimeMs REAL NOT NULL,
                        healthScore INTEGER NOT NULL,
                        location TEXT,
                        timestamp INTEGER NOT NULL,
                        status TEXT NOT NULL,
                        tags TEXT
                    )
                """.trimIndent())
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Remove duplicate sessions keeping only the oldest per imageUri
                db.execSQL("""
                    DELETE FROM detection_sessions WHERE id NOT IN (
                        SELECT MIN(id) FROM detection_sessions GROUP BY imageUri
                    )
                """.trimIndent())
                // Create unique index to prevent future duplicates
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_detection_sessions_imageUri ON detection_sessions (imageUri)"
                )
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `contribution_queue` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `sessionId` INTEGER NOT NULL,
                        `imageUri` TEXT NOT NULL,
                        `annotationsJson` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `retryCount` INTEGER NOT NULL DEFAULT 0,
                        `createdAt` INTEGER NOT NULL
                    )
                """.trimIndent())
                // Update all_achievements target: 21 original + 4 contribution - 1 (itself) = 24
                db.execSQL("UPDATE achievements SET target = 24 WHERE id = 'all_achievements'")
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE generated_reports ADD COLUMN audience TEXT")
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE generated_reports ADD COLUMN validationScore INTEGER")
                db.execSQL("ALTER TABLE generated_reports ADD COLUMN validationDetails TEXT")
            }
        }

        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE generated_reports ADD COLUMN sessionIds TEXT")
            }
        }

        /**
         * v10 -> v11: Collapse marine_dex_entries to the 11 canonical types.
         *
         * Pre-v11, the dex stored extended Gemma 4 types (PLASTIC_BAG, STYROFOAM, ...)
         * alongside the 11 canonical types, producing the inconsistent
         * "X / 11" counter and the achievement dex_11 reachable with extended types.
         *
         * This migration merges every non-canonical row into its canonical parent:
         *   firstSeenAt           = MIN across the merged group
         *   firstSeenSessionId    = the one paired with that MIN firstSeenAt
         *   timesDetected         = SUM
         *   lastSeenAt            = MAX
         *   isFavorite            = MAX (any favourite => merged stays favourite)
         *
         * The mapping mirrors [DebrisType.canonical] / `CANONICAL_PARENT` in
         * Models.kt; keep both in sync if the taxonomy changes.
         */
        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Temp table with the mapping. Canonical types map to themselves.
                db.execSQL("CREATE TEMPORARY TABLE _dex_canon_map (extended TEXT PRIMARY KEY, canonical TEXT NOT NULL)")
                val mappings = listOf(
                    // plastic family
                    "BOTTLE_CAP" to "PLASTIC_DEBRIS",
                    "PLASTIC_BAG" to "PLASTIC_DEBRIS",
                    "FOOD_WRAPPER" to "PLASTIC_DEBRIS",
                    "STYROFOAM" to "PLASTIC_DEBRIS",
                    "PLASTIC_CUP" to "PLASTIC_DEBRIS",
                    "STRAW" to "PLASTIC_DEBRIS",
                    "PLASTIC_UTENSIL" to "PLASTIC_DEBRIS",
                    "SIX_PACK_RING" to "PLASTIC_DEBRIS",
                    "PLASTIC_SHEETING" to "PLASTIC_DEBRIS",
                    "DIAPER" to "PLASTIC_DEBRIS",
                    // metal family
                    "AEROSOL_CAN" to "METAL_DEBRIS",
                    "METAL_DRUM" to "METAL_DEBRIS",
                    "WIRE_CABLE" to "METAL_DEBRIS",
                    "BATTERY" to "METAL_DEBRIS",
                    "ELECTRONICS" to "METAL_DEBRIS",
                    "PAINT_CAN" to "METAL_DEBRIS",
                    "OIL_CONTAINER" to "METAL_DEBRIS",
                    "CHEMICAL_DRUM" to "METAL_DEBRIS",
                    // glass family
                    "GLASS_BOTTLE" to "GLASS_DEBRIS",
                    "GLASS_JAR" to "GLASS_DEBRIS",
                    "GLASS_FRAGMENT" to "GLASS_DEBRIS",
                    "LIGHT_BULB" to "GLASS_DEBRIS",
                    // fishing family
                    "FISHING_LINE" to "FISHING_NET",
                    "ROPE" to "FISHING_NET",
                    "FISHING_BUOY" to "FISHING_NET",
                    "FISHING_TRAP" to "FISHING_NET",
                    // rubber family
                    "FLIP_FLOP" to "TIRE",
                    "RUBBER_HOSE" to "TIRE",
                    // fabric family
                    "CLOTHING" to "FABRIC_DEBRIS",
                    "SHOE" to "FABRIC_DEBRIS",
                    // hazardous & natural -> OTHER
                    "CIGARETTE_BUTT" to "OTHER",
                    "CIGARETTE_LIGHTER" to "OTHER",
                    "SYRINGE" to "OTHER",
                    "CARDBOARD" to "OTHER",
                    "PAPER" to "OTHER",
                    "WOOD_PALLET" to "OTHER",
                    "LUMBER" to "OTHER",
                    "CERAMIC_FRAGMENT" to "OTHER",
                    "BRICK" to "OTHER",
                )
                for ((ext, canon) in mappings) {
                    db.execSQL(
                        "INSERT INTO _dex_canon_map (extended, canonical) VALUES (?, ?)",
                        arrayOf<Any>(ext, canon),
                    )
                }

                // Snapshot of every dex row with its target canonical type.
                db.execSQL(
                    """
                    CREATE TEMPORARY TABLE _dex_new AS
                    SELECT
                        COALESCE(
                            (SELECT canonical FROM _dex_canon_map WHERE extended = e.debrisType),
                            e.debrisType
                        ) AS new_type,
                        e.firstSeenAt,
                        e.firstSeenSessionId,
                        e.timesDetected,
                        e.lastSeenAt,
                        e.isFavorite
                    FROM marine_dex_entries e
                    """.trimIndent()
                )

                // Replace the live table with the aggregated canonical rows.
                db.execSQL("DELETE FROM marine_dex_entries")
                db.execSQL(
                    """
                    INSERT INTO marine_dex_entries
                        (debrisType, firstSeenAt, firstSeenSessionId, timesDetected, lastSeenAt, isFavorite)
                    SELECT
                        new_type,
                        MIN(firstSeenAt),
                        (
                            SELECT firstSeenSessionId
                            FROM _dex_new b
                            WHERE b.new_type = a.new_type
                            ORDER BY firstSeenAt ASC
                            LIMIT 1
                        ),
                        SUM(timesDetected),
                        MAX(lastSeenAt),
                        MAX(isFavorite)
                    FROM _dex_new a
                    GROUP BY new_type
                    """.trimIndent()
                )

                db.execSQL("DROP TABLE _dex_new")
                db.execSQL("DROP TABLE _dex_canon_map")
            }
        }

        /**
         * v11 -> v12: BioDex species track (additive — no existing table is touched).
         *
         * Creates two new tables:
         *  - species_observations: one row per species identification event.
         *    Soft-linked to detection_sessions via sessionId (no FK constraint so
         *    the debris pipeline is never blocked by species data).
         *  - species_dex_entries: one row per distinct species discovered (dex).
         *
         * Both tables are created with IF NOT EXISTS so the migration is idempotent
         * (safe to re-run on a partially-migrated DB without data loss).
         */
        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS species_observations (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        imageUri TEXT NOT NULL,
                        thumbnailUri TEXT,
                        sessionId INTEGER,
                        aphiaId INTEGER,
                        scientificName TEXT NOT NULL,
                        commonNameKey TEXT,
                        bbox TEXT,
                        cosineScore REAL NOT NULL,
                        confidence REAL NOT NULL,
                        idSource TEXT NOT NULL,
                        vlmDescription TEXT,
                        uncatalogued INTEGER NOT NULL DEFAULT 0,
                        ecoregionId INTEGER,
                        geoMatchLevel TEXT,
                        outOfRange INTEGER NOT NULL DEFAULT 0,
                        timestamp INTEGER NOT NULL,
                        location TEXT
                    )
                """.trimIndent())
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_species_observations_sessionId ON species_observations (sessionId)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_species_observations_aphiaId ON species_observations (aphiaId)"
                )
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS species_dex_entries (
                        speciesKey TEXT NOT NULL PRIMARY KEY,
                        scientificName TEXT NOT NULL,
                        firstSeenAt INTEGER NOT NULL,
                        firstSeenObservationId INTEGER NOT NULL,
                        timesObserved INTEGER NOT NULL DEFAULT 1,
                        lastSeenAt INTEGER NOT NULL,
                        isFavorite INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
            }
        }

        /**
         * Defensive schema healer that runs on every database open.
         *
         * Why we need this: a subset of users (observed in production v0.2.x)
         * ended up with a corrupt schema where Room's [room_master_table]
         * identity_hash matched the v11 expected hash, yet the underlying
         * DDL was missing columns added in [MIGRATION_9_10] (notably
         * `generated_reports.sessionIds`). Symptom: every report INSERT silently
         * failed because the entity expected a column that did not exist, so
         * no report ever persisted past app close. Root cause is suspected to
         * be a previous partial migration that committed the hash row without
         * committing the ALTER TABLE — Room's migration framework then treats
         * the DB as already-on-target and skips re-running.
         *
         * The callback runs idempotent `ALTER TABLE` statements wrapped in
         * try/catch — adding a column that already exists raises a SQLite
         * error, which we swallow. This way every app open self-heals any
         * partial-migration corruption without bumping the schema version or
         * losing user data.
         */
        private val SCHEMA_HEALER = object : RoomDatabase.Callback() {
            override fun onOpen(db: SupportSQLiteDatabase) {
                super.onOpen(db)
                val expected = listOf(
                    "ALTER TABLE generated_reports ADD COLUMN sessionIds TEXT",
                )
                var added = 0
                for (sql in expected) {
                    try {
                        db.execSQL(sql)
                        added++
                        Log.i("OceanGuardDatabase", "Schema self-heal: applied `$sql`")
                    } catch (_: Throwable) {
                        // Column already exists or other benign error — ignore.
                    }
                }
                if (added > 0) {
                    Log.w("OceanGuardDatabase", "Schema self-heal: patched $added missing column(s) on open")
                }
            }
        }

        private fun buildDatabase(appContext: Context): OceanGuardDatabase {
            return Room.databaseBuilder(
                appContext,
                OceanGuardDatabase::class.java,
                DATABASE_NAME
            )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12)
                .addCallback(SCHEMA_HEALER)
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
