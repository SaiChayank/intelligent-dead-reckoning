package com.intelligentdeadreckoning.app.map

import android.content.Context
import com.google.gson.JsonParser
import java.io.File
import java.nio.file.Files
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
        val manifest = context.assets.open("$prefix/manifest.json").bufferedReader().use { JsonParser.parseReader(it).asJsonObject }
        require(manifest["version"].asInt == 1 && manifest["id"].asString == "hyderabad-v1") { "Unsupported offline pack" }
        val expectedPaths = setOf("hyderabad.mbtiles") + listOf("0-255","256-511","512-767","768-1023").map { "fonts/Noto Sans Regular/$it.pbf" }
        val resources = manifest["files"].asJsonArray.map { it.asJsonObject }
        require(resources.map { it["path"].asString }.toSet() == expectedPaths && resources.size == expectedPaths.size) { "Unexpected pack files" }
        val root = File(context.noBackupFilesDir,"offline-maps/hyderabad-v1")
        var total = 0L
        for (entry in resources) {
            val path = entry["path"].asString
            val length = entry["bytes"].asLong
            val hash = entry["sha256"].asString
            require(length in 1..100L*1024*1024 && hash.matches(Regex("[0-9a-f]{64}"))) { "Invalid pack resource" }
            total = Math.addExact(total,length)
            require(total <= 100L*1024*1024) { "Pack exceeds size budget" }
            val target = File(root,path)
            if (target.isFile && target.length() == length && digest(target) == hash) continue
            check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs()) { "Map storage unavailable" }
            val temporary = File.createTempFile("map-install-", ".tmp", target.parentFile)
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
            } finally { temporary.delete() } // only this installer-owned temporary file
        }
        return InstalledMap(root,total,manifest["tile_count"].asInt)
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
