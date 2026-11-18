package com.gabo.ytmp3

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import java.io.File

/**
 * Biblioteca central: cada canción existe una sola vez ([Track]); las playlists solo
 * apuntan a ellas ([PlaylistTrack]). Los archivos viven en la memoria privada de la app,
 * salvo la música importada del celular, que se lee en su lugar (sin copiarla).
 */
@Entity(tableName = "tracks", indices = [Index(value = ["key"], unique = true)])
data class Track(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Identidad para evitar duplicados: "youtube:dQw4w9WgXcQ", "local:12345"… */
    val key: String,
    val title: String,
    val artist: String = "",
    val durationMs: Long = 0,
    /** Ruta del MP3 propio (descargas) o null si es música importada. */
    val audioPath: String? = null,
    /** content:// de la música importada del celular. */
    val localUri: String? = null,
    val videoPath: String? = null,
    val coverPath: String? = null,
    val gainDb: Float? = null,
    val addedAt: Long = System.currentTimeMillis(),
    val audioBytes: Long = 0,
    val videoBytes: Long = 0,
    @ColumnInfo(defaultValue = "0") val playCount: Int = 0,
    @ColumnInfo(defaultValue = "0") val lastPlayed: Long = 0,
) {
    val isLocal get() = localUri != null
    val hasVideo get() = videoPath != null && File(videoPath).exists()
    fun audioUri(): Uri = localUri?.let(Uri::parse) ?: Uri.fromFile(File(audioPath ?: videoPath ?: ""))
    fun videoUri(): Uri? = videoPath?.let { Uri.fromFile(File(it)) }
}

@Entity(tableName = "playlists")
data class Playlist(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val coverPath: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val showVideo: Boolean = false,
)

@Entity(tableName = "playlist_tracks", primaryKeys = ["playlistId", "trackId"],
    indices = [Index("trackId")])
data class PlaylistTrack(val playlistId: Long, val trackId: Long, val position: Int, val addedAt: Long = System.currentTimeMillis())

/** Fila de la biblioteca: playlist + cuántas canciones + portadas para el mosaico. */
data class PlaylistRow(
    val id: Long, val name: String, val coverPath: String?, val showVideo: Boolean,
    val count: Int, val covers: String?, val firstTrackId: Long?,
)

data class PlaylistSize(val id: Long, val name: String, val audio: Long, val video: Long, val count: Int)

@Dao
interface LibDao {
    @Query("""SELECT p.id, p.name, p.coverPath, p.showVideo,
        (SELECT COUNT(*) FROM playlist_tracks x WHERE x.playlistId = p.id) AS count,
        (SELECT GROUP_CONCAT(c, '|') FROM (SELECT t.coverPath AS c FROM playlist_tracks x JOIN tracks t ON t.id = x.trackId
            WHERE x.playlistId = p.id AND t.coverPath IS NOT NULL ORDER BY x.position LIMIT 4)) AS covers,
        (SELECT x.trackId FROM playlist_tracks x WHERE x.playlistId = p.id ORDER BY x.position LIMIT 1) AS firstTrackId
        FROM playlists p ORDER BY p.createdAt DESC""")
    fun playlistRows(): Flow<List<PlaylistRow>>

    @Query("SELECT * FROM playlists ORDER BY createdAt DESC") suspend fun playlists(): List<Playlist>
    @Query("SELECT * FROM playlists WHERE id = :id") suspend fun playlist(id: Long): Playlist?
    @Query("SELECT * FROM playlists WHERE id = :id") fun playlistFlow(id: Long): Flow<Playlist?>
    @Insert suspend fun insertPlaylist(p: Playlist): Long
    @Update suspend fun updatePlaylist(p: Playlist)
    @Query("DELETE FROM playlists WHERE id = :id") suspend fun deletePlaylist(id: Long)
    @Query("DELETE FROM playlist_tracks WHERE playlistId = :id") suspend fun clearPlaylist(id: Long)

    @Query("SELECT t.* FROM playlist_tracks x JOIN tracks t ON t.id = x.trackId WHERE x.playlistId = :pid ORDER BY x.position")
    fun tracksOf(pid: Long): Flow<List<Track>>
    @Query("SELECT t.* FROM playlist_tracks x JOIN tracks t ON t.id = x.trackId WHERE x.playlistId = :pid ORDER BY x.position")
    suspend fun tracksOfNow(pid: Long): List<Track>
    @Query("SELECT * FROM tracks ORDER BY addedAt DESC") fun allTracks(): Flow<List<Track>>
    @Query("SELECT * FROM tracks ORDER BY addedAt DESC") suspend fun allTracksNow(): List<Track>
    @Query("SELECT COUNT(*) FROM tracks") fun trackCount(): Flow<Int>

