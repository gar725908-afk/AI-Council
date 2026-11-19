package ai.hybrid.commandcenter.nexus

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

sealed interface NexusInstallResult {
    data class InstallerOpened(val file: File) : NexusInstallResult
    data class PermissionSettingsOpened(val file: File) : NexusInstallResult
}

/**
 * Downloads a GitHub Release APK, verifies its published SHA-256, package ID and signing
 * certificate, then delegates installation to Android's package installer.
 */
class NexusApkInstaller(
    private val context: Context,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
) {
    suspend fun downloadAndLaunch(release: NexusRelease): NexusInstallResult = withContext(Dispatchers.IO) {
        val apkUrl = release.apkUrl ?: throw IOException("В релизе нет APK.")
        val expectedDigest = release.apkDigest?.removePrefix("sha256:")?.lowercase()
            ?: throw IOException("Релиз не содержит SHA-256 для APK.")
        require(expectedDigest.matches(Regex("^[a-f0-9]{64}$"))) {
            "У релиза некорректный SHA-256."
        }
        val uri = Uri.parse(apkUrl)
        require(uri.scheme == "https" && uri.host == "github.com" &&
            uri.path.orEmpty().contains("/releases/download/") && uri.userInfo == null) {
            "APK должен скачиваться напрямую из GitHub Releases."
        }

        val directory = File(context.cacheDir, "nexus-updates")
        check(directory.exists() || directory.mkdirs()) { "Не удалось создать каталог обновлений." }
        val safeVersion = release.version.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val apkFile = File(directory, "nexus-$safeVersion.apk")
        if (!apkFile.exists() || sha256(apkFile) != expectedDigest) {
            download(apkUrl, apkFile, expectedDigest)
        }

        verifyPackageAndSignature(apkFile, release.version)
        withContext(Dispatchers.Main) {
            launchSystemInstaller(apkFile)
        }
    }

    private fun download(url: String, target: File, expectedDigest: String) {
        val temporary = File(target.parentFile, target.name + ".part")
        temporary.delete()
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.android.package-archive")
            .header("User-Agent", "NEXUS-Android-Updater")
            .get()
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("GitHub не смог отдать APK (HTTP " + response.code + ").")
                val finalUrl = response.request.url
                require(finalUrl.isHttps && finalUrl.host in setOf(
                    "github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com"
                )) { "GitHub перенаправил загрузку на недоверенный адрес." }

                val body = response.body ?: throw IOException("GitHub вернул пустой APK.")
                val declaredLength = body.contentLength()
                require(declaredLength <= MAX_APK_BYTES) { "APK превышает лимит 500 МиБ." }

                val digest = MessageDigest.getInstance("SHA-256")
                var total = 0L
                body.byteStream().use { input ->
                    temporary.outputStream().buffered().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= MAX_APK_BYTES) { "APK превышает лимит 500 МиБ." }
                            digest.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                        }
                    }
                }
                require(total > 0) { "Скачан пустой APK." }
                val actualDigest = digest.digest().joinToString("") { "%02x".format(it) }
                require(MessageDigest.isEqual(
                    actualDigest.toByteArray(Charsets.US_ASCII),
                    expectedDigest.toByteArray(Charsets.US_ASCII)
                )) { "SHA-256 APK не совпадает с опубликованным GitHub digest." }
            }

            check(temporary.renameTo(target)) { "Не удалось сохранить проверенный APK." }
        } catch (error: Exception) {
            temporary.delete()
            throw error
        }
    }

    @Suppress("DEPRECATION")
    private fun verifyPackageAndSignature(apkFile: File, expectedVersion: String) {
        val flags = if (Build.VERSION.SDK_INT >= 28) {
            android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            android.content.pm.PackageManager.GET_SIGNATURES
        }
        val archive = context.packageManager.getPackageArchiveInfo(apkFile.absolutePath, flags)
            ?: throw IOException("Android не распознал APK.")
        require(archive.packageName == context.packageName) {
            "APK относится к другому приложению: " + archive.packageName
        }
        require(archive.versionName?.removePrefix("v") == expectedVersion) {
            "Версия APK не совпадает с версией GitHub Release."
        }
        val installed = context.packageManager.getPackageInfo(context.packageName, flags)

        fun signerDigests(info: android.content.pm.PackageInfo): Set<String> {
            val signers = if (Build.VERSION.SDK_INT >= 28) {
                info.signingInfo?.apkContentsSigners
            } else {
                info.signatures
            } ?: emptyArray()
            return signers.map { signature ->
                MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
                    .joinToString("") { "%02x".format(it) }
            }.toSet()
        }

        val installedSigners = signerDigests(installed)
        val downloadedSigners = signerDigests(archive)
        require(installedSigners.isNotEmpty() && installedSigners == downloadedSigners) {
            "Подпись APK отличается от подписи установленного NEXUS. Обновление заблокировано."
        }
    }

    private fun launchSystemInstaller(apkFile: File): NexusInstallResult {
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            val settingsIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + context.packageName)
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(settingsIntent)
            return NexusInstallResult.PermissionSettingsOpened(apkFile)
        }

        val contentUri = FileProvider.getUriForFile(
            context,
            context.packageName + ".fileprovider",
            apkFile
        )
        val installIntent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(contentUri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(installIntent)
        return NexusInstallResult.InstallerOpened(apkFile)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val MAX_APK_BYTES = 500L * 1024L * 1024L
    }
}
