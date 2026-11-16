package com.mambadoc.app.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.content.ContentValues
import com.mambadoc.app.data.model.Chunk
import com.mambadoc.app.data.model.DocumentMeta
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DocDatabase private constructor(context: Context) :
    SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE documents (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                filename TEXT NOT NULL,
                file_type TEXT NOT NULL,
                page_count INTEGER DEFAULT 0,
                total_chars INTEGER DEFAULT 0,
                ingested_at INTEGER NOT NULL,
                summary_cache TEXT,
                is_scanned INTEGER DEFAULT 0
            )
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE chunks (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                doc_id INTEGER NOT NULL,
                chunk_index INTEGER NOT NULL,
                page_or_section TEXT,
                text TEXT NOT NULL,
                token_est INTEGER DEFAULT 0,
                embedding BLOB,
                FOREIGN KEY(doc_id) REFERENCES documents(id) ON DELETE CASCADE
            )
        """.trimIndent())

        // FTS5 for BM25 lexical search
        db.execSQL("""
            CREATE VIRTUAL TABLE fts_chunks USING fts5(
                text,
                content='chunks',
                content_rowid='id',
                tokenize='porter unicode61'
            )
        """.trimIndent())

        // Triggers to keep FTS in sync
        db.execSQL("""
            CREATE TRIGGER chunks_ai AFTER INSERT ON chunks BEGIN
                INSERT INTO fts_chunks(rowid, text) VALUES (new.id, new.text);
            END
        """.trimIndent())
        db.execSQL("""
            CREATE TRIGGER chunks_ad AFTER DELETE ON chunks BEGIN
                INSERT INTO fts_chunks(fts_chunks, rowid, text) VALUES('delete', old.id, old.text);
            END
        """.trimIndent())
        db.execSQL("""
            CREATE TRIGGER chunks_au AFTER UPDATE ON chunks BEGIN
                INSERT INTO fts_chunks(fts_chunks, rowid, text) VALUES('delete', old.id, old.text);
                INSERT INTO fts_chunks(rowid, text) VALUES (new.id, new.text);
            END
        """.trimIndent())

        db.execSQL("CREATE INDEX idx_chunks_doc ON chunks(doc_id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Simple wipe for v1
        db.execSQL("DROP TABLE IF EXISTS fts_chunks")
        db.execSQL("DROP TABLE IF EXISTS chunks")
        db.execSQL("DROP TABLE IF EXISTS documents")
        onCreate(db)
    }

    fun insertDocument(meta: DocumentMeta): Long {
        val cv = ContentValues().apply {
            put("filename", meta.filename)
            put("file_type", meta.fileType)
            put("page_count", meta.pageCount)
            put("total_chars", meta.totalChars)
            put("ingested_at", meta.ingestedAt)
            put("summary_cache", meta.summaryCache)
            put("is_scanned", if (meta.isScannedPdf) 1 else 0)
        }
        return writableDatabase.insert("documents", null, cv)
    }

    fun insertChunk(chunk: Chunk): Long {
        val cv = ContentValues().apply {
            put("doc_id", chunk.docId)
            put("chunk_index", chunk.chunkIndex)
            put("page_or_section", chunk.pageOrSection)
            put("text", chunk.text)
            put("token_est", chunk.tokenEst)
            chunk.embedding?.let { emb ->
                val buf = ByteBuffer.allocate(emb.size * 4).order(ByteOrder.LITTLE_ENDIAN)
                emb.forEach { buf.putFloat(it) }
                put("embedding", buf.array())
            }
        }
        return writableDatabase.insert("chunks", null, cv)
    }

    fun getAllDocuments(): List<DocumentMeta> {
        val list = mutableListOf<DocumentMeta>()
        readableDatabase.rawQuery(
            "SELECT d.*, (SELECT COUNT(*) FROM chunks c WHERE c.doc_id = d.id) AS chunk_count FROM documents d ORDER BY ingested_at DESC",
            null
        ).use { c ->
            while (c.moveToNext()) {
                list.add(
                    DocumentMeta(
                        id = c.getLong(c.getColumnIndexOrThrow("id")),
                        filename = c.getString(c.getColumnIndexOrThrow("filename")),
                        fileType = c.getString(c.getColumnIndexOrThrow("file_type")),
                        pageCount = c.getInt(c.getColumnIndexOrThrow("page_count")),
                        totalChars = c.getInt(c.getColumnIndexOrThrow("total_chars")),
                        ingestedAt = c.getLong(c.getColumnIndexOrThrow("ingested_at")),
                        summaryCache = c.getString(c.getColumnIndexOrThrow("summary_cache")),
                        isScannedPdf = c.getInt(c.getColumnIndexOrThrow("is_scanned")) == 1,
                        chunkCount = c.getInt(c.getColumnIndexOrThrow("chunk_count"))
                    )
                )
            }
        }
        return list
    }

    fun deleteDocument(id: Long) {
        writableDatabase.delete("documents", "id=?", arrayOf(id.toString()))
    }

    fun getChunksForDoc(docId: Long): List<Chunk> {
        val list = mutableListOf<Chunk>()
        readableDatabase.rawQuery(
            "SELECT * FROM chunks WHERE doc_id=? ORDER BY chunk_index",
            arrayOf(docId.toString())
        ).use { c ->
            while (c.moveToNext()) {
                list.add(cursorToChunk(c))
            }
        }
        return list
    }

    fun getAllChunksWithEmbeddings(docIds: List<Long>? = null): List<Chunk> {
        val list = mutableListOf<Chunk>()
        val sql = if (docIds.isNullOrEmpty()) {
            "SELECT * FROM chunks WHERE embedding IS NOT NULL"
        } else {
            val placeholders = docIds.joinToString(",") { "?" }
            "SELECT * FROM chunks WHERE embedding IS NOT NULL AND doc_id IN ($placeholders)"
        }
        val args = docIds?.map { it.toString() }?.toTypedArray()
        readableDatabase.rawQuery(sql, args).use { c ->
            while (c.moveToNext()) {
                list.add(cursorToChunk(c))
            }
        }
        return list
    }

    fun ftsSearch(query: String, limit: Int = 20, docIds: List<Long>? = null): List<Pair<Long, Float>> {
        // Returns (chunk_id, bm25_rank) – lower BM25 is better, we invert later
        // Sanitize: FTS5 MATCH rejects punctuation / reserved words in raw user questions.
        val ftsQuery = query
            .split(Regex("[^A-Za-z0-9_]+"))
            .map { it.trim() }
            .filter { it.length >= 2 }
            .distinct()
            .take(12)
            .joinToString(" OR ") { "\"$it\"" }
        if (ftsQuery.isBlank()) return emptyList()

        val results = mutableListOf<Pair<Long, Float>>()
        val sql = if (docIds.isNullOrEmpty()) {
            """
            SELECT rowid, bm25(fts_chunks) AS rank
            FROM fts_chunks
            WHERE fts_chunks MATCH ?
            ORDER BY rank
            LIMIT ?
            """.trimIndent()
        } else {
            val placeholders = docIds.joinToString(",") { "?" }
            """
            SELECT f.rowid, bm25(fts_chunks) AS rank
            FROM fts_chunks f
            JOIN chunks c ON c.id = f.rowid
            WHERE fts_chunks MATCH ? AND c.doc_id IN ($placeholders)
            ORDER BY rank
            LIMIT ?
            """.trimIndent()
        }
        val args = mutableListOf(ftsQuery)
        if (!docIds.isNullOrEmpty()) args.addAll(docIds.map { it.toString() })
        args.add(limit.toString())

        try {
            readableDatabase.rawQuery(sql, args.toTypedArray()).use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val rank = c.getFloat(1)
                    results.add(id to rank)
                }
            }
        } catch (e: Exception) {
            // FTS syntax errors on weird queries – return empty
        }
        return results
    }

    fun getChunkById(id: Long): Chunk? {
        readableDatabase.rawQuery("SELECT * FROM chunks WHERE id=?", arrayOf(id.toString())).use { c ->
            if (c.moveToFirst()) return cursorToChunk(c)
        }
        return null
    }

    fun updateSummary(docId: Long, summary: String) {
        val cv = ContentValues().apply { put("summary_cache", summary) }
        writableDatabase.update("documents", cv, "id=?", arrayOf(docId.toString()))
    }

    private fun cursorToChunk(c: android.database.Cursor): Chunk {
        val embBlob = c.getBlob(c.getColumnIndexOrThrow("embedding"))
        val emb = if (embBlob != null && embBlob.isNotEmpty()) {
            val buf = ByteBuffer.wrap(embBlob).order(ByteOrder.LITTLE_ENDIAN)
            FloatArray(embBlob.size / 4) { buf.float }
        } else null
        return Chunk(
            id = c.getLong(c.getColumnIndexOrThrow("id")),
            docId = c.getLong(c.getColumnIndexOrThrow("doc_id")),
            chunkIndex = c.getInt(c.getColumnIndexOrThrow("chunk_index")),
            pageOrSection = c.getString(c.getColumnIndexOrThrow("page_or_section")) ?: "",
            text = c.getString(c.getColumnIndexOrThrow("text")),
            tokenEst = c.getInt(c.getColumnIndexOrThrow("token_est")),
            embedding = emb
        )
    }

    companion object {
        private const val DB_NAME = "mambadoc.db"
        private const val DB_VERSION = 1

        @Volatile private var INSTANCE: DocDatabase? = null

        fun getInstance(context: Context): DocDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: DocDatabase(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
