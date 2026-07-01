package com.friday.node.data.local

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.friday.node.utils.CryptoUtils

class RoomDatabase private constructor(private val context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "friday_local.db"
        private const val DATABASE_VERSION = 1
        private const val TABLE_EVENTS = "events"

        private const val KEY_MESSAGE_ID = "message_id"
        private const val KEY_TYPE = "type"
        private const val KEY_PAYLOAD = "payload"
        private const val KEY_TIMESTAMP = "timestamp"

        @Volatile
        private var instance: RoomDatabase? = null

        fun getInstance(context: Context): RoomDatabase {
            return instance ?: synchronized(this) {
                instance ?: RoomDatabase(context.applicationContext).also { instance = it }
            }
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        val createTableQuery = """
            CREATE TABLE $TABLE_EVENTS (
                $KEY_MESSAGE_ID TEXT PRIMARY KEY,
                $KEY_TYPE TEXT,
                $KEY_PAYLOAD TEXT,
                $KEY_TIMESTAMP INTEGER
            )
        """.trimIndent()
        db.execSQL(createTableQuery)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_EVENTS")
        onCreate(db)
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
}
