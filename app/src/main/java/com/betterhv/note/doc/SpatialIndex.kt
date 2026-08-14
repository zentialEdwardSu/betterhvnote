package com.betterhv.note.doc

import com.betterhv.note.ink.Bounds
import java.util.UUID
import kotlin.math.floor

/** Spec §31-32. */
interface SpatialIndex {
    fun insert(id: UUID, bounds: Bounds)
    fun remove(id: UUID, bounds: Bounds)
    fun update(id: UUID, oldBounds: Bounds, newBounds: Bounds)
    fun query(area: Bounds): List<UUID>
}

/**
 * Uniform-grid spatial index (spec §31 recommendation over an R-tree: simpler,
 * suits typical pen-stroke distribution, fast insert/delete, predictable cost).
 *
 * Cell size is a fixed constant rather than adaptive: there is no pan/zoom yet
 * (view space == page space), so a size tuned to typical stroke/eraser extents
 * is a reasonable default; revisit if/when page space diverges from view space.
 */
class UniformGridSpatialIndex(private val cellSize: Float = DEFAULT_CELL_SIZE) : SpatialIndex {
    private data class CellKey(val cx: Int, val cy: Int)

    private val cells = HashMap<CellKey, MutableSet<UUID>>()

    private fun cellOf(x: Float, y: Float): CellKey =
        CellKey(floor(x / cellSize).toInt(), floor(y / cellSize).toInt())

    private fun cellRange(bounds: Bounds): Sequence<CellKey> {
        val minCell = cellOf(bounds.left, bounds.top)
        val maxCell = cellOf(bounds.right, bounds.bottom)
        return sequence {
            for (cx in minCell.cx..maxCell.cx) {
                for (cy in minCell.cy..maxCell.cy) {
                    yield(CellKey(cx, cy))
                }
            }
        }
    }

    override fun insert(id: UUID, bounds: Bounds) {
        for (key in cellRange(bounds)) {
            cells.getOrPut(key) { HashSet() }.add(id)
        }
    }

    override fun remove(id: UUID, bounds: Bounds) {
        for (key in cellRange(bounds)) {
            val set = cells[key] ?: continue
            set.remove(id)
            if (set.isEmpty()) cells.remove(key)
        }
    }

    override fun update(id: UUID, oldBounds: Bounds, newBounds: Bounds) {
        remove(id, oldBounds)
        insert(id, newBounds)
    }

    override fun query(area: Bounds): List<UUID> {
        val result = LinkedHashSet<UUID>()
        for (key in cellRange(area)) {
            cells[key]?.let { result.addAll(it) }
        }
        return result.toList()
    }

    fun clearAll() {
        cells.clear()
    }

    companion object {
        private const val DEFAULT_CELL_SIZE = 512.0f
    }
}
