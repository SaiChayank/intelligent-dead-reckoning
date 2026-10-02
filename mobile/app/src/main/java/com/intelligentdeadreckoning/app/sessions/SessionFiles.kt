package com.intelligentdeadreckoning.app.sessions

import com.intelligentdeadreckoning.contracts.recording.v1.*
import com.intelligentdeadreckoning.app.security.SafeSecurityMessages
import java.io.*
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.zip.*

data class SavedSession(val id: String, val metadata: RecordingMetadata?, val bytes: Long?, val error: String? = null) {
    val replayable: Boolean get() = error == null && metadata != null &&
        (metadata.completionState == CompletionState.COMPLETED ||
            (metadata.completionState == CompletionState.INCOMPLETE && metadata.recoveryState == RecoveryState.RECOVERED))
    val exportable: Boolean get() = error == null && metadata != null &&
        metadata.completionState != CompletionState.OPEN && metadata.recoveryState != RecoveryState.REQUIRED
    val durationNs: Long? get() = metadata?.clock?.let { c -> c.endedNs?.minus(c.startedNs) }
}
data class SessionPage(val sessions: List<SavedSession> = emptyList(), val next: String? = null)

/** Read-only private-session access. All methods must run on an I/O worker. */
class SessionFiles(rootProvider: () -> File) {
    private val root by lazy(rootProvider)

    private fun recordingsRoot(): Path {
        val path = root.toPath().toAbsolutePath().normalize()
        var ancestor: Path? = path
        while (ancestor != null) {
            require(!Files.isSymbolicLink(ancestor)) { "UNSAFE_RECORDING_ROOT" }
            ancestor = ancestor.parent
        }
        require(!Files.exists(path, NOFOLLOW_LINKS) || Files.isDirectory(path, NOFOLLOW_LINKS)) {
            "UNSAFE_RECORDING_ROOT"
        }
        return path
    }

    private fun directory(id: String): File {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "UNSAFE_SESSION_ID" }
        val base = recordingsRoot()
        val path = base.resolve(id).normalize()
        require(path.parent == base && !Files.isSymbolicLink(path)) { "UNSAFE_SESSION_PATH" }
        require(Files.isDirectory(path, NOFOLLOW_LINKS)) { "SESSION_NOT_FOUND" }
        return path.toFile()
    }

    private fun file(dir: File, name: String): File {
        require(name in setOf("metadata.json", "measurements.jsonl")) { "UNSAFE_ARTIFACT_NAME" }
        val parent = dir.toPath().toAbsolutePath().normalize()
        val path = parent.resolve(name).normalize()
        require(path.parent == parent && !Files.isSymbolicLink(path)) { "UNSAFE_SESSION_ARTIFACT" }
        var ancestor: Path? = parent
        while (ancestor != null) {
            require(!Files.isSymbolicLink(ancestor)) { "UNSAFE_SESSION_ARTIFACT" }
            ancestor = ancestor.parent
        }
        require(Files.isRegularFile(path, NOFOLLOW_LINKS)) { "SESSION_ARTIFACT_MISSING" }
        return path.toFile()
    }

    private fun safeError(error: Exception): String = when (error) {
        is SecurityException -> SafeSecurityMessages.code(error, "SESSION_ACCESS_DENIED")
        is IOException -> SafeSecurityMessages.code(error, "SESSION_IO_ERROR")
        else -> SafeSecurityMessages.code(error, "SESSION_INVALID")
    }
    private fun metadataBytes(dir: File): ByteArray = file(dir, "metadata.json").inputStream().use {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = it.read(buffer); if (n < 0) break
            require(out.size() + n <= 262144) { "METADATA_LIMIT" }
            out.write(buffer, 0, n)
        }
        out.toByteArray()
    }
    fun inspect(id: String): SavedSession = try {
        val dir = directory(id)
        val raw = metadataBytes(dir)
        val metadata = RecordingCodec.decodeMetadata(raw)
        require(metadata.recordingId == id) { "SESSION_METADATA_ID_MISMATCH" }
        SavedSession(id, metadata, Math.addExact(raw.size.toLong(), file(dir, "measurements.jsonl").length()))
    } catch (e: Exception) { SavedSession(id, null, null, safeError(e)) }

    /** Caller owns the stream. Never opens a writer or performs recovery. */
    fun openReplay(id: String): Pair<RecordingMetadata, InputStream> {
        val summary = inspect(id)
        require(summary.replayable) { summary.error ?: "SESSION_NOT_REPLAYABLE" }
        return summary.metadata!! to file(directory(id), "measurements.jsonl").inputStream().buffered()
    }

    /** Bounded 20-item pages, stable lexicographic ID order (not chronological). */
    fun list(after: String? = null): SessionPage {
        val base = recordingsRoot()
        if (!Files.exists(base, NOFOLLOW_LINKS)) return SessionPage()
        val ids = java.util.TreeSet<String>()
        Files.newDirectoryStream(base).use { stream ->
            stream.forEach { p ->
                val id = p.fileName.toString()
                if (!Files.isSymbolicLink(p) && Files.isDirectory(p, NOFOLLOW_LINKS) && (after == null || id > after)) {
                    ids.add(id)
                    if (ids.size > 21) ids.pollLast()
                }
            }
        }
        val visible = ids.take(20)
        return SessionPage(visible.map(::inspect), if (ids.size > 20) visible.last() else null)
    }

    /** Exact bytes, fixed entry order/time, STORED ZIP; two streaming passes, bounded memory.
     * Destination is opened only after preflight. Caller owns closing it, including on failure. */
    fun export(id: String, open: () -> OutputStream, checkCancelled: () -> Unit = {}) {
        val summary = inspect(id)
        require(summary.exportable) { summary.error ?: "SESSION_NOT_EXPORTABLE" }
        val dir = directory(id)
        val originalMetadata = metadataBytes(dir)
        val files = listOf(file(dir, "metadata.json"), file(dir, "measurements.jsonl"))
        val buffer = ByteArray(65536)
        val descriptors = files.map { f ->
            val crc = CRC32(); var count = 0L
            f.inputStream().use { input ->
                while (true) {
                    checkCancelled()
                    val n = input.read(buffer); if (n < 0) break
                    crc.update(buffer, 0, n); count = Math.addExact(count, n.toLong())
                }
            }
            count to crc.value
        }
        checkCancelled()
        open().use { output ->
            ZipOutputStream(output).use { zip ->
                files.forEachIndexed { i, f ->
                    val (size, checksum) = descriptors[i]
                    zip.putNextEntry(ZipEntry(f.name).apply {
                        method = ZipEntry.STORED; time = 0L; this.size = size; compressedSize = size; crc = checksum
                    })
                    f.inputStream().use { input ->
                        while (true) {
                            checkCancelled()
                            val n = input.read(buffer); if (n < 0) break
                            zip.write(buffer, 0, n)
                        }
                    }
                    zip.closeEntry() // also rejects changed size/CRC
                }
                require(originalMetadata.contentEquals(metadataBytes(dir))) { "SESSION_CHANGED_DURING_EXPORT" }
                checkCancelled()
            }
        }
    }
}
