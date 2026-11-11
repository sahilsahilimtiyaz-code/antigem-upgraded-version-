package com.example.gemini.data.agent.claude

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.system.Os
import android.util.Log
import com.example.gemini.data.local.LocalEnvironmentManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * Robust importer for Claude Code agents.
 *
 * Supports importing Claude Code from:
 * - Gzipped tarballs (.tar.gz, .tgz) downloaded from GitHub releases or official sources
 * - Uncompressed tar archives (.tar)
 * - Zip archives (.zip)
 * - Raw standalone ELF binaries (e.g. 'claude', 'claude-linux-arm64')
 *
 * Automatically inspects the archive contents, finds the executable, detects version,
 * verifies POSIX permissions (0755), installs it to ~/.local/share/claude/versions/<version>,
 * and ensures the Termux wrapper script routes to it properly through the bundled glibc loader.
 */
object ClaudeImporter {
    private const val TAG = "ClaudeImporter"
    private val VERSION_REGEX = Regex("^[0-9]+\\.[0-9]+\\.[0-9]+$")
    private val FILENAME_VERSION_REGEX = Regex("(?:v|version|claude[-_])?([0-9]+\\.[0-9]+\\.[0-9]+)")

    data class ImportResult(
        val version: String,
        val targetPath: String,
        val binarySize: Long
    )

    /**
     * Imports a Claude Code archive or binary from an Android content URI.
     */
    suspend fun importFromUri(
        context: Context,
        uri: Uri,
        onProgress: (String) -> Unit = {}
    ): Result<ImportResult> = withContext(Dispatchers.IO) {
        runCatching {
            val displayName = queryDisplayName(context, uri) ?: "claude_archive"
            onProgress("Reading selected file: $displayName…")

            val cacheDir = context.cacheDir
            val tempFile = File.createTempFile("claude_import_", "_pkg", cacheDir)
            try {
                // 1. Copy URI stream to temporary local cache file
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var bytesRead: Int
                        var totalRead = 0L
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            totalRead += bytesRead
                            if (totalRead % (512 * 1024) == 0L) {
                                onProgress("Cached ${totalRead / (1024 * 1024)} MB…")
                            }
                        }
                    }
                } ?: throw IllegalArgumentException("Could not open input stream from URI: $uri")

                if (tempFile.length() == 0L) {
                    throw IllegalStateException("The selected file is empty (0 bytes).")
                }

                onProgress("Analyzing package format (${tempFile.length() / (1024 * 1024)} MB)…")

                // 2. Detect format
                val candidateBinary: File
                var detectedVersion: String? = extractVersionFromText(displayName)
                val extractDir = File(cacheDir, "claude_extract_${System.currentTimeMillis()}").apply { mkdirs() }

