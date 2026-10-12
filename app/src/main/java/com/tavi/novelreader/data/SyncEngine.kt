package com.tavi.novelreader.data

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** 本地数据 <-> 同步记录，并通过 WebDAV 与远端合并 */
class SyncEngine(private val ctx: Context, private val dao: BookDao, private val settings: SettingsRepo) {
    private val prefs = SyncPrefs(ctx)

    suspend fun snapshot(): List<SyncRecord> {
        val books = dao.allBooks()
        val key = books.associate { it.id to it.syncKey() }
        val out = mutableListOf<SyncRecord>()
        books.filter { it.lastReadAt > 0 }.forEach { b ->
            out += SyncRecord("p:${b.syncKey()}", b.lastReadAt, data = JSONObject()
                .put("ch", b.chapterIndex).put("off", b.chapterOffset).put("p", b.progress.toDouble()).put("ms", b.readingMillis))
        }
        dao.allBookmarks().forEach { m ->
            val k = key[m.bookId] ?: return@forEach
            out += SyncRecord(bookmarkId(k, m.chapterIndex, m.offset), m.createdAt, data = JSONObject()
                .put("book", k).put("ch", m.chapterIndex).put("off", m.offset).put("title", m.chapterTitle).put("snip", m.snippet))
        }
        dao.allHighlights().forEach { h ->
            val k = key[h.bookId] ?: return@forEach
            out += SyncRecord("h:${h.uid}", h.updatedAt, data = JSONObject()
                .put("book", k).put("ch", h.chapterIndex).put("s", h.start).put("e", h.end).put("color", h.color).put("style", h.style)
                .put("text", h.text).put("note", h.note ?: "").put("title", h.chapterTitle).put("created", h.createdAt))
        }
        // 分组 / 标签（v1.9 起）；旧版本会忽略这些记录但合并时原样保留
        books.filter { it.metaAt > 0 }.forEach { b ->
            out += SyncRecord("m:${b.syncKey()}", b.metaAt, data = JSONObject().put("g", b.groupName).put("t", b.tags))
        }
        dao.groupList().forEach { g -> out += SyncRecord("g:${g.name}", maxOf(g.at, 1L), data = JSONObject().put("pos", g.position)) }
        dao.allDays().forEach { d -> out += SyncRecord("d:${d.day}", d.millis, data = JSONObject().put("ms", d.millis)) }
        if (prefs.settingsAt > 0) out += SyncRecord("s", prefs.settingsAt, data = settings.state.value.toJson())
        dao.tombstones().forEach { t -> out += SyncRecord(t.uid, t.at, deleted = true) }
        // 本地同一 id 既有墓碑又有记录时，以较新的为准
        return SyncMerge.merge(out, emptyList()).values.toList()
    }

