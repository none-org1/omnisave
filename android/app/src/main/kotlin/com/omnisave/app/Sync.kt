package com.omnisave.app

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.database.*
import org.json.JSONObject
import java.util.Base64
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/* Optional cross-device sync — envelopes byte-for-byte compatible with extension/lib/sync.js.
   PBKDF2-HMAC-SHA256(syncCode, salt "omnisave-v1", 120_000) → AES-256/GCM.
   Saves are sealed client-side; the RTDB only ever holds ciphertext. Off unless configured. */
object Sync {
    private const val SALT = "omnisave-v1"
    private const val ITERS = 120000

    data class Cfg(val on: Boolean, val code: String, val configJson: String)

    fun cfg(ctx: Context): Cfg {
        val p = ctx.getSharedPreferences("omnisave", Context.MODE_PRIVATE)
        return Cfg(p.getBoolean("syncOn", false), p.getString("syncCode", "") ?: "", p.getString("fbConfig", "") ?: "")
    }

    fun persist(ctx: Context, c: Cfg) = ctx.getSharedPreferences("omnisave", Context.MODE_PRIVATE)
        .edit().putBoolean("syncOn", c.on).putString("syncCode", c.code).putString("fbConfig", c.configJson).apply()

    @Volatile private var key: SecretKeySpec? = null
    @Volatile private var root: DatabaseReference? = null
    private var listener: ChildEventListener? = null
    private val io = Executors.newSingleThreadExecutor()

    /** Boot Firebase from the pasted web-config JSON + derive the AES key. Same contract as extension `sync.js boot()`. */
    fun boot(ctx: Context, cfg: Cfg): Boolean {
        if (!cfg.on) return false
        return try {
            if (root == null) {
                val o = JSONObject(cfg.configJson)
                if (FirebaseApp.getApps(ctx).isEmpty()) {
                    FirebaseApp.initializeApp(ctx, FirebaseOptions.Builder()
                        .setApiKey(o.getString("apiKey"))
                        .setApplicationId(o.optString("appId", o.optString("mobilesdk_app_id", "omnisave")))
                        .setProjectId(o.optString("projectId", "omnisave"))
                        .setDatabaseUrl(o.getString("databaseURL"))
                        .build())
                }
                root = FirebaseDatabase.getInstance().getReference("omnisave/" + java.net.URLEncoder.encode(cfg.code, "UTF-8") + "/items")
            }
            if (key == null) {
                val f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                val k = f.generateSecret(PBEKeySpec(cfg.code.toCharArray(), SALT.toByteArray(), ITERS, 256)).encoded
                key = SecretKeySpec(k, "AES")
            }
            true
        } catch (e: Exception) { false }
    }

    /* ---------- seal / unseal ---------- */
    private fun iv() = ByteArray(12).also { java.security.SecureRandom().nextBytes(it) }

    @Synchronized private fun seal(json: String, updatedAt: Long): Map<String, Any> {
        val ivb = iv()
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, ivb))
        return mapOf("c" to b64(ivb), "d" to b64(c.doFinal(json.toByteArray())), "u" to updatedAt)
    }

    @Synchronized private fun unseal(env: Map<String, Any>): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, b64d(env["c"] as String)))
        return String(c.doFinal(b64d(env["d"] as String)))
    }

    private fun b64(b: ByteArray) = Base64.getEncoder().encodeToString(b)
    private fun b64d(s: String) = Base64.getDecoder().decode(s)

    /* ---------- push ---------- */
    fun pushSave(item: Item) {
        val r = root ?: return
        if (item.remote) return
        io.execute {
            try {
                val o = JSONObject()
                    .put("id", item.id).put("createdAt", item.createdAt).put("updatedAt", item.updatedAt)
                    .put("type", item.type).put("platform", item.platform)
                    .put("url", item.url).put("canonical", item.canonical).put("title", item.title)
                    .put("refText", item.refText).put("preview", item.preview ?: "")
                item.videoId?.let { o.put("video", JSONObject().put("id", it).put("t", item.videoT ?: 0).put("isShort", item.isShort)) }
                item.tags?.let { o.put("tags", JSONObject().put("0", it)) } // kept text-only; tags string
                item.audioPath?.let { p ->
                    val bytes = java.io.File(p).takeIf { it.exists() }?.readBytes()
                    if (bytes != null) o.put("audio", JSONObject().put("b64", b64(bytes)).put("mime", "audio/mp4"))
                }
                r.child(item.id).set(seal(o.toString(), item.updatedAt))
            } catch (e: Exception) { android.util.Log.w("omnisave-sync", "push failed", e) }
        }
    }

    fun pushRemove(id: String) {
        val r = root ?: return
        io.execute { try { r.child(id).setValue(mapOf("t" to "tombstone", "u" to System.currentTimeMillis())) } catch (_: Exception) {} }
    }

    /* ---------- pull + realtime listeners ---------- */
    fun start(ctx: Context, dao: ItemDao) {
        val r = root ?: return
        val prefs = ctx.getSharedPreferences("omnisave", Context.MODE_PRIVATE)
        var lastSync = prefs.getLong("omnisaveLastSync", 0)
        listener?.let { r.removeEventListener(it) }
        val l = object : ChildEventListener {
            override fun onChildAdded(s: DataSnapshot, prev: String?) = apply(s, lastSync)
            override fun onChildChanged(s: DataSnapshot, prev: String?) = apply(s, lastSync)
            override fun onChildRemoved(s: DataSnapshot) { applyTombstone(s.key ?: return) }
            override fun onChildMoved(s: DataSnapshot, prev: String?) {}
            override fun onCancelled(e: DatabaseError) {}
            private fun apply(s: DataSnapshot, seen: Long) {
                val env = s.value as? Map<*, *> ?: return
                if ((env["u"] as? Number)?.toLong()?.let { it <= seen } == true) return
                if (env["t"] == "tombstone") { applyTombstone(s.key ?: return); return }
                try {
                    val o = JSONObject(unseal(env as Map<String, Any>))
                    val item = Item(
                        id = o.getString("id"), createdAt = o.optLong("createdAt"), updatedAt = o.optLong("updatedAt"),
                        type = o.optString("type", "website"), platform = o.optString("platform", "web"),
                        url = o.optString("url"), canonical = o.optString("url"), title = o.optString("title"),
                        refText = o.optString("refText"), preview = o.optString("preview", "").ifBlank { null },
                        tags = o.optString("tags", "").ifBlank { null },
                        remote = true
                    )
                    // audio memo → files dir (never raw PCM; ≤30s enforced at record time)
                    o.optJSONObject("audio")?.let { a ->
                        val f = java.io.File(ctx.filesDir, "memo-" + item.id + ".m4a")
                        java.io.File(ctx.filesDir).mkdirs(); f.writeBytes(b64d(a.getString("b64")))
                    }
                    io.execute { try { dao.upsert(item) } catch (_: Exception) {} }
                } catch (e: Exception) { android.util.Log.w("omnisave-sync", "apply failed", e) }
            }
            private fun applyTombstone(id: String) = io.execute { try { dao.remove(id) } catch (_: Exception) {} }
        }
        listener = l
        r.addChildEventListener(l)
        prefs.edit().putLong("omnisaveLastSync", System.currentTimeMillis()).apply()
    }

    fun stop(ctx: Context) {
        listener?.let { root?.removeEventListener(it) }
        listener = null
        ctx.getSharedPreferences("omnisave", Context.MODE_PRIVATE).edit().putLong("omnisaveLastSync", System.currentTimeMillis()).apply()
    }
}
