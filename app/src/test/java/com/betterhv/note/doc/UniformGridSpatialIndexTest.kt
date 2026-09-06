package com.betterhv.note.doc

import com.betterhv.note.ink.Bounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class UniformGridSpatialIndexTest {

  @Test
  fun `query finds an inserted object whose bounds overlap the query area`() {
    val index = UniformGridSpatialIndex(cellSize = 100f)
    val id = UUID.randomUUID()
    index.insert(id, Bounds(10f, 10f, 20f, 20f))
    val hits = index.query(Bounds(0f, 0f, 30f, 30f))
    assertTrue(id in hits)
  }

  @Test
  fun `query does not find an object far outside the query area`() {
    val index = UniformGridSpatialIndex(cellSize = 100f)
    val id = UUID.randomUUID()
    index.insert(id, Bounds(1000f, 1000f, 1010f, 1010f))
    val hits = index.query(Bounds(0f, 0f, 30f, 30f))
    assertFalse(id in hits)
  }

  @Test
  fun `object spanning multiple cells is found from either cell`() {
    val index = UniformGridSpatialIndex(cellSize = 100f)
    val id = UUID.randomUUID()
    // Spans cell (0,0) and cell (1,0).
    index.insert(id, Bounds(90f, 10f, 110f, 20f))
    assertTrue(id in index.query(Bounds(0f, 0f, 50f, 50f)))
    assertTrue(id in index.query(Bounds(150f, 0f, 200f, 50f)))
  }

  @Test
  fun `remove drops the object from every cell it occupied`() {
    val index = UniformGridSpatialIndex(cellSize = 100f)
    val id = UUID.randomUUID()
    val bounds = Bounds(90f, 10f, 110f, 20f)
    index.insert(id, bounds)
    index.remove(id, bounds)
    assertFalse(id in index.query(Bounds(0f, 0f, 200f, 50f)))
  }

  @Test
  fun `update moves the object so it is found only at its new bounds`() {
    val index = UniformGridSpatialIndex(cellSize = 100f)
    val id = UUID.randomUUID()
    val oldBounds = Bounds(10f, 10f, 20f, 20f)
    val newBounds = Bounds(500f, 500f, 520f, 520f)
    index.insert(id, oldBounds)
    index.update(id, oldBounds, newBounds)
    assertFalse(id in index.query(Bounds(0f, 0f, 30f, 30f)))
    assertTrue(id in index.query(Bounds(490f, 490f, 530f, 530f)))
  }

  @Test
  fun `query returns each id only once even if it spans several overlapping cells`() {
    val index = UniformGridSpatialIndex(cellSize = 50f)
    val id = UUID.randomUUID()
    index.insert(id, Bounds(0f, 0f, 200f, 200f))
    val hits = index.query(Bounds(0f, 0f, 200f, 200f))
    assertEquals(1, hits.count { it == id })
  }
}