    suspend fun apply(recs: List<SyncRecord>): Int {
        val books = dao.allBooks().associateBy { it.syncKey() }
        var n = 0
        for (r in recs) {
            val d = r.data
            when {
                r.deleted -> {
                    dao.putTombstone(Tombstone(r.id, r.at))
                    when {
                        r.id.startsWith("h:") -> { dao.deleteHighlightUid(r.id.removePrefix("h:")); n++ }
                        r.id.startsWith("g:") -> {
                            val name = r.id.removePrefix("g:")
                            val g = dao.groupList().firstOrNull { it.name == name }
                            if (g != null && g.at <= r.at) { dao.deleteGroup(name); dao.moveGroup(name, ""); n++ }
                        }
                        r.id.startsWith("b:") -> {
                            val (k, ch, off) = parseBookmarkId(r.id) ?: continue
                            val b = books[k] ?: continue
                            dao.bookmarksOf(b.id).filter { it.chapterIndex == ch && it.offset == off && it.createdAt <= r.at }
                                .forEach { dao.deleteBookmark(it.id); n++ }
                        }
                    }
                }
                d == null -> {}
                r.id.startsWith("p:") -> {
                    val b = books[r.id.removePrefix("p:")] ?: continue
                    if (r.at > b.lastReadAt) { dao.syncProgress(b.id, d.getInt("ch"), d.getInt("off"), d.getDouble("p").toFloat(), r.at, d.optLong("ms")); n++ }
                }
                r.id.startsWith("b:") -> {
                    val b = books[d.getString("book")] ?: continue
                    if (dao.bookmarksOf(b.id).none { it.chapterIndex == d.getInt("ch") && it.offset == d.getInt("off") }) {
                        dao.addBookmark(Bookmark(bookId = b.id, chapterIndex = d.getInt("ch"), offset = d.getInt("off"),
                            chapterTitle = d.optString("title"), snippet = d.optString("snip"), createdAt = r.at)); n++
                    }
                }
                r.id.startsWith("h:") -> {
                    val b = books[d.getString("book")] ?: continue
                    val uid = r.id.removePrefix("h:")
                    val ex = dao.highlightByUid(uid)
                    if (ex == null || ex.updatedAt < r.at) {
                        dao.putHighlight(Highlight(id = ex?.id ?: 0, bookId = b.id, chapterIndex = d.getInt("ch"), start = d.getInt("s"), end = d.getInt("e"),
                            color = d.optInt("color"), style = d.optInt("style"), text = d.optString("text"), note = d.optString("note").ifEmpty { null },
                            chapterTitle = d.optString("title"), createdAt = d.optLong("created"), uid = uid, updatedAt = r.at)); n++
                    }
                }
                r.id.startsWith("m:") -> {
                    val b = books[r.id.removePrefix("m:")] ?: continue
                    if (r.at > b.metaAt) {
                        val g = d.optString("g")
                        if (g.isNotEmpty() && dao.groupList().none { it.name == g }) dao.addGroup(ShelfGroup(g, dao.groupList().size, r.at))
                        dao.syncMeta(b.id, g, d.optString("t"), r.at); n++
                    }
                }
                r.id.startsWith("g:") -> {
                    val name = r.id.removePrefix("g:")
                    val g = dao.groupList().firstOrNull { it.name == name }
                    if (g == null || g.at < r.at) { dao.putGroup(ShelfGroup(name, d.optInt("pos", g?.position ?: 0), r.at)); dao.deleteTombstone(r.id); n++ }
                }
                r.id.startsWith("d:") -> {
                    val day = r.id.removePrefix("d:")
                    val local = dao.allDays().firstOrNull { it.day == day }?.millis ?: 0
                    if (d.getLong("ms") > local) { dao.putDay(ReadingDay(day, d.getLong("ms"))); n++ }
                }
                r.id == "s" -> if (r.at > prefs.settingsAt) {
                    settings.replace(ReaderSettings.fromJson(d)); prefs.settingsAt = r.at; n++
                }
            }
        }
        return n
    }

    private fun dav(): WebDav {
        require(prefs.configured) { "尚未设置 WebDAV 服务器" }
        return WebDav(prefs.url, prefs.user, prefs.password)
    }

    fun test(): String { val d = dav(); d.ensureDir(); d.get("sync.json"); return "连接成功" }

    suspend fun sync(): String {
        val d = dav()
        d.ensureDir()
        val remote = d.get("sync.json")?.let { SyncMerge.decode(it) } ?: emptyList()
        val local = snapshot()
        val merged = SyncMerge.merge(local, remote)
        val changed = apply(SyncMerge.toApply(local, merged))
        d.put("sync.json", SyncMerge.encode(merged.values).toByteArray())
        var uploaded = 0
        if (prefs.uploadBooks) {
            d.ensureDir("books")
            for (b in dao.allBooks()) {
                val f = File(b.filePath); if (!f.exists()) continue
                val name = "books/" + sha1(b.syncKey()) + "." + f.extension
                if (!d.exists(name)) { d.putStream(name, f.length(), "application/octet-stream") { o -> f.inputStream().use { it.copyTo(o) } }; uploaded++ }
            }
        }
        val msg = "已同步 ${merged.size} 条记录，本地更新 $changed 条" + if (uploaded > 0) "，上传 $uploaded 本书" else ""
        prefs.lastSync = System.currentTimeMillis(); prefs.lastResult = msg
        return msg
    }

    companion object {
        fun bookmarkId(key: String, ch: Int, off: Int) = "b:$key#$ch#$off"
        fun parseBookmarkId(id: String): Triple<String, Int, Int>? {
            val p = id.removePrefix("b:").split('#')
            if (p.size < 3) return null
            return Triple(p.dropLast(2).joinToString("#"), p[p.size - 2].toIntOrNull() ?: return null, p.last().toIntOrNull() ?: return null)
        }
        private fun sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
    }
}
