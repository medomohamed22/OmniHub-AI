package com.aiway.nativeapp

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class ConversationInfo(val id: String, val title: String, val updatedAt: Long, val count: Int)

/**
 * Large local storage (SQLite). Stores conversations, messages, workspace files + edit history,
 * non-secret settings and an activity log. Secrets (tokens) stay in SecureStore (Android Keystore).
 */
class LocalDb(context: Context) : SQLiteOpenHelper(context.applicationContext, "aiway.db", null, 1) {

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE conversations(id TEXT PRIMARY KEY, title TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE messages(id INTEGER NOT NULL, conv_id TEXT NOT NULL, role TEXT NOT NULL, text TEXT NOT NULL, created_at INTEGER NOT NULL, PRIMARY KEY(conv_id, id))")
        db.execSQL("CREATE INDEX idx_msg_conv ON messages(conv_id, created_at)")
        db.execSQL("CREATE TABLE files(path TEXT PRIMARY KEY, content TEXT NOT NULL, updated_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE file_versions(id INTEGER PRIMARY KEY AUTOINCREMENT, path TEXT NOT NULL, content TEXT NOT NULL, created_at INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX idx_ver_path ON file_versions(path, created_at)")
        db.execSQL("CREATE TABLE kv(k TEXT PRIMARY KEY, v TEXT NOT NULL, updated_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE events(id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, type TEXT NOT NULL, detail TEXT NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    // ---------- settings ----------
    fun setKv(key: String, value: String) {
        val cv = ContentValues().apply { put("k", key); put("v", value); put("updated_at", System.currentTimeMillis()) }
        writableDatabase.insertWithOnConflict("kv", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getKv(key: String): String? =
        readableDatabase.rawQuery("SELECT v FROM kv WHERE k=?", arrayOf(key)).use { if (it.moveToFirst()) it.getString(0) else null }

    // ---------- events ----------
    fun log(type: String, detail: String = "") {
        val cv = ContentValues().apply { put("ts", System.currentTimeMillis()); put("type", type); put("detail", detail.take(500)) }
        writableDatabase.insert("events", null, cv)
        writableDatabase.execSQL("DELETE FROM events WHERE id NOT IN (SELECT id FROM events ORDER BY id DESC LIMIT 2000)")
    }

    // ---------- conversations ----------
    fun upsertConversation(id: String, title: String) {
        val now = System.currentTimeMillis()
        val db = writableDatabase
        val updated = ContentValues().apply { put("title", title); put("updated_at", now) }
        if (db.update("conversations", updated, "id=?", arrayOf(id)) == 0) {
            db.insert("conversations", null, ContentValues().apply { put("id", id); put("title", title); put("created_at", now); put("updated_at", now) })
        }
    }

    fun saveMessage(convId: String, m: ChatMessage) {
        val cv = ContentValues().apply {
            put("id", m.id); put("conv_id", convId); put("role", m.role); put("text", m.text); put("created_at", m.id)
        }
        writableDatabase.insertWithOnConflict("messages", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        writableDatabase.execSQL("UPDATE conversations SET updated_at=? WHERE id=?", arrayOf<Any>(System.currentTimeMillis(), convId))
    }

    fun loadMessages(convId: String): List<ChatMessage> =
        readableDatabase.rawQuery("SELECT id, role, text FROM messages WHERE conv_id=? ORDER BY created_at, id", arrayOf(convId)).use { c ->
            buildList { while (c.moveToNext()) add(ChatMessage(c.getLong(0), c.getString(1), c.getString(2))) }
        }

    fun listConversations(): List<ConversationInfo> =
        readableDatabase.rawQuery(
            "SELECT c.id, c.title, c.updated_at, (SELECT COUNT(*) FROM messages m WHERE m.conv_id=c.id) FROM conversations c ORDER BY c.updated_at DESC LIMIT 200", null
        ).use { c -> buildList { while (c.moveToNext()) add(ConversationInfo(c.getString(0), c.getString(1), c.getLong(2), c.getInt(3))) } }

    fun renameConversation(id: String, title: String) {
        writableDatabase.execSQL("UPDATE conversations SET title=? WHERE id=?", arrayOf<Any>(title, id))
    }

    fun deleteConversation(id: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("messages", "conv_id=?", arrayOf(id))
            db.delete("conversations", "id=?", arrayOf(id))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    // ---------- workspace ----------
    fun loadFiles(): LinkedHashMap<String, String> =
        readableDatabase.rawQuery("SELECT path, content FROM files ORDER BY path", null).use { c ->
            val out = linkedMapOf<String, String>()
            while (c.moveToNext()) out[c.getString(0)] = c.getString(1)
            out
        }

    fun hasFiles(): Boolean = readableDatabase.rawQuery("SELECT 1 FROM files LIMIT 1", null).use { it.moveToFirst() }

    /** Replace the whole workspace in one transaction and keep a version of every changed file. */
    fun saveFiles(files: Map<String, String>) {
        val db = writableDatabase
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            val existing = linkedMapOf<String, String>()
            db.rawQuery("SELECT path, content FROM files", null).use { c -> while (c.moveToNext()) existing[c.getString(0)] = c.getString(1) }
            existing.forEach { (p, old) ->
                val lastTs = db.rawQuery("SELECT MAX(created_at) FROM file_versions WHERE path=?", arrayOf(p)).use { if (it.moveToFirst()) it.getLong(0) else 0L }
                if (files[p] != old && now - lastTs > 60_000) {
                    db.insert("file_versions", null, ContentValues().apply { put("path", p); put("content", old); put("created_at", now) })
                }
            }
            db.delete("files", null, null)
            files.forEach { (p, c) ->
                db.insert("files", null, ContentValues().apply { put("path", p); put("content", c); put("updated_at", now) })
            }
            db.execSQL("DELETE FROM file_versions WHERE id NOT IN (SELECT id FROM file_versions ORDER BY id DESC LIMIT 500)")
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun versionCount(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM file_versions", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    fun recentEvents(limit: Int = 40): List<Triple<Long, String, String>> =
        readableDatabase.rawQuery("SELECT ts, type, detail FROM events ORDER BY id DESC LIMIT $limit", null).use { c ->
            buildList { while (c.moveToNext()) add(Triple(c.getLong(0), c.getString(1), c.getString(2))) }
        }

    fun messageCount(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM messages", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    fun sizeBytes(context: Context): Long = context.getDatabasePath("aiway.db").length()

    fun clearConversations() {
        val db = writableDatabase
        db.beginTransaction()
        try { db.delete("messages", null, null); db.delete("conversations", null, null); db.setTransactionSuccessful() } finally { db.endTransaction() }
    }
}
