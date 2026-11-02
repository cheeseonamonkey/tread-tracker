package dev.treadtracker.app.widget

import org.junit.Assert.*
import org.junit.Test

class WidgetSizingTest {
    @Test fun oneCellUsesTinyLayout() {
        assertEquals(WidgetSizing.Layout.TINY, WidgetSizing.layout(65f, 60f))
        assertEquals(WidgetSizing.Layout.TINY, WidgetSizing.layout(80f, 250f))
    }

    @Test fun shortRowsUseCompactLayout() {
        assertEquals(WidgetSizing.Layout.COMPACT, WidgetSizing.layout(220f, 70f))
        assertEquals(WidgetSizing.Layout.COMPACT, WidgetSizing.layout(130f, 120f))
    }

    @Test fun twoCellAndLargerUsesRegularLayout() {
        assertEquals(WidgetSizing.Layout.REGULAR, WidgetSizing.layout(160f, 110f))
        assertEquals(WidgetSizing.Layout.REGULAR, WidgetSizing.layout(500f, 500f))
    }

    @Test fun longValuesShrinkToFit() {
        val small = WidgetSizing.textSize(65f, WidgetSizing.Layout.TINY, 6)
        val large = WidgetSizing.textSize(65f, WidgetSizing.Layout.TINY, 2)
        assertTrue(small < large)
        assertTrue(small >= 11f)
        assertTrue(WidgetSizing.textSize(500f, WidgetSizing.Layout.REGULAR, 9) <= 48f)
    }
}
