package com.betterhv.update

import java.net.URI

class UpdateChecker(private val source: ReleaseSource = GitHubReleaseSource()) {
  fun check(product: UpdateProduct, currentVersion: String): UpdateCheckState = runCatching {
    val current = AppVersion.parse(currentVersion)
      ?: return UpdateCheckState.Failed("Unrecognized current version: $currentVersion")
    val latest = source.loadReleases()
      .asSequence()
      .filterNot { it.draft || it.preRelease }
      .mapNotNull { release ->
        val rawVersion = release.tagName.takeIf { it.startsWith(product.tagPrefix) }
          ?.removePrefix(product.tagPrefix)
          ?: return@mapNotNull null
        val version = AppVersion.parse(rawVersion) ?: return@mapNotNull null
        release.takeIf { isTrustedReleaseUrl(it.pageUrl) }?.let { version to it }
      }
      .maxByOrNull { it.first }
    if (latest == null) {
      UpdateCheckState.UpToDate(current, null)
    } else if (latest.first > current) {
      UpdateCheckState.UpdateAvailable(
        UpdateInfo(current, latest.first, latest.second.title, latest.second.pageUrl),
      )
    } else {
      UpdateCheckState.UpToDate(current, latest.first)
    }
  }.getOrElse { error ->
    UpdateCheckState.Failed(error.message?.takeIf(String::isNotBlank) ?: "Update check failed")
  }

  companion object {
    fun isTrustedReleaseUrl(value: String): Boolean = runCatching {
      val uri = URI(value)
      uri.scheme == "https" && uri.host.equals("github.com", ignoreCase = true) &&
        uri.path.startsWith("/zentialEdwardSu/betterhvnote/releases/")
    }.getOrDefault(false)
  }
}
