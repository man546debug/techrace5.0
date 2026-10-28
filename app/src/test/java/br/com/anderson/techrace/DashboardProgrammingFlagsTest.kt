package br.com.anderson.techrace

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DashboardProgrammingFlagsTest {
    @Test fun unknownFlagsAreNeutralAndOnlyConfirmedFlagsAreGreen() {
        val view = DashboardView(RuntimeEnvironment.getApplication())
        val green = Color.rgb(75, 210, 48)
        val red = Color.rgb(255, 48, 48)
        val muted = Color.rgb(158, 164, 172)
        fun count(color: Int): Int {
            view.measure(View.MeasureSpec.makeMeasureSpec(1536, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1024, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, 1536, 1024)
            val bitmap = Bitmap.createBitmap(1536, 1024, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val bounds = DashboardLayout(1536f, 1024f).statuses
            var found = 0
            // Only the MAP/RPM values; exclude communication/error indicators and captions.
            for (y in (bounds.top + bounds.height * .5f).toInt() until bounds.bottom.toInt()) {
                for (x in bounds.left.toInt() until (bounds.left + bounds.width / 2).toInt()) {
                    if (bitmap.getPixel(x, y) == color) found++
                }
            }
            bitmap.recycle()
            return found
        }
        assertEquals(0, count(green))
        assertEquals(0, count(red))
        assertTrue(count(muted) > 0)
        view.updateProgrammingFlags(true, true)
        assertTrue(count(green) > 0)
        view.markProgrammingFlagsUnknown()
        assertEquals(0, count(green))
        assertEquals(0, count(red))
        view.updateProgrammingFlags(false, false)
        assertTrue(count(red) > 0)
        view.clearDemoData()
        assertEquals(0, count(red))
    }
}
