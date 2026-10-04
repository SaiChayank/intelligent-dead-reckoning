package com.intelligentdeadreckoning.app.recording

import com.google.gson.JsonParser
import com.intelligentdeadreckoning.contracts.recording.v1.*
import com.intelligentdeadreckoning.contracts.v1.Limits
import java.io.*
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Called exclusively by recorder I/O workers. Root is Context.noBackupFilesDir/recordings. */
class FileRecordingStorage(rootProvider: () -> File) : RecordingStorage {
    constructor(root: File) : this({ root })
    // Android may create noBackupFilesDir on access: resolve it only on the I/O worker.
    private val root by lazy(rootProvider)
    private companion object {
        val processLock = Any()
        val activeDirectories = hashSetOf<String>()
    }
    private fun recordingsRoot(create: Boolean = false): File {
        val path = root.toPath().toAbsolutePath().normalize()
        var ancestor: java.nio.file.Path? = path
        while (ancestor != null) {
            require(!Files.isSymbolicLink(ancestor)) { "UNSAFE_RECORDING_ROOT" }
            ancestor = ancestor.parent
        }
        if (create && !Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectories(path)
        }
        require(!Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS) ||
            Files.isDirectory(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) { "UNSAFE_RECORDING_ROOT" }
        return path.toFile()
    }

