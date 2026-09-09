package com.betterhv.note.ink

/** Summarizes ROM-computed widths and the dimensionless scalar sent to the modeler. */
object PressureDiagnostics {
  fun summarize(
    raw: FloatArray,
    count: Int,
    style: PenStyle,
    viewportScale: Float,
    configuredWidthPx: Int,
  ): String {
    val widthsPx = (0 until count).map { raw[it * 3 + 3] }.filter(Float::isFinite)
    if (widthsPx.isEmpty()) return "no finite vendor width samples"
    val middle = widthsPx.subList(widthsPx.size / 4, (widthsPx.size * 3 / 4).coerceAtLeast(1))
    val levels = widthsPx.groupingBy { it }.eachCount().toSortedMap().entries.take(32)
      .joinToString(";") { (widthPx, samples) ->
        "$widthPx:$samples:${RomStrokeMapping.pointScalar(style.penType, widthPx, configuredWidthPx)}"
      }
    return "type=${style.penType} configuredWidthPx=$configuredWidthPx effectivePageWidth=${style.baseWidth} " +
      "viewportScale=$viewportScale " +
      "vendorWidthPxMin=${widthsPx.min()} vendorWidthPxMax=${widthsPx.max()} " +
      "p10=${percentile(widthsPx, 0.1f)} p50=${percentile(widthsPx, 0.5f)} " +
      "p90=${percentile(widthsPx, 0.9f)} middleP50=${percentile(middle, 0.5f)} " +
      "middleP90=${percentile(middle, 0.9f)} zeros=${widthsPx.count { it == 0f }}/${widthsPx.size} " +
      "levels(screenWidth:count:modelScalar)=[$levels]"
  }

  private fun percentile(values: List<Float>, fraction: Float): Float {
    val sorted = values.sorted()
    return sorted[((sorted.lastIndex * fraction).toInt()).coerceIn(sorted.indices)]
  }
}
