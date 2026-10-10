package com.tavi.novelreader

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tavi.novelreader.data.AppDb
import com.tavi.novelreader.data.Book
import com.tavi.novelreader.data.SettingsRepo
import com.tavi.novelreader.data.Bookmark
import com.tavi.novelreader.parser.BookSource
import com.tavi.novelreader.parser.EpubBook
import com.tavi.novelreader.parser.TxtParser
import com.tavi.novelreader.parser.BookCache
import com.tavi.novelreader.parser.BookStore
import com.tavi.novelreader.parser.CountingInputStream
import com.tavi.novelreader.data.Highlight
import com.tavi.novelreader.parser.plainText
import com.tavi.novelreader.parser.blockStarts
import com.tavi.novelreader.parser.tableText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class AppViewModel(app: Application) : AndroidViewModel(app) {
    val dao = AppDb.get(app).books()
    val settings = SettingsRepo(app)
    val books = dao.observeAll().stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val message = MutableStateFlow<String?>(null)
    val importing = MutableStateFlow(false)

    /** 正在导入的任务（显示在书架顶部，带进度） */
    data class ImportTask(val id: Long, val name: String, val progress: Float)
    val imports = MutableStateFlow<List<ImportTask>>(emptyList())

    private fun setTask(t: ImportTask?, id: Long) {
        imports.value = imports.value.filter { it.id != id } + listOfNotNull(t)
    }

    fun import(uris: List<Uri>) = viewModelScope.launch {
        importing.value = true
        var ok = 0
        for (uri in uris) {
            val id = System.nanoTime()
            val r = runCatching { withContext(Dispatchers.IO) { importOne(uri, id) } }
            setTask(null, id)
            if (r.isSuccess) ok++ else message.value = "导入失败：${r.exceptionOrNull()?.message ?: "未知错误"}"
        }
        importing.value = false
        if (ok > 0) message.value = "已导入 $ok 本书"
    }

    private suspend fun importOne(uri: Uri, taskId: Long) {
        val ctx = getApplication<Application>()
        val cr = ctx.contentResolver
        var name = "未命名"
        var size = -1L
        cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) { c.getString(0)?.let { name = it }; if (!c.isNull(1)) size = c.getLong(1) }
        }
        val baseTitle = name.substringBeforeLast('.')
        setTask(ImportTask(taskId, baseTitle, 0f), taskId)
        val mime = cr.getType(uri) ?: ""
        val isEpub = name.endsWith(".epub", true) || mime == "application/epub+zip"
        val ext = if (isEpub) "epub" else "txt"
        val dir = File(ctx.filesDir, "books").apply { mkdirs() }
        val dest = File(dir, "${UUID.randomUUID()}.$ext")
        // 复制占 20% 进度，解析占 80%
        cr.openInputStream(uri)!!.use { input ->
            CountingInputStream(input) { c -> if (size > 0 && c % (1 shl 20) < 65536) setTask(ImportTask(taskId, baseTitle, 0.2f * c / size), taskId) }
                .use { counted -> dest.outputStream().use { counted.copyTo(it, 1 shl 16) } }
        }
        try {
            val lens = BookStore.build(ctx.filesDir, ext, dest) { p -> setTask(ImportTask(taskId, baseTitle, 0.2f + 0.8f * p), taskId) }
            if (isEpub) {
                val epub = EpubBook(dest)
                var cover: String? = null
                epub.coverBytes()?.let { bytes ->
                    val f = File(File(ctx.filesDir, "covers").apply { mkdirs() }, "${dest.nameWithoutExtension}.img")
                    f.writeBytes(bytes); cover = f.path
                }
                dao.insert(Book(title = epub.title ?: baseTitle, author = epub.author ?: "佚名", format = "epub",
                    filePath = dest.path, coverPath = cover, chapterCount = lens.size,
                    chapterLens = lens.joinToString(","), cacheVersion = BookCache.VERSION))
            } else {
                dao.insert(Book(title = baseTitle, author = "本地文本", format = "txt",
                    filePath = dest.path, coverPath = null, chapterCount = lens.size,
                    chapterLens = lens.joinToString(","), cacheVersion = BookCache.VERSION))
            }
        } catch (e: Exception) {
            dest.delete(); BookCache.dirFor(ctx.filesDir, dest.path).deleteRecursively(); throw e
        }
    }

    /**
     * 打开书籍：优先使用解析缓存；旧版本导入的书在首次打开时重建缓存，
     * 并把阅读位置、书签、笔记按全书比例映射到新的章节结构。
     */
    suspend fun openBook(book: Book, onProgress: (Float) -> Unit): Pair<BookSource, Book> = withContext(Dispatchers.IO) {
        val root = getApplication<Application>().filesDir
        val file = File(book.filePath)
        BookStore.open(root, book.format, file)?.takeIf { book.cacheVersion == BookCache.VERSION }?.let { return@withContext it to book }
        val oldLens = book.lens()
        val newLens = BookStore.build(root, book.format, file, onProgress)
        val (ch, off) = BookStore.remap(oldLens, newLens, book.chapterIndex, book.chapterOffset)
        dao.setCache(book.id, BookCache.VERSION, newLens.joinToString(","), newLens.size, ch, off)
        dao.bookmarksOf(book.id).forEach { m ->
            val (c2, o2) = BookStore.remap(oldLens, newLens, m.chapterIndex, m.offset); dao.moveBookmark(m.id, c2, o2)
        }
        dao.highlightsOf(book.id).forEach { h ->
            val (c2, s2) = BookStore.remap(oldLens, newLens, h.chapterIndex, h.start)
            dao.putHighlight(h.copy(chapterIndex = c2, start = s2, end = s2 + (h.end - h.start)))
        }
        val src = BookStore.open(root, book.format, file) ?: error("缓存创建失败")
        src to book.copy(chapterIndex = ch, chapterOffset = off, chapterLens = newLens.joinToString(","), chapterCount = newLens.size, cacheVersion = BookCache.VERSION)
    }

    fun delete(book: Book) = viewModelScope.launch(Dispatchers.IO) {
        dao.deleteBookmarksOf(book.id)
        dao.deleteHighlightsOf(book.id)
        File(book.filePath).delete(); book.coverPath?.let { File(it).delete() }
        BookCache.dirFor(getApplication<Application>().filesDir, book.filePath).deleteRecursively()
        dao.delete(book)
    }

    // ---------- 划线与笔记 ----------
    fun highlights(bookId: Long) = dao.highlights(bookId)
    fun putHighlight(h: Highlight) = viewModelScope.launch(Dispatchers.IO) { dao.putHighlight(h) }
    fun deleteHighlight(id: Long) = viewModelScope.launch(Dispatchers.IO) { dao.deleteHighlight(id) }

    /** 导出 Markdown 到用户选择的文件 */
    fun exportNotes(uri: Uri, markdown: String) = viewModelScope.launch {
        val r = runCatching {
            withContext(Dispatchers.IO) {
                getApplication<Application>().contentResolver.openOutputStream(uri, "wt")!!.use { it.write(markdown.toByteArray()) }
            }
        }
        message.value = if (r.isSuccess) "笔记已导出" else "导出失败：${r.exceptionOrNull()?.message}"
    }

    fun saveProgress(id: Long, ch: Int, off: Int, p: Float) = viewModelScope.launch(Dispatchers.IO) {
        dao.updateProgress(id, ch, off, p)
    }

    fun computeLens(src: BookSource): String =
        src.chapterTitles.indices.joinToString(",") { i -> runCatching { src.loadChapter(i).plainText().length }.getOrDefault(0).toString() }

    /** 旧版本导入的书籍没有章节字数，打开时补算 */
    suspend fun ensureLens(book: Book, src: BookSource): List<Int> = withContext(Dispatchers.IO) {
        book.lens().takeIf { it.size == src.chapterTitles.size } ?: computeLens(src).also { dao.setLens(book.id, it) }
            .split(',').map { it.toInt() }
    }

    fun addReadingTime(bookId: Long, ms: Long) = viewModelScope.launch(Dispatchers.IO) {
        if (ms <= 0) return@launch
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date())
        dao.addTime(bookId, ms); dao.ensureDay(day); dao.addDay(day, ms)
    }

    fun bookmarks(bookId: Long) = dao.bookmarks(bookId)
    fun addBookmark(b: Bookmark) = viewModelScope.launch(Dispatchers.IO) { dao.addBookmark(b) }
    fun deleteBookmark(id: Long) = viewModelScope.launch(Dispatchers.IO) { dao.deleteBookmark(id) }
    val recentDays = dao.recentDays(7)

    fun backup(uri: Uri) = viewModelScope.launch {
        val r = runCatching {
            withContext(Dispatchers.IO) {
                val json = com.tavi.novelreader.data.Backup.export(dao, settings.state.value)
                getApplication<Application>().contentResolver.openOutputStream(uri, "wt")!!.use { it.write(json.toByteArray()) }
            }
        }
        message.value = if (r.isSuccess) "备份已保存" else "备份失败：${r.exceptionOrNull()?.message}"
    }

    fun restore(uri: Uri) = viewModelScope.launch {
        val r = runCatching {
            withContext(Dispatchers.IO) {
                val json = getApplication<Application>().contentResolver.openInputStream(uri)!!.use { String(it.readBytes()) }
                com.tavi.novelreader.data.Backup.import(dao, json)
            }
        }
        r.getOrNull()?.settings?.let { settings.replace(it) }
        message.value = r.fold(
            { "已恢复：匹配 ${it.matchedBooks}/${it.totalBooks} 本书，新增 ${it.bookmarks} 个书签、${it.highlights} 条笔记" },
            { "恢复失败：${it.message}" })
    }

    /** 清除缓存：系统缓存目录 + 已不在书架上的残留文件 */
    fun clearCache() = viewModelScope.launch {
        val freed = withContext(Dispatchers.IO) {
            val app = getApplication<Application>()
            var bytes = app.cacheDir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
            app.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
            val used = dao.allBooks().flatMap { listOfNotNull(it.filePath, it.coverPath) }.toSet()
            listOf("books", "covers").forEach { d ->
                File(app.filesDir, d).listFiles()?.filter { it.path !in used }?.forEach { bytes += it.length(); it.delete() }
            }
            val usedCache = dao.allBooks().map { BookCache.dirFor(app.filesDir, it.filePath).path }.toSet()
            File(app.filesDir, "bookcache").listFiles()?.filter { it.path !in usedCache }?.forEach { d ->
                bytes += d.walkBottomUp().filter { it.isFile }.sumOf { it.length() }; d.deleteRecursively()
            }
            bytes
        }
        message.value = "已清除 ${"%.1f".format(freed / 1024.0 / 1024.0)} MB 缓存"
    }

    /** 导入自定义字体（TTF/OTF） */
    fun importFont(uri: Uri) = viewModelScope.launch {
        val r = runCatching {
            withContext(Dispatchers.IO) {
                val app = getApplication<Application>()
                var name = "自定义字体"
                app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0)?.let { name = it }
                }
                val dir = File(app.filesDir, "fonts").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val f = File(dir, "custom_${System.currentTimeMillis()}.${name.substringAfterLast('.', "ttf")}")
                app.contentResolver.openInputStream(uri)!!.use { i -> f.outputStream().use { i.copyTo(it) } }
                runCatching { android.graphics.Typeface.createFromFile(f) }.getOrNull()
                    ?.takeIf { it != android.graphics.Typeface.DEFAULT } ?: run { f.delete(); error("无法识别该字体文件") }
                f.path to name.substringBeforeLast('.')
            }
        }
        r.onSuccess { (path, name) -> settings.update { it.copy(font = com.tavi.novelreader.data.ReaderFont.CUSTOM, customFontPath = path, customFontName = name) } }
        message.value = r.fold({ "已导入字体：${it.second}" }, { "导入字体失败：${it.message}" })
    }
}

