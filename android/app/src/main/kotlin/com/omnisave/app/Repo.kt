package com.omnisave.app

import android.content.Context
import java.net.URLDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/* Shared save path for bubble + share-target: canonicalize, 15-min dedupe, write, enrich, push. */
object Repo {
    fun canonicalize(raw: String): String = try {
        val u = URL(raw) // simplest; Android URL lacks query param API → scratch parse
        val q = u.query?.split('&')?.filter {
            !it.startsWith("utm_", true) && !it.startsWith("mc_", true) &&
                !setOf("fbclid", "gclid", "si", "feature", "ref").contains(it.substringBefore('=').lowercase())
        }?.joinToString("&")
        val host = u.host.removePrefix("www.")
        "https://$host${u.path ?: ""}${q?.takeIf { it.isNotBlank() }?.let { "?$it" } ?: ""}"
    } catch (e: Exception) { raw }

    fun parse(url: String): Triple<String, String, Boolean?> {
        val host = hostOf(url); val path = try { URL(url).path ?: "" } catch (e: Exception) { "" }
        val (platform, type) = when {
            host.endsWith("youtube.com") || host == "youtu.be" -> {
                val id = Regex("[?&]v=([\\w-]{6,})").find(url)?.groupValues?.get(1)
                    ?: if (host == "youtu.be") path.removePrefix("/") else null
                val short = "/shorts/([\\w-]{6,})".toRegex().find(path)?.groupValues?.get(1)
                listOfNotNull("youtube", if (short != null) "short" else "video")
                    .map { it }.let { it[0] to it[1] }
            }
            host == "x.com" || host == "twitter.com" -> "x" to (if ("/(i/)?article".toRegex().containsMatchIn(path)) "article" else if ("/status/\\d+".toRegex().containsMatchIn(path)) "post" else "website")
            host.endsWith("linkedin.com") -> "linkedin" to (if ("/(posts|feed|update|pulse)/".toRegex().containsMatchIn(path)) "post" else "website")
            host.endsWith("discord.com") -> "discord" to "post"
            host.endsWith("reddit.com") -> "reddit" to (if ("/comments/".toRegex().containsMatchIn(path)) "post" else "website")
            host.endsWith("notion.so") || host.endsWith("notion.site") -> "notion" to "doc"
            host.endsWith("instagram.com") -> "instagram" to (if (path.contains("reel")) "short" else if (path.contains("/p/") || path.contains("/tv/")) "post" else "website")
            else -> "app" to "website"
        }
        return Triple(platform, type, if (platform == "youtube" && type == "short") true else if (platform == "youtube") false else null)
    }

    private fun hostOf(url: String) = try { URL(url).host.removePrefix("www.") } catch (e: Exception) { "" }

    suspend fun save(
        ctx: Context, dao: ItemDao,
        platform: String, type: String, url: String, title: String,
        refText: String, audioPath: String? = null, videoId: String? = null,
        isShort: Boolean? = null, videoT: Int? = null, preview: String? = null,
        source: String = "app"
    ): Item {
        val now = System.currentTimeMillis()
        val can = canonicalize(url)
        val base = Item(
            id = java.util.UUID.randomUUID().toString(), createdAt = now, updatedAt = now,
            type = type, platform = platform, url = url, canonical = can,
            title = title.take(300), refText = refText.take(500), preview = preview,
            videoId = videoId, videoT = videoT, isShort = isShort ?: false,
            audioPath = audioPath, source = source
        )
        val twin = withContext(Dispatchers.IO) { dao.recentByCanonical(can, now - 15 * 60 * 1000) }
        val saved = if (twin != null) {
            twin.copy(updatedAt = now, title = base.title, videoId = base.videoId ?: twin.videoId,
                      videoT = videoT ?: twin.videoT, isShort = base.isShort, refText = base.refText.ifBlank { twin.refText },
                      audioPath = base.audioPath ?: twin.audioPath)
        } else base
        val enriched = GemClient.enrich(ctx, saved) ?: saved
        withContext(Dispatchers.IO) { dao.upsert(enriched) }
        if (enriched.remote.not()) Sync.pushSave(enriched)
        return enriched
    }

    suspend fun storageUsed(ctx: Context): Long = withContext(Dispatchers.IO) {
        fun dir(f: File): Long = f.listFiles()?.sumOf { if (it.isDirectory) dir(it) else it.length() } ?: 0
        dir(ctx.filesDir) + dir(File(ctx.getDatabasePath("omnisave.db").parentFile ?: ctx.filesDir, "."))
    }
}
