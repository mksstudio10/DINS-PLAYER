package com.dinsplayer.app.data.db

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.dinsplayer.app.data.models.Playlist
import com.dinsplayer.app.data.models.PlaylistType
import kotlinx.coroutines.flow.Flow

// ── Type Converter ───────────────────────────────────────────────────────────

class Converters {
    @TypeConverter fun fromPlaylistType(v: PlaylistType): String = v.name
    @TypeConverter fun toPlaylistType(v: String): PlaylistType = PlaylistType.valueOf(v)
}

// ── DAO ──────────────────────────────────────────────────────────────────────

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<Playlist>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(playlist: Playlist): Long

    @Delete
    suspend fun delete(playlist: Playlist)

    @Update
    suspend fun update(playlist: Playlist)

    /**
     * Safe save: routes to update when id > 0 (existing row), insert when id == 0 (new row).
     * Prevents accidental double-insert that REPLACE strategy can cause when called twice
     * (D-pad double-fire or race condition).
     */
    @Transaction
    suspend fun upsert(playlist: Playlist): Long {
        return if (playlist.id > 0L) {
            update(playlist)
            playlist.id
        } else {
            insert(playlist)
        }
    }

    @Query("UPDATE playlists SET isActive = 0")
    suspend fun deactivateAll()

    /**
     * Exactly one playlist may be active. Deactivates every other row first, inside a single
     * transaction, so the UI can never observe two active playlists.
     */
    @Transaction
    suspend fun setActiveExclusive(id: Long) {
        deactivateAll()
        setActive(id, true)
    }

    @Query("UPDATE playlists SET isActive = :active WHERE id = :id")
    suspend fun setActive(id: Long, active: Boolean)
}

// ── Database ─────────────────────────────────────────────────────────────────

/**
 * v1 → v2: tambah kolom userAgent (nullable TEXT).
 * Penambahan userAgent untuk override User-Agent per playlist.
 */
private val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE playlists ADD COLUMN userAgent TEXT")
    }
}

/**
 * v2 → v3: tambah kolom customHeaders (TEXT NOT NULL DEFAULT '').
 * Penambahan custom HTTP headers per playlist (format: "Key: Value\nKey2: Value2").
 */
private val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE playlists ADD COLUMN customHeaders TEXT NOT NULL DEFAULT ''")
    }
}

@Database(entities = [Playlist::class], version = 3, exportSchema = false)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun playlistDao(): PlaylistDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "dins_player.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .fallbackToDestructiveMigrationOnDowngrade()
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
