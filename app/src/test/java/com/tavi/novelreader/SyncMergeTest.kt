package com.tavi.novelreader

import com.tavi.novelreader.data.SyncEngine
import com.tavi.novelreader.data.SyncMerge
import com.tavi.novelreader.data.SyncRecord
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SyncMergeTest {
    private fun r(id: String, at: Long, del: Boolean = false, v: Int = 0) = SyncRecord(id, at, del, if (del) null else JSONObject().put("v", v))

    @Test fun lastWriterWinsPerRecord() {
        val local = listOf(r("p:a", 100, v = 1), r("p:b", 300, v = 1), r("h:x", 50, v = 1))
        val remote = listOf(r("p:a", 200, v = 2), r("p:b", 250, v = 2), r("h:y", 70, v = 2))
        val m = SyncMerge.merge(local, remote)
        assertEquals(4, m.size)
        assertEquals(2, m["p:a"]!!.data!!.getInt("v"))   // 远端更新
        assertEquals(1, m["p:b"]!!.data!!.getInt("v"))   // 本地更新
        assertTrue("h:x" in m && "h:y" in m)              // 两边独有的都保留
        val apply = SyncMerge.toApply(local, m).map { it.id }.toSet()
        assertEquals(setOf("p:a", "h:y"), apply)
    }

    @Test fun deletionsPropagateAndDoNotResurrect() {
        val local = listOf(r("h:x", 100, del = true))
        val remote = listOf(r("h:x", 90, v = 1))
        assertTrue(SyncMerge.merge(local, remote)["h:x"]!!.deleted)
        // 删除后在另一台设备上又编辑（更新）：编辑胜出
        assertFalse(SyncMerge.merge(local, listOf(r("h:x", 150, v = 3)))["h:x"]!!.deleted)
        // 同一时间：删除优先
        assertTrue(SyncMerge.merge(listOf(r("h:x", 100, v = 1)), listOf(r("h:x", 100, del = true)))["h:x"]!!.deleted)
    }

    @Test fun mergeIsIdempotentAndRoundTrips() {
        val a = listOf(r("p:a", 1, v = 1), r("b:k#1#2", 5, v = 1), r("d:2026-10-11", 600, v = 600))
        val b = listOf(r("p:a", 2, v = 2), r("s", 9, v = 9))
        val m1 = SyncMerge.merge(a, b)
        val m2 = SyncMerge.merge(m1.values, b)
        assertEquals(m1.keys, m2.keys)
        assertTrue(SyncMerge.toApply(m1.values, m2).isEmpty())
        val decoded = SyncMerge.decode(SyncMerge.encode(m1.values))
        assertEquals(m1.values.map { it.id to it.at }, decoded.map { it.id to it.at })
        assertEquals(Triple("三体|刘慈欣|epub", 3, 120), SyncEngine.parseBookmarkId(SyncEngine.bookmarkId("三体|刘慈欣|epub", 3, 120)))
    }
}