    private fun directory(id: String): File {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "UNSAFE_RECORDING_ID" }
        val base = recordingsRoot()
        val path = base.toPath().resolve(id).normalize()
        require(path.parent == base.toPath() && !Files.isSymbolicLink(path)) { "UNSAFE_RECORDING_PATH" }
        return path.toFile()
    }

    private fun regularFileForCreate(dir: File, name: String): File {
        require(name == "measurements.jsonl") { "UNSAFE_RECORDING_ARTIFACT" }
        val parent = dir.toPath().toAbsolutePath().normalize()
        require(Files.isDirectory(parent, java.nio.file.LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(parent)) {
            "UNSAFE_RECORDING_PATH"
        }
        val path = parent.resolve(name)
        require(!Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)) {
            "UNSAFE_RECORDING_ARTIFACT"
        }
        return path.toFile()
    }

    private fun regularFile(dir: File, name: String): File {
        require(name in setOf("metadata.json", "measurements.jsonl")) { "UNSAFE_RECORDING_ARTIFACT" }
        val parent = dir.toPath().toAbsolutePath().normalize()
        val path = parent.resolve(name).normalize()
        require(path.parent == parent && !Files.isSymbolicLink(path) &&
            Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) { "UNSAFE_RECORDING_ARTIFACT" }
        return path.toFile()
    }

    override fun create(metadata: RecordingMetadata): RecordingOutput = synchronized(processLock) {
        val raw = RecordingCodec.encodeMetadata(metadata)
        val base = recordingsRoot(create = true)
        val dir = directory(metadata.recordingId)
        check(dir.parentFile?.toPath() == base.toPath()) { "UNSAFE_RECORDING_PATH" }
        Files.createDirectory(dir.toPath()) // Never overwrite an existing recording.
        activeDirectories.add(dir.absolutePath)
        try {
            atomicMetadata(dir, raw)
            val file = regularFileForCreate(dir, "measurements.jsonl")
            check(file.createNewFile()) { "MEASUREMENTS_ALREADY_EXIST" }
            val output = FileOutputStream(file)
            object : RecordingOutput {
                override fun append(bytes: ByteArray) { output.write(bytes + byteArrayOf(10)) }
                override fun sync() { output.fd.sync() }
                override fun close() { output.close() }
            }
        } catch (e: Exception) {
            activeDirectories.remove(dir.absolutePath)
            throw e
        }
    }
    override fun finalize(metadata: RecordingMetadata) = synchronized(processLock) {
        val dir = directory(metadata.recordingId)
        try { atomicMetadata(dir, RecordingCodec.encodeMetadata(metadata)) }
        finally { activeDirectories.remove(dir.absolutePath) }
    }

    private fun atomicMetadata(dir: File, bytes: ByteArray) {
        require(Files.isDirectory(dir.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS) &&
            !Files.isSymbolicLink(dir.toPath())) { "UNSAFE_RECORDING_PATH" }
        val target = dir.toPath().resolve("metadata.json")
        require(!Files.exists(target, java.nio.file.LinkOption.NOFOLLOW_LINKS) ||
            Files.isRegularFile(target, java.nio.file.LinkOption.NOFOLLOW_LINKS)) { "UNSAFE_RECORDING_ARTIFACT" }
        val temporary = Files.createTempFile(dir.toPath(), "metadata-", ".tmp")
        try {
            FileOutputStream(temporary.toFile()).use { it.write(bytes); it.fd.sync() }
            // If atomic rename is unsupported/fails, propagate failure; keep previous metadata.
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    override fun recover(): RecoverySummary = synchronized(processLock) {
        val base = recordingsRoot()
        if (!Files.exists(base.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) return@synchronized RecoverySummary()
        var recovered = 0
        var failed = 0
        var trimmedBytes = 0L
        Files.newDirectoryStream(base.toPath()).use { paths ->
            for (path in paths) {
                if (Files.isSymbolicLink(path)) { failed++; continue }
                if (!Files.isDirectory(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) continue
                if (path.toFile().absolutePath in activeDirectories) continue
                try {
                    val dir = path.toFile()
                    val metaFile = regularFile(dir, "metadata.json")
                    require(metaFile.length() in 1..262_144) { "METADATA_LIMIT" }
                    val metadata = RecordingCodec.decodeMetadata(metaFile.readBytes())
                    require(directory(metadata.recordingId).absoluteFile.normalize() == dir.absoluteFile.normalize()) { "DIRECTORY_ID_MISMATCH" }
                    if (metadata.completionState == CompletionState.OPEN || metadata.recoveryState == RecoveryState.REQUIRED) {
                        try {
                            val result = recoverSession(dir, metadata)
                            finalize(result.first)
                            trimmedBytes += result.second
                            recovered++
                        } catch (e: Exception) {
                            // No skip/repair of complete corruption. Preserve the measurement bytes.
                            runCatching {
                                finalize(metadata.copy(completionState = CompletionState.INCOMPLETE,
                                    endState = RecordingEndState.INTERRUPTED, recoveryState = RecoveryState.UNRECOVERABLE,
                                    recordCount = null, channelCounts = null))
                            }
                            failed++
                        }
                    } else if (metadata.recoveryState == RecoveryState.UNRECOVERABLE) {
                        failed++
                    }
                } catch (_: Exception) {
                    failed++
                }
            }
        }
        RecoverySummary(recovered, failed, "Recovered/incomplete: $recovered; trimmed trailing bytes: $trimmedBytes; rejected: $failed.")
    }

    private fun recoverSession(dir: File, metadata: RecordingMetadata): Pair<RecordingMetadata, Long> {
        val file = regularFile(dir, "measurements.jsonl")
        val guard = AcquisitionRecordGuard(metadata)
        var count = 0L
        val counts = linkedMapOf<String, Long>()
        var offset = 0L
        var trimAt: Long? = null
        file.inputStream().buffered().use { input ->
            while (true) {
                val lineStart = offset
                val line = ByteArrayOutputStream()
                var lf = false
                while (true) {
                    val b = input.read()
                    if (b < 0) break
                    offset++
                    if (b == 10) { lf = true; break }
                    require(line.size() < Limits().maxRecordBytes) { "RECORD_TOO_LARGE at line ${count + 1}" }
                    line.write(b)
                }
                val raw = line.toByteArray()
                if (raw.isEmpty() && !lf) break
                val record = try { RecordingCodec.decodeRecord(raw, metadata) }
                catch (e: IllegalArgumentException) {
                    if (!lf && isIncompleteJsonObject(raw)) { trimAt = lineStart; break }
                    throw IOException("CORRUPT_RECORD at line ${count + 1}", e)
                }
                guard.accept(record)
                count++
                counts.merge(record.event.data.type, 1L, Long::plus)
                if (!lf) break // Valid complete final row without LF is retained.
            }
        }
        // Only reached after validating every preceding row. Never truncate interior corruption.
        trimAt?.let { RandomAccessFile(file, "rw").use { output -> output.setLength(it); output.fd.sync() } }
        return metadata.copy(completionState = CompletionState.INCOMPLETE,
            endState = RecordingEndState.INTERRUPTED, recoveryState = RecoveryState.RECOVERED,
            endedUtcMs = null, clock = metadata.clock.copy(endedNs = guard.lastReceived),
            recordCount = count, channelCounts = counts.map { ChannelCount(it.key, it.value) }) to
            (trimAt?.let { offset - it } ?: 0L)
    }
}

/** Conservative syntactic proof: strict parser reaches EOF inside an object. Completed invalid
 * objects, bad UTF-8, duplicate keys and syntax errors are never treated as truncation.
 * Some ambiguous fragments are deliberately unrecoverable rather than guessed/repaired.
 */
internal fun isIncompleteJsonObject(raw: ByteArray): Boolean {
    val text = try { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString() }
    catch (_: Exception) { return false }
    if (!text.trimStart().startsWith("{")) return false
    return try { JsonPrefix(text).value(0); false }
    catch (_: EOFException) { true }
    catch (_: Exception) { false }
}

/** A small JSON *prefix* recognizer, not a replacement for the canonical record codec.
 * EOF is recoverable only while a required grammar token is incomplete. Any syntax error
 * encountered before EOF is rejected, including trailing commas and invalid escapes.
 */
private class JsonPrefix(private val text: String) {
    private var i = 0
    private fun ws() { while (i < text.length && text[i] in " \t\r\n") i++ }
    private fun peek(): Char { ws(); if (i == text.length) throw EOFException(); return text[i] }
    private fun take(c: Char) { require(peek() == c); i++ }
    private fun string(): String {
        ws(); val start = i; take('"')
        while (true) {
            if (i == text.length) throw EOFException()
            val c = text[i++]
            if (c == '"') return JsonParser.parseString(text.substring(start, i)).asString
            require(c >= ' ')
            if (c == '\\') {
                if (i == text.length) throw EOFException()
                when (text[i++]) {
                    '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> Unit
                    'u' -> repeat(4) {
                        if (i == text.length) throw EOFException()
                        require(text[i++].digitToIntOrNull(16) != null)
                    }
                    else -> error("INVALID_ESCAPE")
                }
            }
        }
    }
    fun value(depth: Int) {
        require(depth <= 32)
        when (peek()) {
            '{' -> {
                i++; val keys = hashSetOf<String>()
                if (peek() == '}') { i++; return }
                while (true) {
                    require(keys.add(string()))
                    take(':'); value(depth + 1)
                    when (peek()) {
                        '}' -> { i++; return }
                        ',' -> { i++; require(peek() != '}') }
                        else -> error("OBJECT_SYNTAX")
                    }
                }
            }
            '[' -> {
                i++
                if (peek() == ']') { i++; return }
                while (true) {
                    value(depth + 1)
                    when (peek()) {
                        ']' -> { i++; return }
                        ',' -> { i++; require(peek() != ']') }
                        else -> error("ARRAY_SYNTAX")
                    }
                }
            }
            '"' -> { string() }
            't', 'f', 'n' -> {
                val word = when (text[i]) { 't' -> "true"; 'f' -> "false"; else -> "null" }
                word.forEach { c -> if (i == text.length) throw EOFException(); require(text[i++] == c) }
            }
            '-', in '0'..'9' -> {
                if (text[i] == '-') { i++; if (i == text.length) throw EOFException() }
                require(text[i] in '0'..'9')
                if (text[i++] != '0') while (i < text.length && text[i] in '0'..'9') i++
                if (i < text.length && text[i] == '.') { i++; digits() }
                if (i < text.length && text[i] in "eE") {
                    i++; if (i < text.length && text[i] in "+-") i++
                    digits()
                }
            }
            else -> error("VALUE_SYNTAX")
        }
    }
    private fun digits() {
        if (i == text.length) throw EOFException()
        require(text[i] in '0'..'9')
        while (i < text.length && text[i] in '0'..'9') i++
    }
}
