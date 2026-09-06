package com.betterhv.note

import com.betterhv.note.ink.Bounds

data class ViewportTransform(val scale: Float, val offsetX: Float, val offsetY: Float) {
  init {
    require(scale > 0f)
  }

  fun screenToPage(x: Float, y: Float): FloatArray = floatArrayOf((x - offsetX) / scale, (y - offsetY) / scale)

  fun pageToScreen(x: Float, y: Float): FloatArray = floatArrayOf(x * scale + offsetX, y * scale + offsetY)

  fun pageToScreen(bounds: Bounds): Bounds = Bounds(
    bounds.left * scale + offsetX,
    bounds.top * scale + offsetY,
    bounds.right * scale + offsetX,
    bounds.bottom * scale + offsetY,
  )

  fun screenDistanceToPage(distance: Float): Float = distance / scale

  companion object {
    fun fit(pageWidth: Float, pageHeight: Float, viewWidth: Int, viewHeight: Int): ViewportTransform {
      if (pageWidth <= 0f || pageHeight <= 0f || viewWidth <= 0 || viewHeight <= 0) {
        return ViewportTransform(1f, 0f, 0f)
      }
      val scale = minOf(viewWidth / pageWidth, viewHeight / pageHeight)
      return ViewportTransform(
        scale,
        (viewWidth - pageWidth * scale) / 2f,
        (viewHeight - pageHeight * scale) / 2f,
      )
    }
  }
}

internal data class PdfViewportState(
  val zoom: Float = 1f,
  val panX: Float = 0f,
  val panY: Float = 0f,
  val revision: Int = 0,
)

/** Framework-free PDF pan/zoom math, shared with JVM tests. */
internal object PdfViewportMath {
  fun transform(
    state: PdfViewportState,
    pageWidth: Float,
    pageHeight: Float,
    viewWidth: Int,
    viewHeight: Int,
  ): ViewportTransform {
    val base = ViewportTransform.fit(pageWidth, pageHeight, viewWidth, viewHeight)
    val scale = base.scale * state.zoom
    return ViewportTransform(
      scale,
      (viewWidth - pageWidth * scale) / 2f + state.panX,
      (viewHeight - pageHeight * scale) / 2f + state.panY,
    )
  }

  fun clamp(
    state: PdfViewportState,
    pageWidth: Float,
    pageHeight: Float,
    viewWidth: Int,
    viewHeight: Int,
  ): PdfViewportState {
    val zoom = state.zoom.coerceIn(1f, 4f)
    val base = ViewportTransform.fit(pageWidth, pageHeight, viewWidth, viewHeight)
    val scale = base.scale * zoom
    val contentWidth = pageWidth * scale
    val contentHeight = pageHeight * scale
    val centerX = (viewWidth - contentWidth) / 2f
    val centerY = (viewHeight - contentHeight) / 2f
    // A fitted page still needs to respond to Side1 dragging. Permit a
    // bounded quarter-screen pan on axes where the page is no larger than
    // the viewport; the Fit action itself continues to reset to center.
    val offsetX = if (contentWidth <= viewWidth) {
      (centerX + state.panX).coerceIn(centerX - viewWidth * FIT_PAN_FRACTION, centerX + viewWidth * FIT_PAN_FRACTION)
    } else {
      (centerX + state.panX).coerceIn(viewWidth - contentWidth, 0f)
    }
    val offsetY = if (contentHeight <= viewHeight) {
      (centerY + state.panY).coerceIn(centerY - viewHeight * FIT_PAN_FRACTION, centerY + viewHeight * FIT_PAN_FRACTION)
    } else {
      (centerY + state.panY).coerceIn(viewHeight - contentHeight, 0f)
    }
    return state.copy(zoom = zoom, panX = offsetX - centerX, panY = offsetY - centerY)
  }

  fun zoomAt(
    start: PdfViewportState,
    nextZoom: Float,
    focusX: Float,
    focusY: Float,
    pageWidth: Float,
    pageHeight: Float,
    viewWidth: Int,
    viewHeight: Int,
  ): PdfViewportState {
    val old = transform(start, pageWidth, pageHeight, viewWidth, viewHeight)
    val pagePoint = old.screenToPage(focusX, focusY)
    val base = ViewportTransform.fit(pageWidth, pageHeight, viewWidth, viewHeight)
    val boundedZoom = nextZoom.coerceIn(1f, 4f)
    val nextScale = base.scale * boundedZoom
    val centerX = (viewWidth - pageWidth * nextScale) / 2f
    val centerY = (viewHeight - pageHeight * nextScale) / 2f
    return clamp(
      start.copy(
        zoom = boundedZoom,
        panX = focusX - pagePoint[0] * nextScale - centerX,
        panY = focusY - pagePoint[1] * nextScale - centerY,
      ),
      pageWidth,
      pageHeight,
      viewWidth,
      viewHeight,
    )
  }

  private const val FIT_PAN_FRACTION = 0.25f
}
