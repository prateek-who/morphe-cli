package app.morphe.gui.data.repository

import app.morphe.gui.data.model.Release
import app.morphe.gui.data.model.ReleaseAsset
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.morphe.gui.util.FileUtils
import app.morphe.gui.util.Logger
import java.io.File

/**
 * Repository for fetching Morphe patches from GitHub releases.
 */
class PatchRepository(
    private val httpClient: HttpClient
) {
    companion object {
        private const val GITHUB_API_BASE = "https://api.github.com"
        private const val PATCHES_REPO = "MorpheApp/morphe-patches"
        private const val RELEASES_ENDPOINT = "$GITHUB_API_BASE/repos/$PATCHES_REPO/releases"
    }

    /**
     * Fetch all releases from GitHub.
     */
    suspend fun fetchReleases(): Result<List<Release>> = withContext(Dispatchers.IO) {
        try {
            Logger.info("Fetching releases from $RELEASES_ENDPOINT")
            val response: HttpResponse = httpClient.get(RELEASES_ENDPOINT) {
                headers {
                    append(HttpHeaders.Accept, "application/vnd.github+json")
                    append("X-GitHub-Api-Version", "2022-11-28")
                }
            }

            if (response.status.isSuccess()) {
                val releases: List<Release> = response.body()
                Logger.info("Fetched ${releases.size} releases")
                Result.success(releases)
            } else {
                val error = "Failed to fetch releases: ${response.status}"
                Logger.error(error)
                Result.failure(Exception(error))
            }
        } catch (e: Exception) {
            Logger.error("Error fetching releases", e)
            Result.failure(e)
        }
    }

    /**
     * Get stable releases only (non-prerelease).
     */
    suspend fun fetchStableReleases(): Result<List<Release>> {
        return fetchReleases().map { releases ->
            releases.filter { !it.isDevRelease() }
        }
    }

    /**
     * Get dev/prerelease versions only.
     */
    suspend fun fetchDevReleases(): Result<List<Release>> {
        return fetchReleases().map { releases ->
            releases.filter { it.isDevRelease() }
        }
    }

    /**
     * Get the latest stable release.
     */
    suspend fun getLatestStableRelease(): Result<Release?> {
        return fetchStableReleases().map { it.firstOrNull() }
    }

    /**
     * Get the latest dev release.
     */
    suspend fun getLatestDevRelease(): Result<Release?> {
        return fetchDevReleases().map { it.firstOrNull() }
    }

    /**
     * Find the .mpp asset in a release.
     */
    fun findMppAsset(release: Release): ReleaseAsset? {
        return release.assets.find { it.isMpp() }
    }

    /**
     * Download the .mpp patch file from a release.
     * Returns the path to the downloaded file.
     */
    suspend fun downloadPatches(release: Release, onProgress: (Float) -> Unit = {}): Result<File> = withContext(Dispatchers.IO) {
        val asset = findMppAsset(release)
        if (asset == null) {
            val error = "No .mpp file found in release ${release.tagName}"
            Logger.error(error)
            return@withContext Result.failure(Exception(error))
        }

        val patchesDir = FileUtils.getPatchesDir()
        val targetFile = File(patchesDir, asset.name)

        // Check if already cached
        if (targetFile.exists() && targetFile.length() == asset.size) {
            Logger.info("Using cached patches: ${targetFile.absolutePath}")
            onProgress(1f)
            return@withContext Result.success(targetFile)
        }

        try {
            Logger.info("Downloading patches from ${asset.downloadUrl}")

            val response: HttpResponse = httpClient.get(asset.downloadUrl) {
                headers {
                    append(HttpHeaders.Accept, "application/octet-stream")
                }
            }

            if (!response.status.isSuccess()) {
                val error = "Failed to download patches: ${response.status}"
                Logger.error(error)
                return@withContext Result.failure(Exception(error))
            }

            val bytes = response.readRawBytes()
            targetFile.writeBytes(bytes)
            onProgress(1f)

            Logger.info("Patches downloaded to ${targetFile.absolutePath}")
            Result.success(targetFile)
        } catch (e: Exception) {
            Logger.error("Error downloading patches", e)
            // Clean up partial download
            if (targetFile.exists()) {
                targetFile.delete()
            }
            Result.failure(e)
        }
    }

    /**
     * Get cached patch file for a specific version.
     */
    fun getCachedPatches(version: String): File? {
        val patchesDir = FileUtils.getPatchesDir()
        return patchesDir.listFiles()?.find {
            it.name.contains(version) && it.name.endsWith(".mpp")
        }
    }

    /**
     * List all cached patch versions.
     */
    fun listCachedPatches(): List<File> {
        val patchesDir = FileUtils.getPatchesDir()
        return patchesDir.listFiles()?.filter { it.name.endsWith(".mpp") } ?: emptyList()
    }

    /**
     * Delete cached patches.
     */
    fun clearCache(): Boolean {
        return try {
            FileUtils.getPatchesDir().listFiles()?.forEach { it.delete() }
            Logger.info("Patches cache cleared")
            true
        } catch (e: Exception) {
            Logger.error("Failed to clear patches cache", e)
            false
        }
    }
}