data class SearchHit(val chapter: Int, val chapterTitle: String, val offset: Int, val snippet: String, val matchStart: Int, val matchLen: Int)

/** 全书全文搜索（忽略大小写），最多返回 limit 条 */
suspend fun searchBook(src: BookSource, q: String, limit: Int = 500, onProgress: (List<SearchHit>) -> Unit) = withContext(Dispatchers.IO) {
    val hits = mutableListOf<SearchHit>()
    for (i in src.chapterTitles.indices) {
        ensureActive()
        val ch = runCatching { src.loadChapter(i) }.getOrNull() ?: continue
        val text = ch.plainText()
        var from = 0
        while (hits.size < limit) {
            val k = text.indexOf(q, from, ignoreCase = true)
            if (k < 0) break
            val s = (k - 18).coerceAtLeast(0); val e = (k + q.length + 30).coerceAtMost(text.length)
            val pre = if (s > 0) "…" else ""
            val snip = pre + text.substring(s, e).replace('\n', ' ').replace("\u3000", "")
            val ms = pre.length + text.substring(s, k).replace("\u3000", "").length
            hits += SearchHit(i, src.chapterTitles[i], k, snip, ms, q.length)
            from = k + q.length
        }
        // 表格文字：命中时定位到表格所在位置
        val st = ch.blockStarts()
        ch.blocks.forEachIndexed { bi, b ->
            if (b.kind != com.tavi.novelreader.parser.BlockKind.TABLE || hits.size >= limit) return@forEachIndexed
            val tt = b.tableText()
            val k = tt.indexOf(q, ignoreCase = true)
            if (k >= 0) {
                val s0 = (k - 18).coerceAtLeast(0)
                val snip = "［表格］" + tt.substring(s0, (k + q.length + 30).coerceAtMost(tt.length))
                hits += SearchHit(i, src.chapterTitles[i], st[bi], snip, 4 + k - s0, q.length)
            }
        }
        hits.sortWith(compareBy({ it.chapter }, { it.offset }))
        withContext(Dispatchers.Main) { onProgress(hits.toList()) }
        if (hits.size >= limit) break
    }
}
