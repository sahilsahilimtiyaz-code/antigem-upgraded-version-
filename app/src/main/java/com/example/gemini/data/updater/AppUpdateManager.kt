package com.example.gemini.data.updater

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import com.example.gemini.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

private const val TAG = "AppUpdateManager"
private const val DEFAULT_VERSION_URL = "https://raw.githubusercontent.com/santoshkurmi/antigem/master/version.json"

data class AppUpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val currentVersionCode: Int,
    val currentVersionName: String,
    val packageName: String,
    val downloadUrl: String,
    val changelog: List<String>,
    val isCritical: Boolean = false
)

sealed class DownloadState {
    object Idle : DownloadState()
    data class Downloading(val progress: Float, val downloadedBytes: Long, val totalBytes: Long) : DownloadState()
    data class Completed(val file: File) : DownloadState()
    data class Error(val message: String) : DownloadState()
}

class AppUpdateManager(private val context: Context) {

    private val httpClient = OkHttpClient.Builder()
        .connectionPool(okhttp3.ConnectionPool(16, 5, TimeUnit.MINUTES))
        .protocols(listOf(okhttp3.Protocol.HTTP_2, okhttp3.Protocol.HTTP_1_1))
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()


    /**
     * Checks if a new version is available by fetching centralized version.json from GitHub.
     */
    suspend fun checkForUpdates(
        versionJsonUrl: String = DEFAULT_VERSION_URL,
        forceCheck: Boolean = false
    ): Result<AppUpdateInfo?> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(versionJsonUrl)
                .header("User-Agent", "antiGem-Updater/${BuildConfig.VERSION_NAME}")
                .header("Cache-Control", "no-cache")
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                val errorMsg = when (response.code) {
                    404 -> "version.json not found on GitHub repository (HTTP 404). Please ensure version.json is pushed to the repository."
                    403 -> "GitHub rate limit exceeded or access forbidden (HTTP 403)."
                    else -> "Failed to fetch version info: HTTP ${response.code} (${response.message})"
                }
                return@withContext Result.failure(Exception(errorMsg))
            }

            val rawJson = response.body?.string() ?: return@withContext Result.failure(Exception("Empty version response from server"))

            val json = JSONObject(rawJson)
            val remoteVersionCode = json.optInt("version_code", 0)
            val remoteVersionName = json.optString("version_name", "1.0.0")
            val minSupportedCode = json.optInt("min_supported_version_code", 0)
            val changelog = when (val raw = json.opt("changelog")) {
                is org.json.JSONArray -> (0 until raw.length()).map { raw.optString(it) }
                is String -> listOf(raw)
                else -> listOf("General improvements and bug fixes.")
            }
            val isCritical = json.optBoolean("is_critical", false) || (BuildConfig.VERSION_CODE < minSupportedCode)

            // Auto-generate package-specific download URL based on remote version name and flavor
            val currentPkg = context.packageName
            val isSharedUser = try {
                val pkgInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    context.packageManager.getPackageInfo(context.packageName, 0)
                }
                !pkgInfo.sharedUserId.isNullOrEmpty()
            } catch (_: Exception) {
                BuildConfig.IS_SHARED_USER_ID
            }

            val flavorType = when {
                currentPkg == "com.termux" && isSharedUser -> "termux-shared"
                currentPkg == "com.termux" -> "termux"
                else -> "standard"
            }
            val primaryBuildType = if (BuildConfig.DEBUG) "debug" else "release"
            val candidateUrls = listOf(
                "https://github.com/santoshkurmi/antigem/releases/download/v$remoteVersionName/antiGem-$flavorType-v$remoteVersionName-$primaryBuildType.apk",
                "https://github.com/santoshkurmi/antigem/releases/download/v$remoteVersionName/antiGem-$flavorType-v$remoteVersionName-release.apk",
                "https://github.com/santoshkurmi/antigem/releases/download/v$remoteVersionName/antiGem-$flavorType-v$remoteVersionName.apk",
                "https://github.com/santoshkurmi/antigem/releases/download/v$remoteVersionName/antiGem-v$remoteVersionName.apk"
            ).distinct()

            var resolvedUrl: String? = null
            if (remoteVersionCode > BuildConfig.VERSION_CODE) {
                // Probe candidate URLs to find the published APK asset
                for (candidate in candidateUrls) {
                    val probeRequest = Request.Builder()
                        .url(candidate)
                        .head()
                        .header("User-Agent", "antiGem-Updater/${BuildConfig.VERSION_NAME}")
                        .build()

                    val isAvailable = try {
                        httpClient.newCall(probeRequest).execute().use { probeResponse ->
                            probeResponse.isSuccessful || probeResponse.isRedirect
                        }
                    } catch (_: Exception) {
                        false
                    }

                    if (isAvailable) {
                        resolvedUrl = candidate
                        break
                    }
                }

                if (resolvedUrl == null) {
                    // Release is not yet fully published or APK asset is still building on CI
                    return@withContext Result.success(null)
                }

                Result.success(
                    AppUpdateInfo(
                        versionCode = remoteVersionCode,
                        versionName = remoteVersionName,
                        currentVersionCode = BuildConfig.VERSION_CODE,
                        currentVersionName = BuildConfig.VERSION_NAME,
                        packageName = currentPkg,
                        downloadUrl = resolvedUrl,
                        changelog = changelog,
                        isCritical = isCritical
                    )
                )
            } else {
                Result.success(null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check for updates: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Downloads the APK file to cache directory with live progress updates.
     */
    suspend fun downloadApk(
        downloadUrl: String,
        onProgress: (Float, Long, Long) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(downloadUrl)
                .header("User-Agent", "antiGem-Updater/${BuildConfig.VERSION_NAME}")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("Download failed: HTTP ${response.code}"))
                }

                val body = response.body ?: return@withContext Result.failure(Exception("Empty download body"))
                val totalBytes = body.contentLength()

                val targetDir = context.externalCacheDir ?: context.cacheDir
                val targetFile = File(targetDir, "antiGem_update_${System.currentTimeMillis()}.apk")

                body.byteStream().use { input ->
                    java.io.BufferedOutputStream(targetFile.outputStream(), 256 * 1024).use { output ->
                        val buffer = ByteArray(256 * 1024)
                        var bytesRead: Int
                        var downloadedBytes = 0L
                        var lastProgressReportTime = 0L

                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            downloadedBytes += bytesRead

                            val now = System.currentTimeMillis()
                            if (now - lastProgressReportTime >= 100L || (totalBytes > 0 && downloadedBytes >= totalBytes)) {
                                lastProgressReportTime = now
                                val progress = if (totalBytes > 0) {
                                    (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                                } else {
                                    0f
                                }
                                withContext(Dispatchers.Main) {
                                    onProgress(progress, downloadedBytes, totalBytes)
                                }
                            }
                        }
                        output.flush()
                    }
                }

                Result.success(targetFile)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Download APK error: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Checks if the downloaded APK signing certificate matches the currently running application.
     */
    fun verifyApkSignaturesMatch(apkFile: File): Boolean {
        return try {
            val pm = context.packageManager
            val installedSignatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val pkgInfo = pm.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES)
                pkgInfo.signingInfo?.apkContentsSigners?.map { it.toByteArray().contentHashCode() }
            } else {
                @Suppress("DEPRECATION")
                val pkgInfo = pm.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_SIGNATURES)
                pkgInfo.signatures?.map { it.toByteArray().contentHashCode() }
            }

            val archiveSignatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val archiveInfo = pm.getPackageArchiveInfo(apkFile.absolutePath, android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES)
                archiveInfo?.signingInfo?.apkContentsSigners?.map { it.toByteArray().contentHashCode() }
            } else {
                @Suppress("DEPRECATION")
                val archiveInfo = pm.getPackageArchiveInfo(apkFile.absolutePath, android.content.pm.PackageManager.GET_SIGNATURES)
                archiveInfo?.signatures?.map { it.toByteArray().contentHashCode() }
            }

            if (installedSignatures.isNullOrEmpty() || archiveSignatures.isNullOrEmpty()) {
                true
            } else {
                installedSignatures.any { archiveSignatures.contains(it) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Signature check error: ${e.message}")
            true
        }
    }

    /**
     * Launches the system Package Installer using FileProvider.
     */
    fun installApk(apkFile: File): Boolean {
        return try {
            if (!verifyApkSignaturesMatch(apkFile)) {
                Log.w(TAG, "Signing certificate mismatch: current app and update APK were signed with different keys.")
                android.widget.Toast.makeText(
                    context,
                    "Notice: Signing key differs (Debug vs Release). If install fails, please install the matching debug build.",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }

            val authority = "${context.packageName}.fileprovider"
            val apkUri = FileProvider.getUriForFile(context, authority, apkFile)

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            // Android 8.0+ Unknown sources permission check
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!context.packageManager.canRequestPackageInstalls()) {
                    val manageIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:${context.packageName}")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(manageIntent)
                    return false
                }
            }

            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch package installer: ${e.message}", e)
            false
        }
    }
}
