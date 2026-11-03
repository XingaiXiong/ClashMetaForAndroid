package com.github.kr328.clash

import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/** Downloads finite, unencrypted MPEG-TS HLS media into a .ts file. */
object HlsDownloader {
    fun isPlaylistUrl(address: String): Boolean = runCatching {
        val uri = URI(address)
        (uri.scheme == "http" || uri.scheme == "https") &&
            !uri.host.isNullOrBlank() &&
            uri.path.endsWith(".m3u8", ignoreCase = true)
    }.getOrDefault(false)

    fun download(address: String, output: OutputStream): Int {
        require(isPlaylistUrl(address)) { "不是 M3U8 链接" }
        output.use { destination ->
            var current = address
            repeat(3) {
                val (base, playlist) = readPlaylist(current)
                val lines = playlist.lines().map { it.trim() }.filter { it.isNotEmpty() }
                require(lines.firstOrNull() == "#EXTM3U") { "播放清单格式不正确" }
                require(lines.none { it.startsWith("#EXT-X-KEY:") || it.startsWith("#EXT-X-SESSION-KEY:") }) {
                    "加密或 DRM 清单不支持"
                }
                require(lines.none { it.startsWith("#EXT-X-MAP:") || it.startsWith("#EXT-X-BYTERANGE:") }) {
                    "仅支持完整 MPEG-TS 片段"
                }
                if (lines.any { it.startsWith("#EXT-X-STREAM-INF:") }) {
                    val index = lines.indexOfFirst { it.startsWith("#EXT-X-STREAM-INF:") }
                    val child = lines.drop(index + 1).firstOrNull { !it.startsWith("#") }
                        ?: error("缺少子清单")
                    current = resolve(base, child)
                    return@repeat
                }
                require("#EXT-X-ENDLIST" in lines) { "直播或未结束的清单暂不支持下载" }
                val segments = lines.filter { !it.startsWith("#") }
                require(segments.isNotEmpty() && segments.size <= 10000) { "片段数量无效" }
                segments.forEach { item ->
                    val segment = resolve(base, item)
                    require(URI(segment).path.endsWith(".ts", ignoreCase = true)) { "仅支持 MPEG-TS 片段" }
                    connection(segment).useConnection { it.inputStream.use { input -> input.copyTo(destination) } }
                }
                destination.flush()
                return segments.size
            }
            error("子清单嵌套过深")
        }
    }

    private fun readPlaylist(address: String): Pair<String, String> =
        connection(address).useConnection { conn ->
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            require(body.length <= 2_000_000) { "清单过大" }
            conn.url.toString() to body
        }

    private fun resolve(base: String, relative: String): String {
        val result = URI(base).resolve(relative).toString()
        require(URI(result).scheme in listOf("http", "https")) { "只支持 HTTP(S)" }
        return result
    }

    private fun connection(address: String): HttpURLConnection {
        val conn = URL(address).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "ClashMetaVideoCapture/1.0")
        return conn
    }

    private inline fun <T> HttpURLConnection.useConnection(block: (HttpURLConnection) -> T): T =
        try {
            require(responseCode in 200..299) { "HTTP $responseCode" }
            block(this)
        } finally {
            disconnect()
        }
}
