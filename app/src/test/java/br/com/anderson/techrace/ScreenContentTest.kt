package br.com.anderson.techrace

import android.graphics.Insets
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35, 36])
class ScreenContentTest {
    @Test fun dashboardFitsBetweenSystemBarsAndUpdatesAfterInsetsChange() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            assertEquals(36, activity.applicationInfo.targetSdkVersion)
            val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as ViewGroup
            val dashboard = root.getChildAt(0)
            assertTrue(dashboard is DashboardView)
            fun layout() {
                root.measure(View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, 800, 480)
            }
            root.dispatchApplyWindowInsets(WindowInsets.Builder()
                .setInsets(WindowInsets.Type.systemBars(), Insets.of(0, 24, 0, 48))
                .setInsets(WindowInsets.Type.displayCutout(), Insets.of(30, 0, 0, 0)).build())
            layout()
            assertEquals(30, dashboard.left)
            assertEquals(24, dashboard.top)
            assertEquals(800, dashboard.right)
            assertEquals(432, dashboard.bottom)
            root.dispatchApplyWindowInsets(WindowInsets.Builder()
                .setInsets(WindowInsets.Type.systemBars(), Insets.NONE)
                .setInsets(WindowInsets.Type.displayCutout(), Insets.NONE).build())
            layout()
            assertEquals(0, dashboard.left)
            assertEquals(0, dashboard.top)
            assertEquals(800, dashboard.width)
            assertEquals(480, dashboard.height)
        } finally { controller.pause().stop().destroy() }
    }
}
