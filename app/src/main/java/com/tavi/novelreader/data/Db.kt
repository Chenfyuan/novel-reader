package com.tavi.novelreader.data

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "books")
data class Book(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val author: String,
    val format: String, // "epub" | "txt"
    val filePath: String,
    val coverPath: String?,
    val chapterCount: Int,
    val chapterIndex: Int = 0,
    val chapterOffset: Int = 0,
    val progress: Float = 0f,
    val addedAt: Long = System.currentTimeMillis(),
    val lastReadAt: Long = 0L,
    /** 每章字符数，逗号分隔；用于精确的全书进度 */
    @ColumnInfo(defaultValue = "") val chapterLens: String = "",
    @ColumnInfo(defaultValue = "0") val readingMillis: Long = 0L,
    /** 解析缓存版本，低于 BookCache.VERSION 时首次打开会重建缓存 */
    @ColumnInfo(defaultValue = "0") val cacheVersion: Int = 0,
    /** 所在分组（空 = 未分组） */
    @ColumnInfo(defaultValue = "") val groupName: String = "",
    /** 标签，逗号分隔 */
    @ColumnInfo(defaultValue = "") val tags: String = "",
) {
    fun tagList(): List<String> = tags.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    /** 跨设备识别同一本书 */
    fun syncKey(): String = "$title|$author|$format"
    fun lens(): List<Int> = if (chapterLens.isEmpty()) emptyList() else chapterLens.split(',').map { it.toIntOrNull() ?: 0 }
}

@Entity(tableName = "bookmarks", indices = [Index("bookId")])
data class Bookmark(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val chapterIndex: Int,
    val offset: Int,
    val chapterTitle: String,
    val snippet: String,
    val createdAt: Long = System.currentTimeMillis(),
)

/** 划线与笔记：以章节 + 字符区间定位 */
@Entity(tableName = "highlights", indices = [Index("bookId")])
data class Highlight(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val chapterIndex: Int,
    val start: Int,
    val end: Int,
    val color: Int = 0,
    /** 0 = 背景高亮，1 = 下划线 */
    val style: Int = 0,
    val text: String,
    val note: String? = null,
    val chapterTitle: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    /** 跨设备唯一 id 与最后修改时间（同步用） */
    @ColumnInfo(defaultValue = "") val uid: String = java.util.UUID.randomUUID().toString(),
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "shelf_groups")
data class ShelfGroup(@PrimaryKey val name: String, val position: Int = 0)

/** 删除记录（同步时传播删除） */
@Entity(tableName = "tombstones")
data class Tombstone(@PrimaryKey val uid: String, val at: Long)

@Entity(tableName = "reading_days")
data class ReadingDay(@PrimaryKey val day: String, val millis: Long)

data class Totals(val total: Long?, val books: Int)

@Dao
interface BookDao {
    @Query("SELECT * FROM books ORDER BY MAX(lastReadAt, addedAt) DESC")
    fun observeAll(): Flow<List<Book>>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun get(id: Long): Book?

    @Insert
    suspend fun insert(book: Book): Long

    @Query("UPDATE books SET chapterIndex = :ch, chapterOffset = :off, progress = :p, lastReadAt = :t WHERE id = :id")
    suspend fun updateProgress(id: Long, ch: Int, off: Int, p: Float, t: Long = System.currentTimeMillis())

    @Query("UPDATE books SET chapterLens = :lens WHERE id = :id")
    suspend fun setLens(id: Long, lens: String)

    @Query("UPDATE books SET readingMillis = readingMillis + :ms WHERE id = :id")
    suspend fun addTime(id: Long, ms: Long)

    @Delete
    suspend fun delete(book: Book)

    // 书签
    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId ORDER BY chapterIndex, offset")
    fun bookmarks(bookId: Long): Flow<List<Bookmark>>

    @Insert
    suspend fun addBookmark(b: Bookmark): Long

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun deleteBookmark(id: Long)

    // 划线与笔记
    @Query("SELECT * FROM highlights WHERE bookId = :bookId ORDER BY chapterIndex, start")
    fun highlights(bookId: Long): Flow<List<Highlight>>
    @Query("SELECT * FROM highlights WHERE bookId = :bookId ORDER BY chapterIndex, start")
    suspend fun highlightsOf(bookId: Long): List<Highlight>
    @Query("SELECT * FROM highlights") suspend fun allHighlights(): List<Highlight>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putHighlight(h: Highlight): Long
    @Query("DELETE FROM highlights WHERE id = :id") suspend fun deleteHighlight(id: Long)
    @Query("DELETE FROM highlights WHERE bookId = :bookId") suspend fun deleteHighlightsOf(bookId: Long)
    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId") suspend fun bookmarksOf(bookId: Long): List<Bookmark>
    @Query("UPDATE bookmarks SET chapterIndex = :ch, offset = :off WHERE id = :id") suspend fun moveBookmark(id: Long, ch: Int, off: Int)
    @Query("UPDATE books SET cacheVersion = :v, chapterLens = :lens, chapterCount = :count, chapterIndex = :ch, chapterOffset = :off WHERE id = :id")
    suspend fun setCache(id: Long, v: Int, lens: String, count: Int, ch: Int, off: Int)

