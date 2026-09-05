package com.betterhv.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class UpdateCheckerTest {
    @Test
    fun `filters product drafts prereleases malformed tags and untrusted urls`() {
        val checker = UpdateChecker(
            ReleaseSource {
                listOf(
                    release("notelink-v9.0.0"),
                    release("note-v8.0.0", draft = true),
                    release("note-v7.0.0", preRelease = true),
                    release("note-vbad"),
                    release("note-v6.0.0", url = "https://example.com/release"),
                    release("note-v1.2.0", title = "Note 1.2"),
                    release("note-v1.3.0", title = "Note 1.3"),
                )
            }
        )

        val result = assertIs<UpdateCheckState.UpdateAvailable>(checker.check(UpdateProduct.NOTE, "1.1"))
        assertEquals("1.3.0", result.info.latestVersion.display)
        assertEquals("Note 1.3", result.info.releaseTitle)
    }

    @Test
    fun `reports current or newer builds as up to date`() {
        val checker = UpdateChecker(ReleaseSource { listOf(release("notelink-v0.2.3")) })
        assertIs<UpdateCheckState.UpToDate>(checker.check(UpdateProduct.NOTELINK, "0.2.3"))
        assertIs<UpdateCheckState.UpToDate>(checker.check(UpdateProduct.NOTELINK, "0.3.0"))
    }

    @Test
    fun `no matching release is a successful empty result`() {
        val checker = UpdateChecker(ReleaseSource { listOf(release("note-v1.0.0")) })
        val result = assertIs<UpdateCheckState.UpToDate>(checker.check(UpdateProduct.NOTELINK, "0.2.3"))
        assertNull(result.latestVersion)
    }

    @Test
    fun `source and current version failures are recoverable`() {
        val failing = UpdateChecker(ReleaseSource { error("offline") })
        assertEquals("offline", assertIs<UpdateCheckState.Failed>(failing.check(UpdateProduct.NOTE, "1.0.0")).message)
        assertIs<UpdateCheckState.Failed>(failing.check(UpdateProduct.NOTE, "broken"))
    }

    private fun release(
        tag: String,
        title: String = tag,
        url: String = "https://github.com/zentialEdwardSu/betterhvnote/releases/tag/$tag",
        draft: Boolean = false,
        preRelease: Boolean = false,
    ) = ReleaseRecord(tag, title, url, draft, preRelease)
}
