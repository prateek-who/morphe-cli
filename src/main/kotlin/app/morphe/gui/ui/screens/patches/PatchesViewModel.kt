package app.morphe.gui.ui.screens.patches

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import app.morphe.gui.data.model.Release
import app.morphe.gui.data.repository.ConfigRepository
import app.morphe.gui.data.repository.PatchRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import app.morphe.gui.util.Logger
import java.io.File

class PatchesViewModel(
    private val apkPath: String,
    private val apkName: String,
    private val patchRepository: PatchRepository,
    private val configRepository: ConfigRepository
) : ScreenModel {

    private val _uiState = MutableStateFlow(PatchesUiState())
    val uiState: StateFlow<PatchesUiState> = _uiState.asStateFlow()

    init {
        loadReleases()
    }

    fun loadReleases() {
        screenModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)

            val result = patchRepository.fetchReleases()

            result.fold(
                onSuccess = { releases ->
                    val stableReleases = releases.filter { !it.isDevRelease() }
                    val devReleases = releases.filter { it.isDevRelease() }

                    // Check config for previously selected version
                    val config = configRepository.loadConfig()
                    val savedVersion = config.lastPatchesVersion

                    // Find the saved release, or fall back to latest stable
                    val initialRelease = if (savedVersion != null) {
                        // Try to find in stable first, then dev
                        stableReleases.find { it.tagName == savedVersion }
                            ?: devReleases.find { it.tagName == savedVersion }
                            ?: stableReleases.firstOrNull()
                    } else {
                        stableReleases.firstOrNull()
                    }

                    // Determine initial channel based on selected release
                    val initialChannel = if (initialRelease != null && initialRelease.isDevRelease()) {
                        ReleaseChannel.DEV
                    } else {
                        ReleaseChannel.STABLE
                    }

                    // Check if patches for the initial release are already cached
                    val cachedFile = initialRelease?.let { checkCachedPatches(it) }

                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        stableReleases = stableReleases,
                        devReleases = devReleases,
                        selectedChannel = initialChannel,
                        selectedRelease = initialRelease,
                        downloadedPatchFile = cachedFile
                    )
                    Logger.info("Loaded ${stableReleases.size} stable and ${devReleases.size} dev releases, saved=$savedVersion, selected=${initialRelease?.tagName}, cached: ${cachedFile != null}")
                },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = e.message ?: "Failed to load releases"
                    )
                    Logger.error("Failed to load releases", e)
                }
            )
        }
    }

    fun selectRelease(release: Release) {
        // Check if patches for this release are already cached
        val cachedFile = checkCachedPatches(release)

        _uiState.value = _uiState.value.copy(
            selectedRelease = release,
            downloadedPatchFile = cachedFile
        )
        Logger.info("Selected release: ${release.tagName}, cached: ${cachedFile != null}")
    }

    /**
     * Check if patches for a release are already downloaded and valid.
     */
    private fun checkCachedPatches(release: Release): File? {
        val asset = patchRepository.findMppAsset(release) ?: return null
        val patchesDir = app.morphe.gui.util.FileUtils.getPatchesDir()
        val cachedFile = File(patchesDir, asset.name)

        // Verify file exists and size matches (size check acts as basic integrity verification)
        return if (cachedFile.exists() && cachedFile.length() == asset.size) {
            Logger.info("Found cached patches: ${cachedFile.absolutePath}")
            cachedFile
        } else {
            null
        }
    }

    fun setChannel(channel: ReleaseChannel) {
        val newRelease = when (channel) {
            ReleaseChannel.STABLE -> _uiState.value.stableReleases.firstOrNull()
            ReleaseChannel.DEV -> _uiState.value.devReleases.firstOrNull()
        }

        // Check if patches for the new release are already cached
        val cachedFile = newRelease?.let { checkCachedPatches(it) }

        _uiState.value = _uiState.value.copy(
            selectedChannel = channel,
            selectedRelease = newRelease,
            downloadedPatchFile = cachedFile
        )
    }

    fun downloadPatches() {
        val release = _uiState.value.selectedRelease ?: return

        screenModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isDownloading = true,
                downloadProgress = 0f,
                error = null
            )

            val result = patchRepository.downloadPatches(release) { progress ->
                _uiState.value = _uiState.value.copy(downloadProgress = progress)
            }

            result.fold(
                onSuccess = { patchFile ->
                    _uiState.value = _uiState.value.copy(
                        isDownloading = false,
                        downloadedPatchFile = patchFile,
                        downloadProgress = 1f
                    )
                    Logger.info("Patches downloaded: ${patchFile.absolutePath}")

                    // Save the selected version to config so HomeScreen can pick it up
                    configRepository.setLastPatchesVersion(release.tagName)
                    Logger.info("Saved selected patches version to config: ${release.tagName}")
                },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(
                        isDownloading = false,
                        error = e.message ?: "Failed to download patches"
                    )
                    Logger.error("Failed to download patches", e)
                }
            )
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    /**
     * Confirm the current selection and save it to config.
     * Called when user clicks "Select" button.
     */
    fun confirmSelection() {
        val release = _uiState.value.selectedRelease ?: return
        screenModelScope.launch {
            configRepository.setLastPatchesVersion(release.tagName)
            Logger.info("Confirmed patches selection: ${release.tagName}")
        }
    }

    fun getApkPath(): String = apkPath
    fun getApkName(): String = apkName
}

enum class ReleaseChannel {
    STABLE,
    DEV
}

data class PatchesUiState(
    val isLoading: Boolean = false,
    val stableReleases: List<Release> = emptyList(),
    val devReleases: List<Release> = emptyList(),
    val selectedChannel: ReleaseChannel = ReleaseChannel.STABLE,
    val selectedRelease: Release? = null,
    val isDownloading: Boolean = false,
    val downloadProgress: Float = 0f,
    val downloadedPatchFile: File? = null,
    val error: String? = null
) {
    val currentReleases: List<Release>
        get() = when (selectedChannel) {
            ReleaseChannel.STABLE -> stableReleases
            ReleaseChannel.DEV -> devReleases
        }

    val isReady: Boolean
        get() = downloadedPatchFile != null
}
