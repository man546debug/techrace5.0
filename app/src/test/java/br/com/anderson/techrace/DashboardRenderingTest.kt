package br.com.anderson.techrace

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.MotionEvent
import android.view.View
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.min

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DashboardRenderingTest {
    @Test fun renderHeadUnitTabletAndPhoneAndVerifyMenuTaps() {
        val sizes = listOf(800 to 480, 1024 to 600, 1280 to 720, 1280 to 800,
            1920 to 720, 768 to 1024, 1080 to 2160)
        // Reuse the same View across orientation/resolution changes, as MainActivity does.
        val view = DashboardView(RuntimeEnvironment.getApplication()).apply {
                connected = true
                connectionLabel = "BLUETOOTH"
                updateData(TechRaceLiveData(5999.0, 12.34, 99.9, 0, 0, 100, 2.49, 4999))
                markCommunicationFailure()
        }
        for ((w, h) in sizes) {
            view.setSelectedNav(DashboardView.Nav.MONITOR)
            view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, w, h)
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val output = File("build/dashboard-previews/dashboard-${w}x$h.png")
            output.parentFile?.mkdirs()
            output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            // Panels reach both sides instead of leaving the old letterbox bands.
            val wide = w > h
            val scale = min(w / (if (wide) 1536f else 1024f), h / (if (wide) 1024f else 1536f))
            val geometry = DashboardLayout(w / scale, h / scale)
            val y = (geometry.graph.top * scale + 25 * scale).toInt()
            assertNotEquals(bitmap.getPixel(0, y), bitmap.getPixel((20 * scale).toInt(), y))
            assertNotEquals(bitmap.getPixel(w - 1, y), bitmap.getPixel(w - (20 * scale).toInt(), y))
            var tapped: DashboardView.Nav? = null
            view.onNavClick = { tapped = it }
            geometry.navCells.forEachIndexed { i, b ->
                val event = MotionEvent.obtain(0, 0, MotionEvent.ACTION_UP,
                    (b.left + b.right) / 2 * scale, (b.top + b.bottom) / 2 * scale, 0)
                view.onTouchEvent(event)
                event.recycle()
                assertEquals(DashboardView.Nav.entries[i], tapped)
            }
            bitmap.recycle()
        }
    }
}