    @Query("SELECT * FROM tracks WHERE key = :key") suspend fun byKey(key: String): Track?
    @Query("SELECT * FROM tracks WHERE id = :id") suspend fun track(id: Long): Track?
    @Query("SELECT key FROM tracks WHERE key LIKE 'local:%'") suspend fun localKeys(): List<String>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertTrack(t: Track): Long
    @Update suspend fun updateTrack(t: Track)
    @Query("DELETE FROM tracks WHERE id = :id") suspend fun deleteTrack(id: Long)
    @Query("DELETE FROM playlist_tracks WHERE trackId = :id") suspend fun unlinkTrack(id: Long)
    @Query("SELECT * FROM tracks WHERE gainDb IS NULL LIMIT 20") suspend fun needGain(): List<Track>
    @Query("SELECT * FROM tracks WHERE playCount > 0 ORDER BY playCount DESC, lastPlayed DESC LIMIT 50") fun topTracks(): Flow<List<Track>>
    @Query("SELECT * FROM tracks WHERE localUri IS NOT NULL ORDER BY title COLLATE NOCASE") fun localTracks(): Flow<List<Track>>
    @Query("SELECT * FROM tracks WHERE lastPlayed > 0 ORDER BY lastPlayed DESC LIMIT 20") fun recentTracks(): Flow<List<Track>>
    @Query("UPDATE tracks SET playCount = playCount + 1, lastPlayed = :now WHERE id = :id") suspend fun bump(id: Long, now: Long)
    @Query("SELECT * FROM tracks WHERE id IN (:ids)") suspend fun tracksByIds(ids: List<Long>): List<Track>

    @Query("SELECT COALESCE(MAX(position), -1) FROM playlist_tracks WHERE playlistId = :pid") suspend fun maxPos(pid: Long): Int
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun link(x: PlaylistTrack): Long
    @Query("DELETE FROM playlist_tracks WHERE playlistId = :pid AND trackId = :tid") suspend fun unlink(pid: Long, tid: Long)
    @Query("SELECT COUNT(*) FROM playlist_tracks WHERE trackId = :tid") suspend fun linkCount(tid: Long): Int

    @Query("""SELECT p.id, p.name, COALESCE(SUM(t.audioBytes), 0) AS audio, COALESCE(SUM(t.videoBytes), 0) AS video,
        COUNT(t.id) AS count FROM playlists p LEFT JOIN playlist_tracks x ON x.playlistId = p.id
        LEFT JOIN tracks t ON t.id = x.trackId GROUP BY p.id ORDER BY (audio + video) DESC""")
    suspend fun sizes(): List<PlaylistSize>
    @Query("SELECT COALESCE(SUM(audioBytes), 0) FROM tracks") suspend fun totalAudio(): Long
    @Query("SELECT COALESCE(SUM(videoBytes), 0) FROM tracks") suspend fun totalVideo(): Long
}

@Database(entities = [Track::class, Playlist::class, PlaylistTrack::class], version = 2, exportSchema = false)
abstract class LibDb : RoomDatabase() {
    abstract fun dao(): LibDao
}

private val M1_2 = object : androidx.room.migration.Migration(1, 2) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE tracks ADD COLUMN playCount INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE tracks ADD COLUMN lastPlayed INTEGER NOT NULL DEFAULT 0")
    }
}

object Library {
    @Volatile private var db: LibDb? = null

    fun dao(ctx: Context): LibDao = (db ?: synchronized(this) {
        db ?: Room.databaseBuilder(ctx.applicationContext, LibDb::class.java, "library.db").addMigrations(M1_2).build().also { db = it }
    }).dao()

    fun dir(ctx: Context, sub: String) = File(ctx.filesDir, "library/$sub").apply { mkdirs() }

    fun safeKey(key: String) = key.replace(Regex("[^A-Za-z0-9_-]"), "_").take(80)

    suspend fun createPlaylist(ctx: Context, name: String): Long =
        dao(ctx).insertPlaylist(Playlist(name = name.trim().ifEmpty { "Mi playlist" }))

