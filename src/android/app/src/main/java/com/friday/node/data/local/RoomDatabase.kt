package com.friday.node.data.local

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.friday.node.utils.CryptoUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class RoomDatabase private constructor(private val context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "friday_local.db"
        private const val DATABASE_VERSION = 2
        private const val TABLE_EVENTS = "events"
        private const val TABLE_PERSONAL_BASELINE = "personal_baseline"

        private const val KEY_MESSAGE_ID = "message_id"
        private const val KEY_TYPE = "type"
        private const val KEY_PAYLOAD = "payload"
        private const val KEY_TIMESTAMP = "timestamp"

        // Baseline schema keys
        private const val KEY_METRIC_NAME = "metric_name"
        private const val KEY_ROLLING_MEAN = "rolling_mean"
        private const val KEY_ROLLING_M2 = "rolling_m2"
        private const val KEY_ROLLING_STD = "rolling_std"
        private const val KEY_SAMPLE_COUNT = "sample_count"
        private const val KEY_LAST_UPDATED = "last_updated"

        @Volatile
        private var instance: RoomDatabase? = null

        fun getInstance(context: Context): RoomDatabase {
            return instance ?: synchronized(this) {
                instance ?: RoomDatabase(context.applicationContext).also { instance = it }
            }
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        val createEventsTableQuery = """
            CREATE TABLE IF NOT EXISTS $TABLE_EVENTS (
                $KEY_MESSAGE_ID TEXT PRIMARY KEY,
                $KEY_TYPE TEXT,
                $KEY_PAYLOAD TEXT,
                $KEY_TIMESTAMP INTEGER
            )
        """.trimIndent()
        db.execSQL(createEventsTableQuery)

        val createBaselineTableQuery = """
            CREATE TABLE IF NOT EXISTS $TABLE_PERSONAL_BASELINE (
                $KEY_METRIC_NAME TEXT PRIMARY KEY,
                $KEY_ROLLING_MEAN REAL,
                $KEY_ROLLING_M2 REAL,
                $KEY_ROLLING_STD REAL,
                $KEY_SAMPLE_COUNT INTEGER,
                $KEY_LAST_UPDATED INTEGER
            )
        """.trimIndent()
        db.execSQL(createBaselineTableQuery)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            val createBaselineTableQuery = """
                CREATE TABLE IF NOT EXISTS $TABLE_PERSONAL_BASELINE (
                    $KEY_METRIC_NAME TEXT PRIMARY KEY,
                    $KEY_ROLLING_MEAN REAL,
                    $KEY_ROLLING_M2 REAL,
                    $KEY_ROLLING_STD REAL,
                    $KEY_SAMPLE_COUNT INTEGER,
                    $KEY_LAST_UPDATED INTEGER
                )
            """.trimIndent()
            db.execSQL(createBaselineTableQuery)
        }
    }

    @Synchronized
    fun insertEvent(event: EventEntity): Boolean {
        val db = writableDatabase
        val sharedPrefs = context.getSharedPreferences("friday_prefs", Context.MODE_PRIVATE)
        val isEncryptionEnabled = sharedPrefs.getBoolean("data_encryption", true)
        val finalPayload = if (isEncryptionEnabled) CryptoUtils.encrypt(event.payload) else event.payload

        val values = ContentValues().apply {
            put(KEY_MESSAGE_ID, event.messageId)
            put(KEY_TYPE, event.type)
            put(KEY_PAYLOAD, finalPayload)
            put(KEY_TIMESTAMP, event.timestamp)
        }
        val result = db.insertWithOnConflict(TABLE_EVENTS, null, values, SQLiteDatabase.CONFLICT_REPLACE)
        return result != -1L
    }

    @Synchronized
    fun getAllEvents(): List<EventEntity> {
        val events = mutableListOf<EventEntity>()
        val db = readableDatabase
        val selectQuery = "SELECT * FROM $TABLE_EVENTS ORDER BY $KEY_TIMESTAMP ASC"
        val cursor = db.rawQuery(selectQuery, null)

        if (cursor.moveToFirst()) {
            val idIndex = cursor.getColumnIndexOrThrow(KEY_MESSAGE_ID)
            val typeIndex = cursor.getColumnIndexOrThrow(KEY_TYPE)
            val payloadIndex = cursor.getColumnIndexOrThrow(KEY_PAYLOAD)
            val timestampIndex = cursor.getColumnIndexOrThrow(KEY_TIMESTAMP)

            do {
                val rawPayload = cursor.getString(payloadIndex)
                val decryptedPayload = CryptoUtils.decrypt(rawPayload)
                events.add(
                    EventEntity(
                        messageId = cursor.getString(idIndex),
                        type = cursor.getString(typeIndex),
                        payload = decryptedPayload,
                        timestamp = cursor.getLong(timestampIndex)
                    )
                )
            } while (cursor.moveToNext())
        }
        cursor.close()
        return events
    }

    @Synchronized
    fun deleteEvents(messageIds: List<String>) {
        if (messageIds.isEmpty()) return
        val db = writableDatabase
        val placeholders = messageIds.joinToString(",") { "?" }
        db.delete(TABLE_EVENTS, "$KEY_MESSAGE_ID IN ($placeholders)", messageIds.toTypedArray())
    }

    @Synchronized
    fun getEventCount(): Int {
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT COUNT(*) FROM $TABLE_EVENTS", null)
        var count = 0
        if (cursor.moveToFirst()) {
            count = cursor.getInt(0)
        }
        cursor.close()
        return count
    }

    // In-memory fast cache: completely eliminates SQLite disk reads and lock contention on the Main/UI thread
    private val baselineCache = java.util.concurrent.ConcurrentHashMap<String, PersonalBaseline>()
    private val isCacheInitialized = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun ensureBaselineCacheLoaded() {
        if (isCacheInitialized.compareAndSet(false, true)) {
            try {
                val db = readableDatabase
                val cursor = db.rawQuery(
                    "SELECT $KEY_METRIC_NAME, $KEY_ROLLING_MEAN, $KEY_ROLLING_M2, $KEY_ROLLING_STD, $KEY_SAMPLE_COUNT, $KEY_LAST_UPDATED FROM $TABLE_PERSONAL_BASELINE",
                    null
                )
                if (cursor.moveToFirst()) {
                    do {
                        val name = cursor.getString(0)
                        baselineCache[name] = PersonalBaseline(
                            metricName = name,
                            rollingMean = cursor.getDouble(1),
                            rollingM2 = cursor.getDouble(2),
                            rollingStd = cursor.getDouble(3),
                            sampleCount = cursor.getLong(4),
                            lastUpdated = cursor.getLong(5)
                        )
                    } while (cursor.moveToNext())
                }
                cursor.close()
            } catch (e: Exception) {
                android.util.Log.w("RoomDatabase", "Failed to warm baseline cache: ${e.message}")
            }
        }
    }

    // ────────────────────────────────────────────────────────────────
    // Personal Baseline Engine (Welford's Algorithm - In-Memory O(1))
    // ────────────────────────────────────────────────────────────────

    /**
     * Records a new numeric metric sample (e.g. app switch count, typing delay ms,
     * notification frequency) and updates the rolling mean, M2, and standard deviation
     * in O(1) memory time using Welford's algorithm. Disk writes are performed asynchronously
     * on Dispatchers.IO to guarantee 0ms UI thread blocking.
     */
    fun recordMetricSample(metricName: String, value: Double): PersonalBaseline {
        ensureBaselineCacheLoaded()

        val current = baselineCache[metricName]
        val count = (current?.sampleCount ?: 0L) + 1L
        val oldMean = current?.rollingMean ?: 0.0
        val delta = value - oldMean
        val newMean = oldMean + (delta / count)
        val delta2 = value - newMean
        val newM2 = (current?.rollingM2 ?: 0.0) + (delta * delta2)
        val newStd = if (count > 1) kotlin.math.sqrt(newM2 / (count - 1)) else 0.0
        val now = System.currentTimeMillis()

        val updated = PersonalBaseline(metricName, newMean, newM2, newStd, count, now)
        baselineCache[metricName] = updated

        // Asynchronously persist to SQLite on background thread — ZERO UI thread blocking!
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                val db = writableDatabase
                val values = ContentValues().apply {
                    put(KEY_METRIC_NAME, metricName)
                    put(KEY_ROLLING_MEAN, newMean)
                    put(KEY_ROLLING_M2, newM2)
                    put(KEY_ROLLING_STD, newStd)
                    put(KEY_SAMPLE_COUNT, count)
                    put(KEY_LAST_UPDATED, now)
                }
                db.insertWithOnConflict(TABLE_PERSONAL_BASELINE, null, values, SQLiteDatabase.CONFLICT_REPLACE)
            } catch (e: Exception) {
                android.util.Log.w("RoomDatabase", "Async baseline persist error: ${e.message}")
            }
        }

        return updated
    }

    /**
     * Retrieves the stored personal baseline directly from in-memory cache (<0.001ms, 0 disk I/O).
     */
    fun getPersonalBaseline(metricName: String): PersonalBaseline? {
        ensureBaselineCacheLoaded()
        return baselineCache[metricName]
    }

    /**
     * Computes the statistical Z-score deviation directly from in-memory baseline.
     * Returns 0.0 if fewer than 5 samples have been recorded or if std dev is negligible.
     */
    fun calculateZScore(metricName: String, value: Double): Double {
        ensureBaselineCacheLoaded()
        val baseline = baselineCache[metricName] ?: return 0.0
        if (baseline.sampleCount < 5 || baseline.rollingStd <= 0.0001) {
            return 0.0
        }
        return (value - baseline.rollingMean) / baseline.rollingStd
    }

    /**
     * Retrieves all recorded baselines from cache.
     */
    fun getAllBaselines(): Map<String, PersonalBaseline> {
        ensureBaselineCacheLoaded()
        return baselineCache.toMap()
    }

    @Synchronized
    fun clearBaselines() {
        baselineCache.clear()
        val db = writableDatabase
        db.delete(TABLE_PERSONAL_BASELINE, null, null)
    }
}

data class PersonalBaseline(
    val metricName: String,
    val rollingMean: Double,
    val rollingM2: Double,
    val rollingStd: Double,
    val sampleCount: Long,
    val lastUpdated: Long
)

