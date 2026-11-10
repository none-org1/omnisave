package com.omnisave.app

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/* Same shape as the extension + sync engine (PLAN.md `Item`). */
@Entity(tableName = "items")
data class Item(
    @PrimaryKey val id: String,
    val createdAt: Long,
    val updatedAt: Long,
    val type: String,        // short | video | post | article | doc | image | website
    val platform: String,    // x | linkedin | youtube | reddit | discord | notion | instagram | app
    val url: String,
    val canonical: String,
    val title: String,
    val refText: String,
    val preview: String? = null,
    val videoId: String? = null,
    val videoT: Int? = null,
    val isShort: Boolean = false,
    val tags: String? = null,        // "a,b,c"
    val summary: String? = null,
    val audioPath: String? = null,   // compressed ≤30s mono AAC file in app storage, or null
    val source: String = "app",
    @Transient val remote: Boolean = false
) {
    fun tagsList(): List<String> = tags?.split(',')?.filter { it.isNotBlank() } ?: emptyList()
}

@Dao
interface ItemDao {
    @Query("SELECT * FROM items WHERE (:q = '' OR title LIKE '%'||:q||'%' OR refText LIKE '%'||:q||'%' OR url LIKE '%'||:q||'%' OR tags LIKE '%'||:q||'%') ORDER BY createdAt DESC")
    fun byQuery(q: String): Flow<List<Item>>

    @Query("SELECT * FROM items WHERE id = :id LIMIT 1")
    suspend fun one(id: String): Item?

    @Query("SELECT * FROM items WHERE canonical = :can AND createdAt > :since LIMIT 1")
    suspend fun recentByCanonical(can: String, since: Long): Item?

    @Upsert suspend fun upsert(item: Item)
    @Query("DELETE FROM items WHERE id = :id") suspend fun remove(id: String)
    @Query("SELECT COUNT(*) FROM items") suspend fun count(): Int
    @Query("SELECT * FROM items") suspend fun all(): List<Item>
    @Query("DELETE FROM items") suspend fun wipe()
}

@Database(entities = [Item::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): ItemDao
    companion object {
        @Volatile private var db: AppDatabase? = null
        fun get(ctx: android.content.Context): AppDatabase = db ?: synchronized(this) {
            db ?: Room.databaseBuilder(ctx.applicationContext, AppDatabase::class.java, "omnisave.db").build().also { db = it }
        }
    }
}
