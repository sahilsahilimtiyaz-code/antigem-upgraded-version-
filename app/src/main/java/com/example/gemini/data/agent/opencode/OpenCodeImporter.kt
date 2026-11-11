package com.example.gemini.data.agent.opencode

import android.content.Context
import android.net.Uri
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
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * Robust importer for OpenCode agents.
 *
 * Supports importing OpenCode from:
 * - Gzipped npm/github tarballs (.tar.gz, .tgz, e.g. @opencode/cli-linux-arm64-*.tgz)
 * - Zip archives (.zip)
 * - Uncompressed tar archives (.tar)
 * - Raw standalone ELF binaries ('opencode')
 *
 * Automatically inspects the archive contents, finds the executable, verifies POSIX
 * permissions (0755), and installs it to ~/.opencode/bin/opencode.
 */
object OpenCodeImporter {
    private const val TAG = "OpenCodeImporter"

    data class ImportResult(
        val version: String,
        val targetPath: String,
        val binarySize: Long
    )

    /**
     * Imports an OpenCode archive or binary from an Android content URI.
     */
    suspend fun importFromUri(
        context: Context,
        uri: Uri,
        onProgress: (String) -> Unit = {}
    ): Result<ImportResult> = withContext(Dispatchers.IO) {
        runCatching {
            val displayName = queryDisplayName(context, uri) ?: "opencode_archive"
            onProgress("Reading selected file: $displayName…")

            val cacheDir = context.cacheDir
            val tempFile = File.createTempFile("opencode_import_", "_pkg", cacheDir)
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
                val extractDir = File(cacheDir, "opencode_extract_${System.currentTimeMillis()}").apply { mkdirs() }

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
                            onProgress("Extracting GZIP archive (.tgz / .tar.gz)…")
                            val versionFound = extractTarGz(tempFile, extractDir, onProgress)
                            if (detectedVersion == null && versionFound != null) {
                                detectedVersion = versionFound
                            }
                            candidateBinary = findOpenCodeBinary(extractDir, onProgress)
                        }
                        // ZIP: 0x50 0x4B 0x03 0x04
                        magic.size >= 4 && magic[0] == 0x50.toByte() && magic[1] == 0x4B.toByte() &&
                                magic[2] == 0x03.toByte() && magic[3] == 0x04.toByte() -> {
                            onProgress("Extracting ZIP archive (.zip)…")
                            val versionFound = extractZip(tempFile, extractDir, onProgress)
                            if (detectedVersion == null && versionFound != null) {
                                detectedVersion = versionFound
                            }
                            candidateBinary = findOpenCodeBinary(extractDir, onProgress)
                        }
                        isTarArchive(tempFile) -> {
                            onProgress("Extracting TAR archive…")
                            val versionFound = extractTar(tempFile, extractDir, onProgress)
                            if (detectedVersion == null && versionFound != null) {
                                detectedVersion = versionFound
                            }
                            candidateBinary = findOpenCodeBinary(extractDir, onProgress)
                        }
                        else -> {
                            if (tempFile.length() > 5 * 1024 * 1024) {
                                onProgress("Treating file as executable binary…")
                                candidateBinary = tempFile
                            } else {
                                throw IllegalArgumentException("Unsupported file type: not a recognized .tgz, .zip, .tar, or ELF executable.")
                            }
                        }
                    }

                    val finalVersion = detectedVersion ?: "2.2.7"
                    onProgress("Installing OpenCode binary…")

                    // 3. Install into ~/.opencode/bin/opencode
                    val homeDir = LocalEnvironmentManager.getHomeDir(context)
                    val binDir = File(homeDir, ".opencode/bin").apply { mkdirs() }
                    val targetFile = File(binDir, "opencode")
                    val tmpTarget = File(binDir, ".opencode.import_tmp")

                    if (tmpTarget.exists()) tmpTarget.delete()

                    FileInputStream(candidateBinary).use { input ->
                        FileOutputStream(tmpTarget).use { output ->
                            input.copyTo(output)
                        }
                    }

                    tmpTarget.setReadable(true, false)
                    tmpTarget.setExecutable(true, false)
                    try {
                        Os.chmod(tmpTarget.absolutePath, 493 /* 0755 */)
                    } catch (_: Throwable) {}

                    if (targetFile.exists()) targetFile.delete()
                    if (!tmpTarget.renameTo(targetFile)) {
                        targetFile.delete()
                        if (!tmpTarget.renameTo(targetFile)) {
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

                    onProgress("Import complete! OpenCode is installed and ready at ~/.opencode/bin/opencode.")
                    Log.i(TAG, "Successfully imported OpenCode to ${targetFile.absolutePath}")

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

    private fun findOpenCodeBinary(searchDir: File, onProgress: (String) -> Unit): File {
        onProgress("Searching for OpenCode binary in package…")
        val allFiles = searchDir.walkTopDown().filter { it.isFile && it.length() > 0 }.toList()

        // 1. Look for package/bin/opencode or opencode
        val namedOpenCode = allFiles.firstOrNull { file ->
            (file.name == "opencode" || file.name.startsWith("opencode-") || file.name == "opencode.exe") &&
                    file.length() > 1024 * 1024 && checkIsElf(file)
        }
        if (namedOpenCode != null) return namedOpenCode

        // 2. Any large ELF executable
        val largeElf = allFiles.filter { it.length() > 5 * 1024 * 1024 && checkIsElf(it) }
            .maxByOrNull { it.length() }
        if (largeElf != null) return largeElf

        // 3. Fallback to file named opencode
        val anyNamed = allFiles.firstOrNull { it.name == "opencode" }
        if (anyNamed != null) return anyNamed

        // 4. Fallback largest file
        val largest = allFiles.maxByOrNull { it.length() }
        if (largest != null && largest.length() > 100 * 1024) {
            return largest
        }

        throw IllegalStateException("Could not find a valid OpenCode binary inside the imported package.")
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
        match?.groupValues?.getOrNull(1)
    } catch (_: Throwable) {
        null
    }

    private fun extractVersionFromText(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val match = Regex("(?:v|version|opencode[-_])?([0-9]+\\.[0-9]+\\.[0-9]+)").find(text)
        return match?.groupValues?.getOrNull(1)
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
