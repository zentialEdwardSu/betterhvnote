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
  val assets: List<ReleaseAsset> = emptyList(),
)

data class ReleaseAsset(
  val name: String,
  val downloadUrl: String,
  val byteLength: Long,
)

data class NotePackageDescriptor(
  val version: AppVersion,
  val tagName: String,
  val releaseTitle: String,
  val apk: ReleaseAsset,
  val checksums: ReleaseAsset,
  val expectedSha256: ByteArray,
) {
  override fun equals(other: Any?): Boolean = other is NotePackageDescriptor &&
    version == other.version && tagName == other.tagName && releaseTitle == other.releaseTitle &&
    apk == other.apk && checksums == other.checksums && expectedSha256.contentEquals(other.expectedSha256)

  override fun hashCode(): Int = 31 * version.hashCode() + expectedSha256.contentHashCode()
}

data class UpdateInfo(
  val currentVersion: AppVersion,
  val latestVersion: AppVersion,
  val releaseTitle: String,
  val releaseUrl: String,
)

sealed interface UpdateCheckState {
  data object Idle : UpdateCheckState
  data object Checking : UpdateCheckState
  data class UpToDate(val currentVersion: AppVersion, val latestVersion: AppVersion?) : UpdateCheckState
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
