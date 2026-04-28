package app.musicplayer.restaurant.sync

import android.content.Context
import android.util.Log
import app.musicplayer.restaurant.data.ServerApi
import app.musicplayer.restaurant.data.Settings
import kotlinx.coroutines.flow.first
import java.io.File
import java.security.MessageDigest

data class SyncResult(val downloaded: Int, val deleted: Int, val total: Int)

object Syncer {
    private const val TAG = "Syncer"

    suspend fun sync(context: Context): SyncResult {
        val settings = Settings(context)
        val baseUrl = settings.serverUrl.first()
        val api = ServerApi(baseUrl)

        // Cache the latest hours alongside the inventory
        runCatching { settings.setHoursJson(api.getHoursJson()) }
            .onFailure { Log.w(TAG, "hours fetch failed", it) }

        val list = api.getList()
        val musicDir = musicDir(context).also { it.mkdirs() }

        val byName = list.tracks.associateBy { it.name }
        val locals = musicDir.listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(".mp3", ignoreCase = true) }

        var deleted = 0
        for (file in locals) {
            val server = byName[file.name]
            if (server == null) {
                if (file.delete()) deleted++
                continue
            }
            // Hash check is the slow part of sync but only matters when files
            // were re-uploaded under the same name. Skip when sizes mismatch
            // outright — we'll redownload anyway.
            if (file.length() != server.size || !sha1(file).equals(server.sha1, ignoreCase = true)) {
                if (file.delete()) deleted++
            }
        }

        val present = musicDir.listFiles().orEmpty().map { it.name }.toSet()
        var downloaded = 0
        for (track in list.tracks) {
            if (track.name in present) continue
            try {
                api.downloadTrack(track.name, File(musicDir, track.name))
                downloaded++
            } catch (t: Throwable) {
                Log.w(TAG, "download failed for ${track.name}", t)
                throw t
            }
        }

        // Tidy up partial downloads from a previous interrupted run
        musicDir.listFiles().orEmpty()
            .filter { it.name.endsWith(".part") }
            .forEach { it.delete() }

        return SyncResult(downloaded, deleted, list.tracks.size)
    }

    fun musicDir(context: Context): File = File(context.filesDir, "music")

    private fun sha1(f: File): String {
        val md = MessageDigest.getInstance("SHA-1")
        f.inputStream().use { stream ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = stream.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
