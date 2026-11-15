package org.shadowtrace

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Durable FIFO. Never silently discards unsent scans. */
class Outbox(context: Context) : SQLiteOpenHelper(context, "outbox.sqlite3", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE queue (id INTEGER PRIMARY KEY AUTOINCREMENT, payload TEXT NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    fun add(payload: String) {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM queue", null).use {
            it.moveToFirst()
            check(it.getInt(0) < 5000) { "Offline queue full. Reconnect before continuing." }
        }
        writableDatabase.insertOrThrow("queue", null, ContentValues().apply { put("payload", payload) })
    }
    fun first(): Pair<Long, String>? = readableDatabase.rawQuery(
        "SELECT id,payload FROM queue ORDER BY id LIMIT 1", null).use {
        if (it.moveToFirst()) Pair(it.getLong(0), it.getString(1)) else null
    }
    fun acknowledge(id: Long) { writableDatabase.delete("queue", "id=?", arrayOf(id.toString())) }
    fun count(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM queue", null).use {
        it.moveToFirst(); it.getInt(0)
    }
}