    /** Añade canciones al final de las playlists (sin duplicar dentro de cada una). */
    suspend fun addToPlaylists(ctx: Context, trackIds: List<Long>, playlistIds: Collection<Long>) {
        val d = dao(ctx)
        for (pid in playlistIds) {
            var pos = d.maxPos(pid)
            for (tid in trackIds) if (d.link(PlaylistTrack(pid, tid, pos + 1)) != -1L) pos++
        }
    }

    /** Guarda (o reutiliza) una canción. Devuelve su id. */
    suspend fun upsert(ctx: Context, t: Track): Long {
        val d = dao(ctx)
        val old = d.byKey(t.key) ?: return d.insertTrack(t)
        // Ya existía: completar lo que falte (p. ej. ahora trae video) sin perder lo editado.
        val merged = old.copy(
            audioPath = old.audioPath ?: t.audioPath,
            videoPath = old.videoPath?.takeIf { File(it).exists() } ?: t.videoPath,
            coverPath = old.coverPath ?: t.coverPath,
            durationMs = if (old.durationMs > 0) old.durationMs else t.durationMs,
            audioBytes = if (old.audioPath != null) old.audioBytes else t.audioBytes,
            videoBytes = if (old.videoPath?.let { File(it).exists() } == true) old.videoBytes else t.videoBytes,
        )
        if (merged != old) d.updateTrack(merged)
        return old.id
    }

    /** Borra la canción de toda la biblioteca y sus archivos propios. */
    suspend fun deleteTrack(ctx: Context, t: Track) {
        val d = dao(ctx)
        d.unlinkTrack(t.id); d.deleteTrack(t.id)
        listOfNotNull(t.audioPath, t.videoPath, t.coverPath).forEach { p ->
            if (p.startsWith(ctx.filesDir.absolutePath)) File(p).delete()
        }
    }

    /** Libera espacio: borra los videos (queda el audio) de una playlist o de todo. */
    suspend fun deleteVideos(ctx: Context, playlistId: Long?): Long {
        val d = dao(ctx)
        val list = if (playlistId == null) d.allTracksNow() else d.tracksOfNow(playlistId)
        var freed = 0L
        for (t in list) {
            val v = t.videoPath ?: continue
            if (t.audioPath == null) continue   // sin audio aparte: el video es la única copia
            freed += File(v).length()
            File(v).delete()
            d.updateTrack(t.copy(videoPath = null, videoBytes = 0))
        }
        return freed
    }

    // ------------------------------------------------------------------ música del celular
    data class LocalSong(val id: Long, val uri: Uri, val title: String, val artist: String,
                         val durationMs: Long, val folder: String, val size: Long, val albumId: Long)

    fun scanDevice(ctx: Context): List<LocalSong> {
        val out = ArrayList<LocalSong>()
        val proj = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.DATA, MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.ALBUM_ID)
        val sel = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} >= 30000"
        runCatching {
            ctx.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, proj, sel, null,
                "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE")?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val data = c.getString(4) ?: ""
                    val folder = File(data).parentFile?.name ?: "Música"
                    val artist = c.getString(2)?.takeIf { it != "<unknown>" } ?: ""
                    out += LocalSong(id, ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id),
                        c.getString(1) ?: "Canción", artist, c.getLong(3), folder, c.getLong(5), c.getLong(6))
                }
            }
        }
        return out
    }

    /** Importa canciones del celular (se leen en su sitio). Devuelve los ids en la biblioteca. */
    suspend fun importLocal(ctx: Context, songs: List<LocalSong>): List<Long> {
        val ids = ArrayList<Long>()
        val covers = dir(ctx, "covers")
        for (s in songs) {
            val cover = File(covers, "local_${s.id}.jpg")
            if (!cover.exists()) runCatching {
                val bmp = if (android.os.Build.VERSION.SDK_INT >= 29)
                    ctx.contentResolver.loadThumbnail(s.uri, android.util.Size(512, 512), null)
                else null
                bmp?.let { b -> cover.outputStream().use { b.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, it) } }
            }
            ids += upsert(ctx, Track(key = "local:${s.id}", title = s.title, artist = s.artist, durationMs = s.durationMs,
                localUri = s.uri.toString(), coverPath = cover.takeIf { it.exists() }?.absolutePath))
        }
        Loudness.kick(ctx)
        return ids
    }
}
