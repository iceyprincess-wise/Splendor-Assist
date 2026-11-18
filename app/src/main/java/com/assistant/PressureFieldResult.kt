package com.assistant






































import com.assistant.runtime.*


























import kotlin.math.*




























data class PressureFieldResult(
    val columns: Int,
    val rows: Int,
    val pressure: Array<FloatArray>
) {
    // No68-I-D24: pressure of the cell containing (x, y) in frame
    // coordinates, or 0 when the point or the grid is invalid. Used by
    // PressingRecognitionEngine to judge the pressure AT THE BALL instead
    // of the (inverted) mean of the whole normalised grid.
    fun cellPressureAt(x: Float, y: Float, frameWidth: Float, frameHeight: Float): Float {
        if (columns <= 0 || rows <= 0 || frameWidth <= 0f || frameHeight <= 0f) return 0f
        if (!x.isFinite() || !y.isFinite()) return 0f
        if (pressure.size != rows) return 0f
        val col = ((x / frameWidth) * columns).toInt().coerceIn(0, columns - 1)
        val row = ((y / frameHeight) * rows).toInt().coerceIn(0, rows - 1)
        val r = pressure.getOrNull(row) ?: return 0f
        if (r.size != columns) return 0f
        val v = r.getOrNull(col) ?: return 0f
        return if (v.isFinite()) v.coerceIn(0f, 1f) else 0f
    }

    // No68-I-D26: a data class holding Array<FloatArray> gets REFERENTIAL
    // equals/hashCode from the array — two identical fields never compare
    // equal. Content-based overrides restore the data-class contract.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PressureFieldResult) return false
        return columns == other.columns && rows == other.rows &&
            pressure.contentDeepEquals(other.pressure)
    }

    override fun hashCode(): Int {
        var result = columns
        result = 31 * result + rows
        result = 31 * result + pressure.contentDeepHashCode()
        return result
    }
}

/* ========
ReceiverEngagementEngine
======== */
