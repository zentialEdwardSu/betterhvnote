package com.betterhv.update

import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GitHubReleaseSourceTest {
    @Test
    fun `parses github response and configures request`() {
        val connection = FakeConnection(
            200,
            """[{"tag_name":"note-v1.0.0","name":"One","html_url":"https://github.com/""" +
                """zentialEdwardSu/betterhvnote/releases/tag/note-v1.0.0","draft":false,"prerelease":false}]"""
        )
        val source = GitHubReleaseSource(TEST_URL, UrlConnectionFactory { connection })

        val releases = source.loadReleases()

        assertEquals("note-v1.0.0", releases.single().tagName)
        assertEquals("BetterHvNote-UpdateChecker", connection.getRequestProperty("User-Agent"))
        assertEquals(5_000, connection.connectTimeout)
        assertEquals(10_000, connection.readTimeout)
    }

    @Test
    fun `http and malformed json failures are reported`() {
        assertFailsWith<IOException> {
            GitHubReleaseSource(TEST_URL, UrlConnectionFactory { FakeConnection(403, "") }).loadReleases()
        }
        assertFailsWith<IOException> {
            GitHubReleaseSource(TEST_URL, UrlConnectionFactory { FakeConnection(200, "not-json") }).loadReleases()
        }
    }

    @Test
    fun `connection failure is propagated`() {
        assertFailsWith<IOException> {
            GitHubReleaseSource(TEST_URL, UrlConnectionFactory { throw IOException("offline") }).loadReleases()
        }
    }

    @Test
    fun `oversized response is rejected`() {
        val body = "x".repeat(OVERSIZED_RESPONSE_BYTES)
        assertFailsWith<IOException> {
            GitHubReleaseSource(TEST_URL, UrlConnectionFactory { FakeConnection(200, body) }).loadReleases()
        }
    }

    private class FakeConnection(
        private val status: Int,
        body: String,
    ) : HttpURLConnection(TEST_URL) {
        private val content = body.encodeToByteArray()
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy(): Boolean = false
        override fun getResponseCode(): Int = status
        override fun getInputStream() = ByteArrayInputStream(content)
    }

    companion object {
        private val TEST_URL = URL("https://api.github.com/test")
        private const val OVERSIZED_RESPONSE_BYTES = 1_048_577
    }
}
