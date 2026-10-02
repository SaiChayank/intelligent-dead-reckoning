package com.intelligentdeadreckoning.app.map

import android.content.Context
import com.google.gson.JsonParser
import com.intelligentdeadreckoning.app.security.PrivateAssetPaths
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

data class InstalledMap(val directory: File, val bytes: Long, val tiles: Int) {
    val style: String get() = OfflineMapStyle.json(File(directory,"hyderabad.mbtiles").absolutePath,
        "file://${directory.absolutePath}/fonts/{fontstack}/{range}.pbf")
}

/** Bundled pack only. Called off Main. No remote URL loading, cache eviction or raw-data access. */
class OfflineMapPack(private val context: Context) {
    fun install(): InstalledMap {
        val prefix = "offline/hyderabad"
        val manifestBytes = readAssetBounded("$prefix/manifest.json", 256 * 1024)
        val manifest = JsonParser.parseString(manifestBytes.toString(Charsets.UTF_8)).asJsonObject
        require(manifest["version"].asInt == 1 && manifest["id"].asString == "hyderabad-v1") { "Unsupported offline pack" }
        val expectedPaths = setOf("hyderabad.mbtiles") + listOf("0-255","256-511","512-767","768-1023").map { "fonts/Noto Sans Regular/$it.pbf" }
        val resources = manifest["files"].asJsonArray.map { it.asJsonObject }
        require(resources.map { it["path"].asString }.toSet() == expectedPaths && resources.size == expectedPaths.size) { "Unexpected pack files" }
        val privateRoot = PrivateAssetPaths.directory(context.noBackupFilesDir, "offline-maps")
        val root = PrivateAssetPaths.directory(privateRoot, "hyderabad-v1")
        var total = 0L
        for (entry in resources) {
            val path = entry["path"].asString
            val length = entry["bytes"].asLong
            val hash = entry["sha256"].asString
            require(length in 1..100L*1024*1024 && hash.matches(Regex("[0-9a-f]{64}"))) { "Invalid pack resource" }
            total = Math.addExact(total,length)
            require(total <= 100L*1024*1024) { "Pack exceeds size budget" }
            val target = PrivateAssetPaths.file(root, path)
            if (Files.isRegularFile(target.toPath(), NOFOLLOW_LINKS) && target.length() == length && digest(target) == hash) continue

            val parent = target.parentFile!!
            val temporary = Files.createTempFile(parent.toPath(), "map-install-", ".tmp").toFile()
            try {
                context.assets.open("$prefix/$path").use { input ->
                    temporary.outputStream().use { out ->
                        val buffer = ByteArray(65536); var written = 0L
                        while (true) {
                            val n = input.read(buffer); if(n < 0) break
                            written += n; require(written <= length) { "Oversized map asset" }
                            out.write(buffer,0,n)
                        }
                        out.fd.sync()
                    }
                }
                require(temporary.length() == length && digest(temporary) == hash) { "Map checksum mismatch" }
                Files.move(temporary.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
            } finally { Files.deleteIfExists(temporary.toPath()) } // only this installer-owned temporary file
        }
        return InstalledMap(root,total,manifest["tile_count"].asInt)
    }

    private fun readAssetBounded(path: String, maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream()
        context.assets.open(path).use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= maxBytes) { "Offline map manifest is too large" }
                output.write(buffer, 0, count)
            }
        }
        return output.toByteArray()
    }

    private fun digest(file: File): String {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while(true) { val n = input.read(buffer); if(n < 0) break; hash.update(buffer,0,n) }
        }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }
}
