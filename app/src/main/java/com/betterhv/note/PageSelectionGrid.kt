package com.betterhv.note

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import java.util.UUID

private val NotionBorder = Color(0xFFE3E3E1)

/**
 * Reusable page selection grid with thumbnail display and checkbox overlay.
 * Extracted from NotebookManagerScreen for use in both notebook creation
 * and export page selection.
 */
@Composable
fun PageSelectionGrid(
  pages: List<PageUiInfo>,
  selectedIds: Set<UUID>,
  pageBitmap: (UUID) -> Bitmap?,
  onToggle: (UUID) -> Unit,
  modifier: Modifier = Modifier,
) {
  LazyVerticalGrid(
    columns = GridCells.Adaptive(116.dp),
    modifier = modifier.fillMaxSize().padding(24.dp),
    horizontalArrangement = Arrangement.spacedBy(12.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    items(pages, key = PageUiInfo::id) { page ->
      SelectablePageCard(
        page = page,
        bitmap = pageBitmap(page.id),
        selected = page.id in selectedIds,
        onToggle = { onToggle(page.id) },
      )
    }
  }
}

@Composable
private fun SelectablePageCard(page: PageUiInfo, bitmap: Bitmap?, selected: Boolean, onToggle: () -> Unit) {
  Box(
    modifier = Modifier
      .fillMaxWidth()
      .aspectRatio(0.75f)
      .background(Color.White, RectangleShape)
      .border(
        if (selected) 2.dp else 1.dp,
        if (selected) Color.Black else NotionBorder,
        RectangleShape,
      )
      .clickable(onClick = onToggle),
  ) {
    bitmap?.let {
      Image(
        it.asImageBitmap(),
        contentDescription = noteText("第 ${page.pageNumber} 页", "Page ${page.pageNumber}"),
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.Fit,
      )
    }
    Text(
      page.pageNumber.toString(),
      modifier = Modifier.align(Alignment.TopStart).background(Color.White).padding(4.dp),
    )
    if (selected) {
      Box(
        Modifier
          .align(Alignment.TopEnd)
          .size(28.dp)
          .background(Color.Black, RectangleShape),
        contentAlignment = Alignment.Center,
      ) {
        Icon(Icons.Filled.Check, contentDescription = noteText("已选择", "Selected"), tint = Color.White)
      }
    }
  }
}