                try {
                    val magic = readMagicBytes(tempFile, 4)
                    when {
                        // Raw ELF binary: 0x7F 'E' 'L' 'F'
                        isElfMagic(magic) -> {
                            onProgress("Detected raw ELF binary. Preparing installation…")
                            candidateBinary = tempFile
                        }
                        // GZIP: 0x1F 0x8B (.tar.gz, .tgz)
                        magic.size >= 2 && magic[0] == 0x1F.toByte() && magic[1] == 0x8B.toByte() -> {
                            onProgress("Extracting GZIP archive (.tar.gz)…")
                            val versionFound = extractTarGz(tempFile, extractDir, onProgress)
                            if (detectedVersion == null && versionFound != null) {
                                detectedVersion = versionFound
                            }
                            candidateBinary = findClaudeBinary(extractDir, onProgress)
                        }
                        // ZIP: 0x50 0x4B 0x03 0x04
                        magic.size >= 4 && magic[0] == 0x50.toByte() && magic[1] == 0x4B.toByte() &&
                                magic[2] == 0x03.toByte() && magic[3] == 0x04.toByte() -> {
                            onProgress("Extracting ZIP archive (.zip)…")
                            val versionFound = extractZip(tempFile, extractDir, onProgress)
                            if (detectedVersion == null && versionFound != null) {
                                detectedVersion = versionFound
                            }
                            candidateBinary = findClaudeBinary(extractDir, onProgress)
                        }
                        // Check if it's an uncompressed tarball
                        isTarArchive(tempFile) -> {
                            onProgress("Extracting TAR archive…")
                            val versionFound = extractTar(tempFile, extractDir, onProgress)
                            if (detectedVersion == null && versionFound != null) {
                                detectedVersion = versionFound
                            }
                            candidateBinary = findClaudeBinary(extractDir, onProgress)
                        }
                        else -> {
                            // If extension looks like binary or unknown, check if larger than 10MB (likely executable)
                            if (tempFile.length() > 5 * 1024 * 1024) {
                                onProgress("Treating file as executable binary…")
                                candidateBinary = tempFile
                            } else {
                                throw IllegalArgumentException("Unsupported file type: not a recognized .tar.gz, .zip, .tar, or ELF executable.")
                            }
                        }
                    }

                    // 3. Determine and sanitize version string (must match ^[0-9]+\.[0-9]+\.[0-9]+$)
                    val finalVersion = sanitizeOrGenerateVersion(context, detectedVersion)
                    onProgress("Configuring Claude Code version $finalVersion…")

                    // 4. Install into ~/.local/share/claude/versions/<version>
                    val homeDir = LocalEnvironmentManager.getHomeDir(context)
                    val versionsDir = File(homeDir, ".local/share/claude/versions").apply { mkdirs() }
                    val targetFile = File(versionsDir, finalVersion)
                    val tmpTarget = File(versionsDir, ".$finalVersion.import_tmp")

                    if (tmpTarget.exists()) tmpTarget.delete()

                    FileInputStream(candidateBinary).use { input ->
                        FileOutputStream(tmpTarget).use { output ->
                            input.copyTo(output)
                        }
                    }

                    // Set executable & readable permissions
                    tmpTarget.setReadable(true, false)
                    tmpTarget.setExecutable(true, false)
                    try {
                        Os.chmod(tmpTarget.absolutePath, 493 /* 0755 */)
                    } catch (e: Throwable) {
                        Log.w(TAG, "chmod 0755 failed: ${e.message}")
                    }

                    if (targetFile.exists()) targetFile.delete()
                    if (!tmpTarget.renameTo(targetFile)) {
                        targetFile.delete()
                        if (!tmpTarget.renameTo(targetFile)) {
                            // Fallback direct copy
                            FileInputStream(tmpTarget).use { input ->
                                FileOutputStream(targetFile).use { output ->
                                    input.copyTo(output)
                                }
                            }
                            tmpTarget.delete()
                        }
                    }
                    targetFile.setExecutable(true, false)
                    targetFile.setReadable(true, false)
                    try {
                        Os.chmod(targetFile.absolutePath, 493 /* 0755 */)
                    } catch (_: Throwable) {}

                    // 5. Ensure the Termux wrapper script is properly written and linked
                    LocalEnvironmentManager.ensureClaudeWrapper(context)

                    // 6. Prune old versions to keep phone storage clean
                    pruneVersions(versionsDir, keep = 2)

                    onProgress("Import complete! Claude Code $finalVersion is installed and ready.")
                    Log.i(TAG, "Successfully imported Claude Code $finalVersion to ${targetFile.absolutePath}")

                    ImportResult(
                        version = finalVersion,
                        targetPath = targetFile.absolutePath,
                        binarySize = targetFile.length()
                    )
                } finally {
                    extractDir.deleteRecursively()
                }
            } finally {
                tempFile.delete()
            }
        }
    }

    private fun readMagicBytes(file: File, count: Int): ByteArray {
        val bytes = ByteArray(count)
        FileInputStream(file).use { it.read(bytes) }
        return bytes
    }

    private fun isElfMagic(bytes: ByteArray): Boolean =
        bytes.size >= 4 && bytes[0] == 0x7F.toByte() && bytes[1] == 'E'.code.toByte() &&
                bytes[2] == 'L'.code.toByte() && bytes[3] == 'F'.code.toByte()

    private fun isTarArchive(file: File): Boolean {
        if (file.length() < 512) return false
        val buffer = ByteArray(512)
        FileInputStream(file).use { it.read(buffer) }
        val magic = String(buffer, 257, 5, Charsets.US_ASCII)
        return magic == "ustar"
    }

    private fun extractTarGz(tarGzFile: File, destDir: File, onProgress: (String) -> Unit): String? {
        var versionFound: String? = null
        FileInputStream(tarGzFile).use { fileIn ->
            BufferedInputStream(fileIn).use { bufIn ->
                GzipCompressorInputStream(bufIn).use { gzIn ->
                    TarArchiveInputStream(gzIn).use { tarIn ->
                        var entry: TarArchiveEntry?
                        var count = 0
                        while (tarIn.nextTarEntry.also { entry = it } != null) {
                            val cur = entry ?: break
                            val outputFile = File(destDir, cur.name)
                            validatePathWithinDest(destDir, outputFile)
                            if (cur.isDirectory) {
                                outputFile.mkdirs()
                            } else {
                                outputFile.parentFile?.mkdirs()
                                FileOutputStream(outputFile).use { out ->
                                    tarIn.copyTo(out)
                                }
                                if (cur.mode and 0b001_000_000 != 0 || cur.name.endsWith("claude") || cur.name.endsWith("claude.exe")) {
                                    outputFile.setExecutable(true, false)
                                }
                                if (outputFile.name == "package.json" && versionFound == null) {
                                    versionFound = parseVersionFromPackageJson(outputFile)
                                }
                            }
                            count++
                            if (count % 100 == 0) onProgress("Unpacked $count files…")
                        }
                    }
                }
            }
        }
        return versionFound
    }

    private fun extractTar(tarFile: File, destDir: File, onProgress: (String) -> Unit): String? {
        var versionFound: String? = null
        FileInputStream(tarFile).use { fileIn ->
            BufferedInputStream(fileIn).use { bufIn ->
                TarArchiveInputStream(bufIn).use { tarIn ->
                    var entry: TarArchiveEntry?
                    var count = 0
                    while (tarIn.nextTarEntry.also { entry = it } != null) {
                        val cur = entry ?: break
                        val outputFile = File(destDir, cur.name)
                        validatePathWithinDest(destDir, outputFile)
                        if (cur.isDirectory) {
                            outputFile.mkdirs()
                        } else {
                            outputFile.parentFile?.mkdirs()
                            FileOutputStream(outputFile).use { out ->
                                tarIn.copyTo(out)
                            }
                            if (outputFile.name == "package.json" && versionFound == null) {
                                versionFound = parseVersionFromPackageJson(outputFile)
                            }
                        }
                        count++
                        if (count % 100 == 0) onProgress("Unpacked $count files…")
                    }
                }
            }
        }
        return versionFound
    }

    private fun extractZip(zipFile: File, destDir: File, onProgress: (String) -> Unit): String? {
        var versionFound: String? = null
        FileInputStream(zipFile).use { fileIn ->
            BufferedInputStream(fileIn).use { bufIn ->
                ZipInputStream(bufIn).use { zipIn ->
                    var entry: ZipEntry?
                    var count = 0
                    while (zipIn.nextEntry.also { entry = it } != null) {
                        val cur = entry ?: break
                        val outputFile = File(destDir, cur.name)
                        validatePathWithinDest(destDir, outputFile)
                        if (cur.isDirectory) {
                            outputFile.mkdirs()
                        } else {
                            outputFile.parentFile?.mkdirs()
                            FileOutputStream(outputFile).use { out ->
                                zipIn.copyTo(out)
                            }
                            if (outputFile.name == "package.json" && versionFound == null) {
                                versionFound = parseVersionFromPackageJson(outputFile)
                            }
                        }
                        zipIn.closeEntry()
                        count++
                        if (count % 100 == 0) onProgress("Unpacked $count files…")
                    }
                }
            }
        }
        return versionFound
    }

    private fun validatePathWithinDest(destDir: File, file: File) {
        val destCanonical = destDir.canonicalPath
        val fileCanonical = file.canonicalPath
        if (!fileCanonical.startsWith(destCanonical)) {
            throw SecurityException("Invalid archive path outside target directory: ${file.name}")
        }
    }

    private fun findClaudeBinary(searchDir: File, onProgress: (String) -> Unit): File {
        onProgress("Searching for Claude binary in package…")
        val allFiles = searchDir.walkTopDown().filter { it.isFile && it.length() > 0 }.toList()

        // 1. Look for executable with ELF header and named claude
        val elfNamedClaude = allFiles.firstOrNull { file ->
            (file.name == "claude" || file.name.startsWith("claude-") || file.name == "claude.exe") &&
                    file.length() > 1024 * 1024 && checkIsElf(file)
        }
        if (elfNamedClaude != null) return elfNamedClaude

        // 2. Look for ANY file with ELF magic bytes > 10MB (standalone bundle)
        val largeElf = allFiles.filter { it.length() > 5 * 1024 * 1024 && checkIsElf(it) }
            .maxByOrNull { it.length() }
        if (largeElf != null) return largeElf

        // 3. Look for file named 'claude' or 'claude-linux-arm64' even if not ELF (e.g. script or node wrapper)
        val namedClaude = allFiles.firstOrNull { it.name == "claude" || it.name == "claude-linux-arm64" }
        if (namedClaude != null) return namedClaude

        // 4. Look for bin/claude.js or package/bin/claude.js
        val nodeClaude = allFiles.firstOrNull { it.name == "claude.js" }
        if (nodeClaude != null) return nodeClaude

        // 5. Fallback: largest non-archive file in the directory
        val largest = allFiles.maxByOrNull { it.length() }
        if (largest != null && largest.length() > 100 * 1024) {
            return largest
        }

        throw IllegalStateException("Could not find a valid Claude Code binary or executable inside the imported archive.")
    }

    private fun checkIsElf(file: File): Boolean = try {
        if (file.length() < 4) false
        else isElfMagic(readMagicBytes(file, 4))
    } catch (_: Throwable) {
        false
    }

    private fun parseVersionFromPackageJson(packageJson: File): String? = try {
        val content = packageJson.readText()
        val match = Regex("\"version\"\\s*:\\s*\"([0-9]+\\.[0-9]+\\.[0-9]+[^\"]*)\"").find(content)
        val ver = match?.groupValues?.getOrNull(1)
        extractVersionFromText(ver)
    } catch (_: Throwable) {
        null
    }

    private fun extractVersionFromText(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val match = FILENAME_VERSION_REGEX.find(text)
        return match?.groupValues?.getOrNull(1)
    }

    private fun sanitizeOrGenerateVersion(context: Context, detected: String?): String {
        if (detected != null && VERSION_REGEX.matches(detected)) {
            return detected
        }
        // Check existing versions in ~/.local/share/claude/versions
        val homeDir = LocalEnvironmentManager.getHomeDir(context)
        val versionsDir = File(homeDir, ".local/share/claude/versions")
        val existing = if (versionsDir.isDirectory) {
            versionsDir.listFiles()?.filter { it.isFile && VERSION_REGEX.matches(it.name) }?.map { it.name } ?: emptyList()
        } else emptyList()

        if (existing.isEmpty()) {
            return "1.0.0"
        }
        // Increment patch or return next semver
        val sorted = existing.sortedWith(Comparator { a, b -> compareVersions(a, b) })
        val latest = sorted.last()
        val parts = latest.split(".").mapNotNull { it.toIntOrNull() }
        return if (parts.size == 3) {
            "${parts[0]}.${parts[1]}.${parts[2] + 1}"
        } else {
            "2.0.0"
        }
    }

    private fun compareVersions(a: String, b: String): Int {
        val pa = a.split(".").mapNotNull { it.toIntOrNull() }
        val pb = b.split(".").mapNotNull { it.toIntOrNull() }
        for (i in 0 until minOf(pa.size, pb.size)) {
            if (pa[i] != pb[i]) return pa[i].compareTo(pb[i])
        }
        return pa.size.compareTo(pb.size)
    }

    private fun pruneVersions(versionsDir: File, keep: Int) {
        val files = versionsDir.listFiles()?.filter { it.isFile && VERSION_REGEX.matches(it.name) } ?: return
        if (files.size <= keep) return
        val sorted = files.sortedWith(Comparator { a, b -> compareVersions(a.name, b.name) })
        for (old in sorted.take(files.size - keep)) {
            old.delete()
        }
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        if (uri.scheme == "file") return uri.lastPathSegment
        return context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx != -1) cursor.getString(idx) else null
            } else null
        }
    }
}
