package br.com.anderson.techrace

/** Geometry shared by drawing and hit testing; all bounds use the available viewport. */
data class DashboardBounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
    fun contains(x: Float, y: Float) = x >= left && x < right && y >= top && y < bottom
}

class DashboardLayout(val width: Float, val height: Float) {
    init { require(width.isFinite() && height.isFinite() && width > 0 && height > 0) }
    val landscape = width > height
    private val gap = 12f
    val header = DashboardBounds(gap, 10f, width - gap, if (landscape) 85f else 125f)
    val connection = DashboardBounds(gap, header.bottom + gap, width - gap,
        header.bottom + gap + if (landscape) 50f else 55f)
    val navigation = DashboardBounds(gap, height - gap - if (landscape) 84f else 128f,
        width - gap, height - gap)
    val statuses = DashboardBounds(gap, navigation.top - gap - if (landscape) 70f else 95f,
        width - gap, navigation.top - gap)
    private val bodyTop = connection.bottom + gap
    private val bodyBottom = statuses.top - gap
    private val bodyHeight = bodyBottom - bodyTop
    private val contentWidth = width - 2 * gap
    private val topHeight = if (landscape) bodyHeight * .64f else minOf(bodyHeight * .43f, contentWidth * .50f)
    val gauge = DashboardBounds(gap, bodyTop,
        gap + contentWidth * if (landscape) .37f else .56f, bodyTop + topHeight)
    private val metricRight = if (landscape) gap + contentWidth * .65f else width - gap
    val map = DashboardBounds(gauge.right + gap, bodyTop, metricRight, bodyTop + (topHeight - gap) / 2)
    val lambda = DashboardBounds(map.left, map.bottom + gap, metricRight, gauge.bottom)
    val cards: List<DashboardBounds> = if (landscape) {
        val left = metricRight + gap
        val cellWidth = (width - gap - left - gap) / 2
        val cellHeight = (topHeight - gap) / 2
        (0..3).map { i ->
            val x = left + (i % 2) * (cellWidth + gap)
            val y = bodyTop + (i / 2) * (cellHeight + gap)
            DashboardBounds(x, y, x + cellWidth, y + cellHeight)
        }
    } else {
        val cellWidth = (contentWidth - 3 * gap) / 4
        val top = gauge.bottom + gap
        val cardHeight = minOf(bodyHeight * .23f, contentWidth * .27f)
        (0..3).map { i ->
            val x = gap + i * (cellWidth + gap)
            DashboardBounds(x, top, x + cellWidth, top + cardHeight)
        }
    }
    val graph = DashboardBounds(gap, (if (landscape) gauge.bottom else cards[0].bottom) + gap,
        width - gap, bodyBottom)
    val navCells = (0..4).map { i ->
        val cellWidth = navigation.width / 5
        DashboardBounds(navigation.left + i * cellWidth, navigation.top,
            navigation.left + (i + 1) * cellWidth, navigation.bottom)
    }
    fun navIndexAt(x: Float, y: Float): Int? = navCells.indexOfFirst { it.contains(x, y) }.takeIf { it >= 0 }
}
