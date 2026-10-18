package br.com.anderson.techrace

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.min

class DashboardLayoutTest {
    private val screens = listOf(800 to 480, 1024 to 600, 1280 to 720, 1280 to 800,
        1920 to 720, 1920 to 1080, 768 to 1024, 800 to 1280, 1080 to 2160, 360 to 800)

    private fun layout(w: Int, h: Int): DashboardLayout {
        val wide = w > h
        val scale = min(w / (if (wide) 1536f else 1024f), h / (if (wide) 1024f else 1536f))
        return DashboardLayout(w / scale, h / scale)
    }

    @Test fun panelsFitWithoutOverlapAcrossHeadUnitsPhonesAndTablets() {
        for ((w, h) in screens) {
            val g = layout(w, h)
            val panels = listOf(g.header, g.connection, g.gauge, g.map, g.lambda, g.graph, g.statuses, g.navigation) + g.cards
            panels.forEach { b ->
                assertTrue("$w x $h: $b", b.width > 0 && b.height > 0)
                assertTrue(b.left >= 0 && b.top >= 0 && b.right <= g.width && b.bottom <= g.height)
            }
            for (i in panels.indices) for (j in i + 1 until panels.size) {
                val a = panels[i]; val b = panels[j]
                assertFalse("$w x $h: overlapping $a and $b",
                    a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom)
            }
            assertTrue("Graph must retain useful height", g.graph.height >= 150)
        }
    }

    @Test fun horizontalSpaceIsUsedOnSixteenByNineAndUltrawideDisplays() {
        for ((w, h) in screens) {
            val g = layout(w, h)
            assertEquals(g.width - 12, g.header.right, .01f)
            assertEquals(g.width - 12, g.graph.right, .01f)
            assertEquals(g.width - 12, g.navigation.right, .01f)
            assertEquals(g.height - 12, g.navigation.bottom, .01f)
        }
    }

    @Test fun touchTargetsFollowAllFiveVisibleMenuCellsAfterResize() {
        for ((w, h) in screens) {
            val g = layout(w, h)
            g.navCells.forEachIndexed { i, cell ->
                assertEquals(i, g.navIndexAt((cell.left + cell.right) / 2, (cell.top + cell.bottom) / 2))
            }
            assertNull(g.navIndexAt(-1f, g.navigation.top + 10))
            assertNull(g.navIndexAt(g.width, g.navigation.top + 10))
            assertNull(g.navIndexAt(g.width / 2, g.statuses.top + 10))
        }
    }
}