    // 分组与标签
    @Query("SELECT * FROM shelf_groups ORDER BY position, name") fun groups(): Flow<List<ShelfGroup>>
    @Query("SELECT * FROM shelf_groups ORDER BY position, name") suspend fun groupList(): List<ShelfGroup>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun addGroup(g: ShelfGroup)
    @Query("DELETE FROM shelf_groups WHERE name = :name") suspend fun deleteGroup(name: String)
    @Query("UPDATE shelf_groups SET name = :to WHERE name = :from") suspend fun renameGroup(from: String, to: String)
    @Query("UPDATE books SET groupName = :to WHERE groupName = :from") suspend fun moveGroup(from: String, to: String)
    @Query("UPDATE books SET groupName = :group WHERE id IN (:ids)") suspend fun setGroup(ids: List<Long>, group: String)
    @Query("UPDATE books SET tags = :tags WHERE id = :id") suspend fun setTags(id: Long, tags: String)
    @Query("SELECT * FROM books WHERE id IN (:ids)") suspend fun booksByIds(ids: List<Long>): List<Book>

    // 同步
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putTombstone(t: Tombstone)
    @Query("SELECT * FROM tombstones") suspend fun tombstones(): List<Tombstone>
    @Query("SELECT * FROM highlights WHERE uid = :uid") suspend fun highlightByUid(uid: String): Highlight?
    @Query("DELETE FROM highlights WHERE uid = :uid") suspend fun deleteHighlightUid(uid: String)
    @Query("SELECT * FROM highlights WHERE id = :id") suspend fun highlight(id: Long): Highlight?
    @Query("SELECT * FROM bookmarks WHERE id = :id") suspend fun bookmark(id: Long): Bookmark?
    @Query("UPDATE books SET chapterIndex = :ch, chapterOffset = :off, progress = :p, lastReadAt = :at, readingMillis = MAX(readingMillis, :ms) WHERE id = :id")
    suspend fun syncProgress(id: Long, ch: Int, off: Int, p: Float, at: Long, ms: Long)

    @Query("DELETE FROM bookmarks WHERE bookId = :bookId")
    suspend fun deleteBookmarksOf(bookId: Long)

    // 统计
    @Query("INSERT OR IGNORE INTO reading_days(day, millis) VALUES(:day, 0)")
    suspend fun ensureDay(day: String)

    @Query("UPDATE reading_days SET millis = millis + :ms WHERE day = :day")
    suspend fun addDay(day: String, ms: Long)

    @Query("SELECT * FROM reading_days ORDER BY day DESC LIMIT :n")
    fun recentDays(n: Int): Flow<List<ReadingDay>>

    // 备份 / 恢复
    @Query("SELECT * FROM books") suspend fun allBooks(): List<Book>
    @Query("SELECT * FROM bookmarks") suspend fun allBookmarks(): List<Bookmark>
    @Query("SELECT * FROM reading_days") suspend fun allDays(): List<ReadingDay>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putDay(d: ReadingDay)
    @Query("UPDATE books SET chapterIndex = :ch, chapterOffset = :off, progress = :p, lastReadAt = :t, readingMillis = :ms WHERE id = :id")
    suspend fun restoreBook(id: Long, ch: Int, off: Int, p: Float, t: Long, ms: Long)
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE books ADD COLUMN chapterLens TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE books ADD COLUMN readingMillis INTEGER NOT NULL DEFAULT 0")
        db.execSQL("CREATE TABLE IF NOT EXISTS bookmarks (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, bookId INTEGER NOT NULL, chapterIndex INTEGER NOT NULL, offset INTEGER NOT NULL, chapterTitle TEXT NOT NULL, snippet TEXT NOT NULL, createdAt INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_bookmarks_bookId ON bookmarks(bookId)")
        db.execSQL("CREATE TABLE IF NOT EXISTS reading_days (day TEXT PRIMARY KEY NOT NULL, millis INTEGER NOT NULL)")
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE books ADD COLUMN cacheVersion INTEGER NOT NULL DEFAULT 0")
        db.execSQL("CREATE TABLE IF NOT EXISTS highlights (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, bookId INTEGER NOT NULL, chapterIndex INTEGER NOT NULL, start INTEGER NOT NULL, `end` INTEGER NOT NULL, color INTEGER NOT NULL, style INTEGER NOT NULL, text TEXT NOT NULL, note TEXT, chapterTitle TEXT NOT NULL, createdAt INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_highlights_bookId ON highlights(bookId)")
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE books ADD COLUMN groupName TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE books ADD COLUMN tags TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE highlights ADD COLUMN uid TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE highlights ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
        db.execSQL("UPDATE highlights SET uid = 'h' || id || '-' || createdAt, updatedAt = createdAt")
        db.execSQL("CREATE TABLE IF NOT EXISTS shelf_groups (name TEXT NOT NULL PRIMARY KEY, position INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS tombstones (uid TEXT NOT NULL PRIMARY KEY, at INTEGER NOT NULL)")
    }
}

@Database(entities = [Book::class, Bookmark::class, ReadingDay::class, Highlight::class, ShelfGroup::class, Tombstone::class], version = 4, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun books(): BookDao

    companion object {
        @Volatile private var inst: AppDb? = null
        fun get(ctx: Context): AppDb = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(ctx.applicationContext, AppDb::class.java, "books.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build().also { inst = it }
        }
    }
}
