package com.betterhv.note.sender

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SharedStreamClassificationTest {
  @Test fun prefersRecognizedProviderMimeType() {
    assertEquals(SharedStreamKind.PDF, classifySharedStream("application/pdf", "image/*"))
    assertEquals(SharedStreamKind.IMAGE, classifySharedStream("image/png", "application/pdf"))
  }

  @Test fun fallsBackToDeclaredIntentMimeType() {
    assertEquals(SharedStreamKind.PDF, classifySharedStream(null, "application/pdf"))
    assertEquals(SharedStreamKind.IMAGE, classifySharedStream(null, "image/jpeg"))
  }

  @Test fun rejectsUnsupportedStreamTypes() {
    assertNull(classifySharedStream("application/zip", "application/octet-stream"))
    assertNull(classifySharedStream(null, null))
  }
}
