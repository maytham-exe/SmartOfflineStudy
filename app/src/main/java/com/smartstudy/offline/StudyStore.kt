package com.smartstudy.offline

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

class StudyStore(context: Context) : SQLiteOpenHelper(context, "study.db", null, 2) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE chunks(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                document TEXT NOT NULL,
                page INTEGER,
                body TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE notes(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                body TEXT NOT NULL,
                kind TEXT NOT NULL,
                created INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_notes_kind ON notes(kind)")
        db.execSQL("CREATE INDEX idx_chunks_doc ON chunks(document)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_notes_kind ON notes(kind)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_chunks_doc ON chunks(document)")
        }
    }

    fun addChunks(chunks: List<SourceChunk>) {
        if (chunks.isEmpty()) return
        writableDatabase.beginTransaction()
        try {
            chunks.forEach { c ->
                writableDatabase.insert(
                    "chunks",
                    null,
                    ContentValues().apply {
                        put("document", c.document)
                        if (c.page == null) putNull("page") else put("page", c.page)
                        put("body", c.text)
                    }
                )
            }
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    fun chunks(): List<SourceChunk> {
        val out = mutableListOf<SourceChunk>()
        readableDatabase.rawQuery("SELECT document, page, body FROM chunks ORDER BY id ASC", null).use { c ->
            while (c.moveToNext()) {
                out += SourceChunk(
                    document = c.getString(0),
                    page = if (c.isNull(1)) null else c.getInt(1),
                    text = c.getString(2)
                )
            }
        }
        return out
    }

    fun saveNote(body: String, kind: String): Long {
        return writableDatabase.insert(
            "notes",
            null,
            ContentValues().apply {
                put("body", body)
                put("kind", kind)
                put("created", System.currentTimeMillis())
            }
        )
    }

    fun notes(kindFilter: String? = null): List<SavedNote> {
        val out = mutableListOf<SavedNote>()
        val sql = if (kindFilter.isNullOrBlank()) {
            "SELECT id, body, kind, created FROM notes ORDER BY created DESC"
        } else {
            "SELECT id, body, kind, created FROM notes WHERE kind = ? ORDER BY created DESC"
        }
        val args = if (kindFilter.isNullOrBlank()) null else arrayOf(kindFilter)
        readableDatabase.rawQuery(sql, args).use { c ->
            while (c.moveToNext()) {
                out += SavedNote(
                    id = c.getLong(0),
                    body = c.getString(1),
                    kind = c.getString(2),
                    created = c.getLong(3)
                )
            }
        }
        return out
    }

    fun deleteNote(id: Long): Int {
        return writableDatabase.delete("notes", "id = ?", arrayOf(id.toString()))
    }

    fun stats(): Map<String, Int> {
        val map = mutableMapOf<String, Int>(
            "total_notes" to 0,
            "total_chunks" to 0,
            "تعريف" to 0,
            "تعليل" to 0,
            "مسألة" to 0,
            "تاريخ" to 0
        )
        readableDatabase.rawQuery("SELECT COUNT(*) FROM notes", null).use { c ->
            if (c.moveToFirst()) map["total_notes"] = c.getInt(0)
        }
        readableDatabase.rawQuery("SELECT COUNT(*) FROM chunks", null).use { c ->
            if (c.moveToFirst()) map["total_chunks"] = c.getInt(0)
        }
        readableDatabase.rawQuery("SELECT kind, COUNT(*) FROM notes GROUP BY kind", null).use { c ->
            while (c.moveToNext()) {
                map[c.getString(0)] = c.getInt(1)
            }
        }
        return map
    }

    fun exportJson(kindFilter: String? = null): String {
        val arr = JSONArray()
        for (n in notes(kindFilter)) {
            arr.put(
                JSONObject().apply {
                    put("id", n.id)
                    put("kind", n.kind)
                    put("body", n.body)
                    put("created", n.created)
                }
            )
        }
        return JSONObject().apply {
            put("app", "Smart Offline Study")
            put("filter", kindFilter ?: "ALL")
            put("count", arr.length())
            put("items", arr)
        }.toString(2)
    }

    fun exportPlainText(kindFilter: String? = null): String {
        val list = notes(kindFilter)
        if (list.isEmpty()) return "لا توجد عناصر محفوظة للتصدير."
        return list.joinToString("
------------------------------
") { item ->
            "[${item.kind}] ${item.body}"
        }
    }
}
