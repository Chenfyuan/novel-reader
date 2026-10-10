package com.tavi.novelreader.data

import org.json.JSONArray
import org.json.JSONObject

/** 备份：书籍元数据与进度、书签、阅读统计、设置（不包含书籍文件本身） */
object Backup {
    suspend fun export(dao: BookDao, settings: ReaderSettings): String {
        val books = dao.allBooks()
        val root = JSONObject()
        root.put("app", "墨阅"); root.put("version", 1); root.put("exportedAt", System.currentTimeMillis())
        root.put("settings", settings.toJson())
        root.put("books", JSONArray().apply {
            books.forEach { b ->
                put(JSONObject().apply {
                    put("key", key(b)); put("title", b.title); put("author", b.author); put("format", b.format)
                    put("chapterIndex", b.chapterIndex); put("chapterOffset", b.chapterOffset); put("progress", b.progress.toDouble())
                    put("lastReadAt", b.lastReadAt); put("addedAt", b.addedAt); put("readingMillis", b.readingMillis)
                })
            }
        })
        val keyOf = books.associate { it.id to key(it) }
        root.put("bookmarks", JSONArray().apply {
            dao.allBookmarks().forEach { m ->
                put(JSONObject().apply {
                    put("book", keyOf[m.bookId] ?: ""); put("chapterIndex", m.chapterIndex); put("offset", m.offset)
                    put("chapterTitle", m.chapterTitle); put("snippet", m.snippet); put("createdAt", m.createdAt)
                })
            }
        })
        root.put("days", JSONArray().apply { dao.allDays().forEach { put(JSONObject().put("day", it.day).put("millis", it.millis)) } })
        return root.toString(2)
    }

    data class Result(val matchedBooks: Int, val totalBooks: Int, val bookmarks: Int, val settings: ReaderSettings?)

    /** 恢复：按「书名+作者+格式」匹配书架上已有的书 */
    suspend fun import(dao: BookDao, json: String): Result {
        val root = JSONObject(json)
        require(root.optString("app") == "墨阅") { "不是墨阅的备份文件" }
        val shelf = dao.allBooks().associateBy { key(it) }
        val books = root.optJSONArray("books") ?: JSONArray()
        var matched = 0
        for (i in 0 until books.length()) {
            val o = books.getJSONObject(i)
            val b = shelf[o.optString("key")] ?: continue
            matched++
            dao.restoreBook(b.id, o.optInt("chapterIndex"), o.optInt("chapterOffset"), o.optDouble("progress").toFloat(),
                o.optLong("lastReadAt"), maxOf(b.readingMillis, o.optLong("readingMillis")))
        }
        val existing = dao.allBookmarks().map { Triple(it.bookId, it.chapterIndex, it.offset) }.toSet()
        val marks = root.optJSONArray("bookmarks") ?: JSONArray()
        var added = 0
        for (i in 0 until marks.length()) {
            val o = marks.getJSONObject(i)
            val b = shelf[o.optString("book")] ?: continue
            if (Triple(b.id, o.optInt("chapterIndex"), o.optInt("offset")) in existing) continue
            dao.addBookmark(Bookmark(bookId = b.id, chapterIndex = o.optInt("chapterIndex"), offset = o.optInt("offset"),
                chapterTitle = o.optString("chapterTitle"), snippet = o.optString("snippet"), createdAt = o.optLong("createdAt")))
            added++
        }
        val days = root.optJSONArray("days") ?: JSONArray()
        val cur = dao.allDays().associate { it.day to it.millis }
        for (i in 0 until days.length()) {
            val o = days.getJSONObject(i)
            val d = o.optString("day"); val ms = o.optLong("millis")
            dao.putDay(ReadingDay(d, maxOf(ms, cur[d] ?: 0L)))
        }
        val settings = root.optJSONObject("settings")?.let { ReaderSettings.fromJson(it) }
        return Result(matched, books.length(), added, settings)
    }

    private fun key(b: Book) = "${b.title}\u0001${b.author}\u0001${b.format}"
}
