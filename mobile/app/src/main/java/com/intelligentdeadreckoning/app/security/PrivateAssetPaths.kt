package com.intelligentdeadreckoning.app.security

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

/** Produce a short diagnostic token without forwarding paths, payloads, or provider text. */
internal object SafeSecurityMessages {
    private val codeToken = Regex("^(?:Replay line [0-9]+: )?([A-Z][A-Z0-9_]{1,63})(?: at [^\\r\\n]+)?(?: \\(line [0-9]+\\))?$")

    fun code(error: Throwable, fallback: String): String {
        val candidate = error.message?.let { codeToken.matchEntire(it)?.groupValues?.get(1) }
        return candidate ?: when (error) {
            is SecurityException -> "ACCESS_DENIED"
            is java.io.IOException -> "IO_ERROR"
            else -> fallback
        }
    }
}

/** Resolve only bounded relative paths beneath a trusted app-private directory. */
internal object PrivateAssetPaths {
    fun directory(baseDirectory: File, relative: String): File {
        val base = baseDirectory.toPath().toAbsolutePath().normalize()
        var ancestor: Path? = base
        while (ancestor != null) {
            require(!Files.isSymbolicLink(ancestor)) { "UNSAFE_PRIVATE_ROOT" }
            ancestor = ancestor.parent
        }
        require(Files.isDirectory(base, NOFOLLOW_LINKS)) { "UNSAFE_PRIVATE_ROOT" }
        var current = base
        for (segment in segments(relative)) {
            val next = current.resolve(segment).normalize()
            require(next.parent == current && !Files.isSymbolicLink(next)) { "UNSAFE_PRIVATE_PATH" }
            if (Files.exists(next, NOFOLLOW_LINKS)) {
                require(Files.isDirectory(next, NOFOLLOW_LINKS)) { "UNSAFE_PRIVATE_PATH" }
            } else {
                Files.createDirectory(next)
            }
            current = next
        }
        return current.toFile()
    }

    fun file(baseDirectory: File, relative: String): File {
        val parts = segments(relative)
        require(parts.isNotEmpty()) { "UNSAFE_PRIVATE_PATH" }
        val parent = if (parts.size == 1) baseDirectory.toPath().toAbsolutePath().normalize()
        else directory(baseDirectory, parts.dropLast(1).joinToString("/")).toPath()
        var ancestor: Path? = parent
        while (ancestor != null) {
            require(!Files.isSymbolicLink(ancestor)) { "UNSAFE_PRIVATE_ROOT" }
            ancestor = ancestor.parent
        }
        require(Files.isDirectory(parent, NOFOLLOW_LINKS)) { "UNSAFE_PRIVATE_ROOT" }
        val target = parent.resolve(parts.last()).normalize()
        require(target.parent == parent && !Files.isSymbolicLink(target)) { "UNSAFE_PRIVATE_PATH" }
        return target.toFile()
    }

    private fun segments(relative: String): List<String> {
        require(relative.isNotBlank() && !relative.startsWith('/') &&
            !relative.startsWith('\\') && '\\' !in relative && ':' !in relative &&
            relative.none { it.code < 32 || it.code == 127 }) { "UNSAFE_PRIVATE_PATH" }
        val parts = relative.split('/')
        require(parts.all { it.isNotBlank() && it != "." && it != ".." }) { "UNSAFE_PRIVATE_PATH" }
        return parts
    }
}
