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
    private fun directory(id: String): File {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "UNSAFE_RECORDING_ID" }
        return File(root, id)
    }
    override fun create(metadata: RecordingMetadata): RecordingOutput = synchronized(processLock) {
        val raw = RecordingCodec.encodeMetadata(metadata)
        Files.createDirectories(root.toPath())
        val dir = directory(metadata.recordingId)
        Files.createDirectory(dir.toPath()) // Never overwrite an existing recording.
        activeDirectories.add(dir.absolutePath)
        try {
            atomicMetadata(dir, raw)
            val file = File(dir, "measurements.jsonl")
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
        val temporary = File(dir, "metadata.json.tmp")
        FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
        // If atomic rename is unsupported/fails, propagate failure; keep the previous metadata.
        Files.move(temporary.toPath(), File(dir, "metadata.json").toPath(),
            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    override fun recover(): RecoverySummary = synchronized(processLock) {
        if (!root.exists()) return@synchronized RecoverySummary()
        var recovered = 0
        var failed = 0
        var trimmedBytes = 0L
        var lastError: String? = null
        Files.newDirectoryStream(root.toPath()).use { paths ->
            for (path in paths) {
                if (!Files.isDirectory(path)) continue
                if (path.toFile().absolutePath in activeDirectories) continue
                try {
                    val dir = path.toFile()
                    val metaFile = File(dir, "metadata.json")
                    require(metaFile.length() in 1..262_144) { "MISSING_OR_OVERSIZED_METADATA" }
                    val metadata = RecordingCodec.decodeMetadata(metaFile.readBytes())
                    require(directory(metadata.recordingId).canonicalFile == dir.canonicalFile) { "DIRECTORY_ID_MISMATCH" }
                    if (metadata.completionState == CompletionState.OPEN || metadata.recoveryState == RecoveryState.REQUIRED) {
                        try {
                            val result = recoverSession(dir, metadata)
                            finalize(result.first)
                            trimmedBytes += result.second
                            recovered++
                        } catch (e: Exception) {
                            // No skip/repair of complete corruption. Preserve the measurement bytes.
                            finalize(metadata.copy(completionState = CompletionState.INCOMPLETE,
                                endState = RecordingEndState.INTERRUPTED, recoveryState = RecoveryState.UNRECOVERABLE,
                                recordCount = null, channelCounts = null))
                            throw e
                        }
                    } else if (metadata.recoveryState == RecoveryState.UNRECOVERABLE) {
                        failed++
                        lastError = "${dir.name}: previously marked unrecoverable"
                    }
                } catch (e: Exception) {
                    failed++
                    lastError = "${path.fileName}: ${e.javaClass.simpleName}: ${e.message}"
                }
            }
        }
        RecoverySummary(recovered, failed, "Recovered/incomplete: $recovered; trimmed trailing bytes: $trimmedBytes; unrecoverable: $failed." +
            (lastError?.let { " Last error: $it" } ?: ""))
    }

    private fun recoverSession(dir: File, metadata: RecordingMetadata): Pair<RecordingMetadata, Long> {
        val file = File(dir, "measurements.jsonl")
        require(file.isFile) { "MISSING_MEASUREMENTS" }
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
                    throw IOException("CORRUPT_RECORD at line ${count + 1}: ${e.message}", e)
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
