package com.betterhv.update

import com.google.gson.Gson
import com.google.gson.JsonParseException
import com.google.gson.annotations.SerializedName
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection

fun interface UrlConnectionFactory {
  fun open(url: URL): URLConnection
}

class GitHubReleaseSource(
  private val endpoint: URL = URL(DEFAULT_ENDPOINT),
  private val connectionFactory: UrlConnectionFactory = UrlConnectionFactory { it.openConnection() },
) : ReleaseSource {
  override fun loadReleases(): List<ReleaseRecord> {
    val connection = connectionFactory.open(endpoint) as? HttpURLConnection
      ?: throw IOException("GitHub URL is not an HTTP connection")
    return try {
      connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
      connection.readTimeout = READ_TIMEOUT_MILLIS
      connection.requestMethod = "GET"
      connection.setRequestProperty("Accept", "application/vnd.github+json")
      connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
      connection.setRequestProperty("User-Agent", "BetterHvNote-UpdateChecker")
      val status = connection.responseCode
      if (status !in HTTP_SUCCESS_MIN..HTTP_SUCCESS_MAX) throw IOException("GitHub returned HTTP $status")
      val body = connection.inputStream.use { input ->
        input.readLimitedText()
      }
      Gson().fromJson(body, Array<GitHubRelease>::class.java)?.map { release ->
        ReleaseRecord(
          tagName = release.tagName.orEmpty(),
          title = release.name?.takeIf(String::isNotBlank) ?: release.tagName.orEmpty(),
          pageUrl = release.htmlUrl.orEmpty(),
          draft = release.draft,
          preRelease = release.preRelease,
          assets = release.assets.orEmpty().map { asset ->
            ReleaseAsset(
              name = asset.name.orEmpty(),
              downloadUrl = asset.downloadUrl.orEmpty(),
              byteLength = asset.size ?: -1L,
            )
          },
        )
      } ?: throw IOException("Invalid GitHub response format")
    } catch (error: JsonParseException) {
      throw IOException("Could not parse GitHub response", error)
    } finally {
      connection.disconnect()
    }
  }

  private fun InputStream.readLimitedText(): String {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(READ_BUFFER_BYTES)
    while (true) {
      val count = read(buffer)
      if (count < 0) break
      if (output.size() + count > MAX_RESPONSE_BYTES) throw IOException("GitHub response is too large")
      output.write(buffer, 0, count)
    }
    return output.toByteArray().decodeToString()
  }

  private data class GitHubRelease(
    @SerializedName("tag_name") val tagName: String?,
    val name: String?,
    @SerializedName("html_url") val htmlUrl: String?,
    val draft: Boolean,
    @SerializedName("prerelease") val preRelease: Boolean,
    val assets: List<GitHubAsset>?,
  )

  private data class GitHubAsset(
    val name: String?,
    @SerializedName("browser_download_url") val downloadUrl: String?,
    val size: Long?,
  )

  companion object {
    private const val DEFAULT_ENDPOINT =
      "https://api.github.com/repos/zentialEdwardSu/betterhvnote/releases?per_page=100"
    private const val CONNECT_TIMEOUT_MILLIS = 5_000
    private const val READ_TIMEOUT_MILLIS = 10_000
    private const val MAX_RESPONSE_BYTES = 1_048_576
    private const val READ_BUFFER_BYTES = 8_192
    private const val HTTP_SUCCESS_MIN = 200
    private const val HTTP_SUCCESS_MAX = 299
  }
}
