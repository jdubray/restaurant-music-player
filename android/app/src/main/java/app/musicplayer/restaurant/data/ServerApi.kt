package app.musicplayer.restaurant.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class ServerApi(private val baseUrl: String) {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.MINUTES)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun getList(): TrackList = withContext(Dispatchers.IO) {
        get("/list") { body -> json.decodeFromString(TrackList.serializer(), body.string()) }
    }

    suspend fun getHoursJson(): String = withContext(Dispatchers.IO) {
        get("/hours") { body -> body.string() }
    }

    suspend fun getHours(): HoursConfig = withContext(Dispatchers.IO) {
        json.decodeFromString(HoursConfig.serializer(), getHoursJson())
    }

    suspend fun isHealthy(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url("$baseUrl/health").build()
            client.newCall(req).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    /** Streams the track to a temp file and atomically moves it into place. */
    suspend fun downloadTrack(name: String, dest: File): Unit = withContext(Dispatchers.IO) {
        val url = "$baseUrl/tracks/".toHttpUrl().newBuilder()
            .addPathSegment(name)
            .build()
        val req = Request.Builder().url(url).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("track $name HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("track $name empty body")
            val tmp = File(dest.parentFile, "${dest.name}.part")
            tmp.outputStream().use { out -> body.byteStream().copyTo(out) }
            if (dest.exists() && !dest.delete()) {
                tmp.delete()
                throw IOException("could not replace $dest")
            }
            if (!tmp.renameTo(dest)) {
                tmp.delete()
                throw IOException("rename failed: $tmp -> $dest")
            }
        }
    }

    private inline fun <T> get(path: String, block: (okhttp3.ResponseBody) -> T): T {
        val req = Request.Builder().url("$baseUrl$path").build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("$path HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("$path empty body")
            return block(body)
        }
    }
}
