package io.github.sebrolens.vitalchronicle.android

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

data class AppUpdateInfo(
    val version: String,
    val apkUrl: String,
    val assetName: String,
    val sizeBytes: Long,
)

sealed interface AppUpdateState {
    data object Idle : AppUpdateState
    data object Checking : AppUpdateState
    data object UpToDate : AppUpdateState
    data class Downloading(val version: String) : AppUpdateState
    data class Ready(val info: AppUpdateInfo, val apkPath: String) : AppUpdateState
    data class Failed(val message: String) : AppUpdateState
}

/**
 * GitHub release updater for the independently distributed APK.
 *
 * Downloads are kept only in the application's private cache. Before an APK is
 * offered to Android's package installer, its package name, version and signing
 * certificate are checked against the currently installed application.
 */
class GitHubAppUpdater(
    context: Context,
    private val http: AndroidHttpClient,
) {
    private val appContext = context.applicationContext
    private val packageManager = appContext.packageManager
    private val updateDirectory: File
        get() = File(appContext.cacheDir, UPDATE_DIRECTORY)

    suspend fun findUpdate(currentVersion: String): AppUpdateInfo? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(LATEST_RELEASE_API)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "VitalChronicle-Android/$currentVersion")
            .build()

        http.execute(request).use { response ->
            if (!response.isSuccessful) {
                error("GitHub update check failed (HTTP ${response.code}).")
            }

            val payload = response.body?.string().orEmpty()
            if (payload.isBlank()) error("GitHub returned an empty release response.")
            val release = JSONObject(payload)
            val version = normalizeVersion(release.optString("tag_name"))
            if (version.isBlank() || compareVersions(version, currentVersion) <= 0) {
                return@withContext null
            }

            val assets = release.optJSONArray("assets")
                ?: error("GitHub release $version has no APK assets.")
            var selected: JSONObject? = null
            for (index in 0 until assets.length()) {
                val candidate = assets.optJSONObject(index) ?: continue
                val name = candidate.optString("name")
                if (!name.endsWith(".apk", ignoreCase = true)) continue
                if (selected == null) selected = candidate
                if (name == CANONICAL_APK_NAME) {
                    selected = candidate
                    break
                }
            }

            val asset = selected ?: error("GitHub release $version has no downloadable APK.")
            val url = asset.optString("browser_download_url")
            if (!url.startsWith("https://")) error("GitHub returned an invalid APK URL.")
            AppUpdateInfo(
                version = version,
                apkUrl = url,
                assetName = asset.optString("name", CANONICAL_APK_NAME),
                sizeBytes = asset.optLong("size", -1L),
            )
        }
    }

    suspend fun downloadAndVerify(info: AppUpdateInfo): File = withContext(Dispatchers.IO) {
        cleanupDownloadedApks()
        if (!updateDirectory.exists() && !updateDirectory.mkdirs()) {
            error("Unable to create private update storage.")
        }

        val partial = File(updateDirectory, "VitalChronicle-Android-${info.version}.apk.part")
        val target = File(updateDirectory, "VitalChronicle-Android-${info.version}.apk")
        try {
            val request = Request.Builder()
                .url(info.apkUrl)
                .header("Accept", "application/octet-stream")
                .header("User-Agent", "VitalChronicle-Android/${BuildConfig.VERSION_NAME}")
                .build()

            http.execute(request).use { response ->
                if (!response.isSuccessful) {
                    error("APK download failed (HTTP ${response.code}).")
                }
                val body = response.body ?: error("GitHub returned an empty APK.")
                val expectedBytes = body.contentLength()
                val copiedBytes = body.byteStream().use { input ->
                    partial.outputStream().buffered().use { output -> input.copyTo(output) }
                }
                if (copiedBytes <= 0L || (expectedBytes > 0L && copiedBytes != expectedBytes)) {
                    error("The downloaded APK is incomplete.")
                }
                if (info.sizeBytes > 0L && copiedBytes != info.sizeBytes) {
                    error("The downloaded APK size does not match the GitHub release.")
                }
            }

            verifyDownloadedApk(partial, info.version)
            if (target.exists() && !target.delete()) error("Unable to replace the cached update.")
            if (!partial.renameTo(target)) {
                partial.copyTo(target, overwrite = true)
                if (!partial.delete()) partial.deleteOnExit()
            }
            target
        } catch (failure: Throwable) {
            partial.delete()
            target.delete()
            throw failure
        }
    }

    fun cleanupDownloadedApks() {
        updateDirectory.listFiles()?.forEach { file ->
            if (file.isFile && (
                    file.extension.equals("apk", ignoreCase = true) ||
                        file.name.endsWith(".apk.part", ignoreCase = true)
                    )
            ) {
                file.delete()
            }
        }
    }

    fun canInstallPackages(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || packageManager.canRequestPackageInstalls()

    fun unknownSourcesIntent(): Intent =
        Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${appContext.packageName}"),
        )

    fun installationIntent(apk: File): Intent {
        if (!apk.isFile) error("The downloaded update is no longer available.")
        val uri = FileProvider.getUriForFile(
            appContext,
            "${appContext.packageName}.updates",
            apk,
        )
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_MIME_TYPE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private fun verifyDownloadedApk(apk: File, expectedVersion: String) {
        val archive = archivePackageInfo(apk)
            ?: error("Android could not read the downloaded APK.")
        if (archive.packageName != appContext.packageName) {
            error("The downloaded APK belongs to a different application.")
        }

        val archiveVersion = normalizeVersion(archive.versionName.orEmpty())
        if (archiveVersion.isBlank() || compareVersions(archiveVersion, expectedVersion) != 0) {
            error("The downloaded APK version does not match GitHub release $expectedVersion.")
        }
        if (compareVersions(archiveVersion, BuildConfig.VERSION_NAME) <= 0) {
            error("The downloaded APK is not newer than the installed version.")
        }

        val installed = installedPackageInfo()
        val installedSigners = signerDigests(installed)
        val archiveSigners = signerDigests(archive)
        if (installedSigners.isEmpty() || archiveSigners.isEmpty() || installedSigners != archiveSigners) {
            error("The downloaded APK signature does not match the installed application.")
        }
    }

    @Suppress("DEPRECATION")
    private fun archivePackageInfo(apk: File): PackageInfo? {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        return packageManager.getPackageArchiveInfo(apk.absolutePath, flags)
    }

    @Suppress("DEPRECATION")
    private fun installedPackageInfo(): PackageInfo {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        return packageManager.getPackageInfo(appContext.packageName, flags)
    }

    @Suppress("DEPRECATION")
    private fun signerDigests(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners
        } else {
            info.signatures
        }
        return signatures.orEmpty().map { signature ->
            MessageDigest.getInstance("SHA-256")
                .digest(signature.toByteArray())
                .joinToString(separator = "") { byte ->
                    (byte.toInt() and 0xff).toString(16).padStart(2, '0')
                }
        }.toSet()
    }

    companion object {
        private const val LATEST_RELEASE_API =
            "https://api.github.com/repos/SebRoLENS/VitalChronicle-android/releases/latest"
        private const val CANONICAL_APK_NAME = "VitalChronicle-Android.apk"
        private const val UPDATE_DIRECTORY = "updates"
        private const val APK_MIME_TYPE = "application/vnd.android.package-archive"

        internal fun normalizeVersion(value: String): String =
            value.trim().removePrefix("v").removePrefix("V")

        internal fun compareVersions(left: String, right: String): Int {
            fun components(value: String): List<Int> =
                normalizeVersion(value)
                    .substringBefore('-')
                    .split('.')
                    .map { it.toIntOrNull() ?: 0 }

            val leftParts = components(left)
            val rightParts = components(right)
            val length = maxOf(leftParts.size, rightParts.size)
            for (index in 0 until length) {
                val comparison =
                    (leftParts.getOrElse(index) { 0 }).compareTo(rightParts.getOrElse(index) { 0 })
                if (comparison != 0) return comparison
            }

            val leftPrerelease = normalizeVersion(left).contains('-')
            val rightPrerelease = normalizeVersion(right).contains('-')
            return when {
                leftPrerelease && !rightPrerelease -> -1
                !leftPrerelease && rightPrerelease -> 1
                else -> normalizeVersion(left).compareTo(normalizeVersion(right))
            }
        }
    }
}
