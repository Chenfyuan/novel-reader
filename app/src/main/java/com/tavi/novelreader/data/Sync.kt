package com.tavi.novelreader.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** 一条同步记录：id 全局唯一，at 为最后修改时间，deleted 表示删除墓碑 */
data class SyncRecord(val id: String, val at: Long, val deleted: Boolean = false, val data: JSONObject? = null) {
    fun toJson() = JSONObject().put("id", id).put("at", at).apply { if (deleted) put("del", true); data?.let { put("d", it) } }
    companion object {
        fun fromJson(o: JSONObject) = SyncRecord(o.getString("id"), o.getLong("at"), o.optBoolean("del"), o.optJSONObject("d"))
    }
}

/** 逐条记录的「最后写入者胜出」合并 */
object SyncMerge {
    fun merge(local: Collection<SyncRecord>, remote: Collection<SyncRecord>): Map<String, SyncRecord> {
        val out = LinkedHashMap<String, SyncRecord>()
        for (r in remote) out[r.id] = r
        for (l in local) {
            val r = out[l.id]
            // 时间相同时删除优先，避免已删除的记录「复活」
            if (r == null || l.at > r.at || (l.at == r.at && l.deleted && !r.deleted)) out[l.id] = l
        }
        return out
    }

    /** 合并后需要写回本地的记录（远端更新的） */
    fun toApply(local: Collection<SyncRecord>, merged: Map<String, SyncRecord>): List<SyncRecord> {
        val byId = local.associateBy { it.id }
        return merged.values.filter { m -> val l = byId[m.id]; l == null || l.at != m.at || l.deleted != m.deleted }
    }

    fun encode(records: Collection<SyncRecord>): String =
        JSONObject().put("app", "moyue").put("version", 1).put("records", JSONArray().apply { records.forEach { put(it.toJson()) } }).toString()

    fun decode(text: String): List<SyncRecord> {
        val a = JSONObject(text).optJSONArray("records") ?: return emptyList()
        return (0 until a.length()).map { SyncRecord.fromJson(a.getJSONObject(it)) }
    }
}

/** 同步配置；密码用 Android Keystore 中的 AES-GCM 密钥加密后保存 */
class SyncPrefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("sync", Context.MODE_PRIVATE)
    var url: String get() = sp.getString("url", "") ?: ""; set(v) = sp.edit().putString("url", v.trim()).apply()
    var user: String get() = sp.getString("user", "") ?: ""; set(v) = sp.edit().putString("user", v.trim()).apply()
    var autoSync: Boolean get() = sp.getBoolean("auto", false); set(v) = sp.edit().putBoolean("auto", v).apply()
    var uploadBooks: Boolean get() = sp.getBoolean("books", false); set(v) = sp.edit().putBoolean("books", v).apply()
    var lastSync: Long get() = sp.getLong("last", 0); set(v) = sp.edit().putLong("last", v).apply()
    var lastResult: String get() = sp.getString("result", "") ?: ""; set(v) = sp.edit().putString("result", v).apply()
    var settingsAt: Long get() = sp.getLong("settingsAt", 0); set(v) = sp.edit().putLong("settingsAt", v).apply()

    var password: String
        get() = sp.getString("pw", null)?.let { runCatching { Secret.decrypt(it) }.getOrNull() } ?: ""
        set(v) { sp.edit().putString("pw", if (v.isEmpty()) null else Secret.encrypt(v)).apply() }

    val configured get() = url.isNotBlank()
}

private object Secret {
    private const val ALIAS = "moyue_webdav"
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        return g.generateKey()
    }
    fun encrypt(s: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key())
        return Base64.encodeToString(c.iv + c.doFinal(s.toByteArray()), Base64.NO_WRAP)
    }
    fun decrypt(s: String): String {
        val b = Base64.decode(s, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, b, 0, 12))
        return String(c.doFinal(b, 12, b.size - 12))
    }
}

/** 极简 WebDAV 客户端（GET / PUT / MKCOL / HEAD） */
class WebDav(base: String, private val user: String, private val pass: String) {
    private val root = base.trimEnd('/') + "/moyue"
    class HttpError(val code: Int, msg: String) : Exception(msg)

    private fun open(path: String, method: String): HttpURLConnection =
        (URL("$root/$path".trimEnd('/')).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15000; readTimeout = 30000
            if (user.isNotEmpty()) setRequestProperty("Authorization", "Basic " +
                Base64.encodeToString("$user:$pass".toByteArray(), Base64.NO_WRAP))
        }

    private fun check(c: HttpURLConnection, vararg ok: Int): Int {
        val code = c.responseCode
        if (code !in ok && code !in 200..299) {
            val msg = when (code) { 401 -> "用户名或密码错误"; 403 -> "没有权限"; 404 -> "地址不存在"; else -> "服务器返回 $code" }
            c.disconnect(); throw HttpError(code, msg)
        }
        return code
    }

    fun ensureDir(path: String = "") { val c = open(path, "MKCOL"); runCatching { check(c, 405, 301) }.getOrElse { if ((it as? HttpError)?.code != 409) throw it }; c.disconnect() }

    fun get(path: String): String? {
        val c = open(path, "GET")
        if (c.responseCode == 404) { c.disconnect(); return null }
        check(c)
        return c.inputStream.use { String(it.readBytes()) }.also { c.disconnect() }
    }

    fun exists(path: String): Boolean { val c = open(path, "HEAD"); val code = c.responseCode; c.disconnect(); return code in 200..299 }

    fun put(path: String, bytes: ByteArray, type: String = "application/json") = putStream(path, bytes.size.toLong(), type) { it.write(bytes) }

    fun putStream(path: String, length: Long, type: String, write: (java.io.OutputStream) -> Unit) {
        val c = open(path, "PUT")
        c.doOutput = true; c.setRequestProperty("Content-Type", type); c.setFixedLengthStreamingMode(length)
        c.outputStream.use(write)
        check(c); c.disconnect()
    }
}
