package com.intelligentdeadreckoning.app.matching

import android.content.Context
import com.google.gson.JsonParser
import com.intelligentdeadreckoning.app.security.PrivateAssetPaths
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.security.MessageDigest

/**
 * Bundled road-graph pack only. Called off Main. No remote URL loading, no cache eviction,
 * no tile or sensor access: this installs the matching graph (the MBTiles render pack is a
 * different asset with a different owner) and returns the loaded [RoadGraph].
 *
 * Integrity follows the one-check discipline: the copy is size-bounded, and the byte-exact
 * SHA-256 verification lives in [RoadGraph.verifyManifest], which runs before [RoadGraph.load].
 */
class RoadGraphPack(private val context: Context) {
    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun install(): RoadGraph {
        val prefix = "roadgraph/hyderabad-v1"
        val manifestBytes = readAssetBounded("$prefix/manifest.json", 256 * 1024)
        val manifest = JsonParser.parseString(manifestBytes.toString(Charsets.UTF_8)).asJsonObject
        require(manifest["format"].asString == RoadGraph.FORMAT) { "Unsupported road-graph format" }
        require(manifest["id"].asString == "hyderabad-road-graph-v1" && manifest["version"].asInt == 1) {
            "Unsupported road graph"
        }
        val resources = manifest["files"].asJsonArray.map { it.asJsonObject }
        require(resources.isNotEmpty()) { "Road graph lists no files" }
        val root = PrivateAssetPaths.directory(context.noBackupFilesDir, "roadgraph/hyderabad-v1")
        var total = 0L
        for (entry in resources) {
            val path = entry["path"].asString
            val length = entry["bytes"].asLong
            require(path == RoadGraph.GRAPH_FILE && length in 1..64L * 1024 * 1024) { "Invalid road-graph resource" }
            total = Math.addExact(total, length)
            val target = PrivateAssetPaths.file(root, path)
            val expectedHash = entry["sha256"].asString
            require(expectedHash.matches(Regex("[0-9a-f]{64}"))) { "Invalid road-graph checksum" }
            if (Files.isRegularFile(target.toPath(), NOFOLLOW_LINKS) && target.length() == length && sha256(target) == expectedHash) continue
            val temporary = Files.createTempFile(target.parentFile!!.toPath(), "graph-install-", ".tmp").toFile()
            try {
                context.assets.open("$prefix/$path").use { input ->
                    temporary.outputStream().use { out ->
                        val buffer = ByteArray(65536)
                        var written = 0L
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            written += n
                            require(written <= length) { "Oversized road-graph asset" }
                            out.write(buffer, 0, n)
                        }
                        out.fd.sync()
                    }
                }
                require(temporary.length() == length && sha256(temporary) == expectedHash) { "Road-graph integrity mismatch" }
                Files.move(
                    temporary.toPath(), target.toPath(),
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                )
            } finally {
                Files.deleteIfExists(temporary.toPath()) // only this installer-owned temporary file
            }
        }
        val manifestTarget = PrivateAssetPaths.file(root, "manifest.json")
        val manifestTemporary = Files.createTempFile(manifestTarget.parentFile!!.toPath(), "manifest-install-", ".tmp").toFile()
        try {
            context.assets.open("$prefix/manifest.json").use { input ->
                manifestTemporary.outputStream().use { out ->
                input.copyTo(out)
                out.fd.sync()
                }
            }
            require(manifestTemporary.length() == manifestBytes.size.toLong() &&
                sha256(manifestTemporary) == sha256(manifestBytes)) { "Road-graph manifest integrity mismatch" }
            Files.move(
                manifestTemporary.toPath(), manifestTarget.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            Files.deleteIfExists(manifestTemporary.toPath())
        }
        val graphFile = File(root, RoadGraph.GRAPH_FILE)
        // The single byte-exact integrity check (size + SHA-256 against the manifest).
        RoadGraph.verifyManifest(graphFile, manifestTarget)
        return RoadGraph.load(graphFile)
    }

    private fun readAssetBounded(path: String, maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream()
        context.assets.open(path).use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= maxBytes) { "Road-graph manifest is too large" }
                output.write(buffer, 0, count)
            }
        }
        return output.toByteArray()
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
