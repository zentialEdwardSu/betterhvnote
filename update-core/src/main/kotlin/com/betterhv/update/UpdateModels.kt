package com.betterhv.update

enum class UpdateProduct(val tagPrefix: String) {
    NOTE("note-v"),
    NOTELINK("notelink-v"),
}

data class ReleaseRecord(
    val tagName: String,
    val title: String,
    val pageUrl: String,
    val draft: Boolean,
    val preRelease: Boolean,
)

data class UpdateInfo(
    val currentVersion: AppVersion,
    val latestVersion: AppVersion,
    val releaseTitle: String,
    val releaseUrl: String,
)

sealed interface UpdateCheckState {
    data object Idle : UpdateCheckState
    data object Checking : UpdateCheckState
    data class UpToDate(
        val currentVersion: AppVersion,
        val latestVersion: AppVersion?,
    ) : UpdateCheckState
    data class UpdateAvailable(val info: UpdateInfo) : UpdateCheckState
    data class Failed(val message: String) : UpdateCheckState
}

data class UpdateUiState(
    val checkState: UpdateCheckState = UpdateCheckState.Idle,
    val bannerDismissed: Boolean = false,
    val manualErrorVisible: Boolean = false,
) {
    val visibleUpdate: UpdateInfo?
        get() = (checkState as? UpdateCheckState.UpdateAvailable)?.info?.takeUnless { bannerDismissed }

    fun checking(manual: Boolean): UpdateUiState = copy(
        checkState = UpdateCheckState.Checking,
        manualErrorVisible = if (manual) false else manualErrorVisible,
    )

    fun completed(result: UpdateCheckState, manual: Boolean): UpdateUiState = copy(
        checkState = result,
        bannerDismissed = if (result is UpdateCheckState.UpdateAvailable) false else bannerDismissed,
        manualErrorVisible = manual && result is UpdateCheckState.Failed,
    )

    fun dismissBanner(): UpdateUiState = copy(bannerDismissed = true)
}

fun interface ReleaseSource {
    fun loadReleases(): List<ReleaseRecord>
}
