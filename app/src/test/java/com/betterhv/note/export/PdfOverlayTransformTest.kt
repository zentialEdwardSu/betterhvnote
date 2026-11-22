package com.betterhv.note.export

import com.betterhv.note.doc.Transform2D
import org.junit.Assert.assertEquals
import org.junit.Test

class PdfOverlayTransformTest {
  @Test fun standardZeroOriginPageProducesIdentityFormMatrix() {
    val pageTransform = Transform2D(1f, 0f, 0f, -1f, 0f, 400f)
    val matrix = PdfOverlayTransform.formMatrix(
      pageTransform = pageTransform,
      pageBoundsLeft = 0f,
      pageBoundsTop = 0f,
      pageBoundsRight = 300f,
      pageBoundsBottom = 400f,
      overlayWidth = 300f,
      overlayHeight = 400f,
    )

    assertTransformEquals(Transform2D.IDENTITY, matrix)
    assertMapsOverlayCornersToFitzBounds(pageTransform, matrix, 0f, 0f, 300f, 400f, 300f, 400f)
  }

  @Test fun shiftedMediaBoxTranslatesFormIntoUserSpaceOrigin() {
    val pageTransform = Transform2D(1f, 0f, 0f, -1f, -100f, 600f)
    val matrix = PdfOverlayTransform.formMatrix(
      pageTransform = pageTransform,
      pageBoundsLeft = 0f,
      pageBoundsTop = 0f,
      pageBoundsRight = 300f,
      pageBoundsBottom = 400f,
      overlayWidth = 300f,
      overlayHeight = 400f,
    )

    assertTransformEquals(Transform2D.translate(100f, 200f), matrix)
    assertMapsOverlayCornersToFitzBounds(pageTransform, matrix, 0f, 0f, 300f, 400f, 300f, 400f)
  }

  @Test fun ninetyDegreeRotatedPageMapsOverlayCorrectlyIntoFitzBounds() {
    val pageTransform = Transform2D(0f, 1f, 1f, 0f, 0f, 0f)
    val matrix = PdfOverlayTransform.formMatrix(
      pageTransform = pageTransform,
      pageBoundsLeft = 0f,
      pageBoundsTop = 0f,
      pageBoundsRight = 300f,
      pageBoundsBottom = 400f,
      overlayWidth = 300f,
      overlayHeight = 400f,
    )

    assertTransformEquals(Transform2D(0f, 1f, -1f, 0f, 400f, 0f), matrix)
    assertMapsOverlayCornersToFitzBounds(pageTransform, matrix, 0f, 0f, 300f, 400f, 300f, 400f)
  }

  @Test fun scaledOverlaySizeMatchesTargetPageBounds() {
    val pageTransform = Transform2D(1f, 0f, 0f, -1f, 0f, 400f)
    val matrix = PdfOverlayTransform.formMatrix(
      pageTransform = pageTransform,
      pageBoundsLeft = 0f,
      pageBoundsTop = 0f,
      pageBoundsRight = 300f,
      pageBoundsBottom = 400f,
      overlayWidth = 600f,
      overlayHeight = 800f,
    )

    assertTransformEquals(Transform2D(0.5f, 0f, 0f, 0.5f, 0f, 0f), matrix)
    assertMapsOverlayCornersToFitzBounds(pageTransform, matrix, 0f, 0f, 300f, 400f, 600f, 800f)
  }

  private fun assertMapsOverlayCornersToFitzBounds(
    pageTransform: Transform2D,
    formMatrix: Transform2D,
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    overlayWidth: Float,
    overlayHeight: Float,
  ) {
    val composed = pageTransform.times(formMatrix)
    val topLeft = composed.mapPoint(0f, overlayHeight)
    val topRight = composed.mapPoint(overlayWidth, overlayHeight)
    val bottomLeft = composed.mapPoint(0f, 0f)
    val bottomRight = composed.mapPoint(overlayWidth, 0f)

    assertEquals(left, topLeft[0], EPS)
    assertEquals(top, topLeft[1], EPS)
    assertEquals(right, topRight[0], EPS)
    assertEquals(top, topRight[1], EPS)
    assertEquals(left, bottomLeft[0], EPS)
    assertEquals(bottom, bottomLeft[1], EPS)
    assertEquals(right, bottomRight[0], EPS)
    assertEquals(bottom, bottomRight[1], EPS)
  }

  private fun assertTransformEquals(expected: Transform2D, actual: Transform2D) {
    assertEquals(expected.a, actual.a, EPS)
    assertEquals(expected.b, actual.b, EPS)
    assertEquals(expected.c, actual.c, EPS)
    assertEquals(expected.d, actual.d, EPS)
    assertEquals(expected.tx, actual.tx, EPS)
    assertEquals(expected.ty, actual.ty, EPS)
  }

  private companion object {
    const val EPS = 0.001f
  }
}
