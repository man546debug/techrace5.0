package br.com.anderson.techrace

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.TextView
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PdfExportFlowTest {
    @Test fun pdfPickerUsesPdfMimeAndBinarySavePreservesTheGeneratedDocument() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        try {
            @Suppress("UNCHECKED_CAST")
            val history = MainActivity::class.java.getDeclaredField("csvRows").apply { isAccessible = true }
                .get(activity) as java.util.ArrayDeque<String>
            history.add("1793534400000;REAL;2.0;1;980;4.25;31.25;0.842;800;84.5;12;3;100")
            MainActivity::class.java.getDeclaredMethod("showConfig").apply { isAccessible = true }.invoke(activity)
            val configuration = ShadowAlertDialog.getLatestAlertDialog()
            val button = configuration.findViewById<Button>(R.id.export_pdf_button)
            assertNotNull(button)
            assertTrue(button.isEnabled)
            assertEquals(View.VISIBLE, button.visibility)
            assertTrue(button.text.toString().contains("1 leitura"))
            val decor = configuration.window!!.decorView
            decor.measure(View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.AT_MOST))
            decor.layout(0, 0, decor.measuredWidth, decor.measuredHeight)
            val bounds = Rect()
            button.getDrawingRect(bounds)
            (decor as android.view.ViewGroup).offsetDescendantRectToMyCoords(button, bounds)
            assertTrue("PDF button must fit the head-unit dialog", bounds.top >= 0 && bounds.bottom <= decor.measuredHeight)
            // Render the fixed header; Robolectric does not attach the dialog ListView's scroll indicators.
            val fixedHeader = button.parent as android.view.ViewGroup
            val image = Bitmap.createBitmap(fixedHeader.width, fixedHeader.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(image)
            canvas.drawColor(Color.rgb(18, 18, 18))
            fixedHeader.draw(canvas)
            val preview = File("build/dashboard-previews/settings-pdf-header-800x480.png")
            preview.parentFile?.mkdirs()
            preview.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
            button.performClick()
            val formats = ShadowAlertDialog.getLatestAlertDialog()
            assertEquals("Exportar histórico PDF", shadowOf(formats).title.toString())
            formats.listView.performItemClick(formats.listView.getChildAt(1), 1, 1L)
            val executor = MainActivity::class.java.getDeclaredField("exportExecutor").apply { isAccessible = true }
                .get(activity) as ExecutorService
            executor.submit {}.get(30, TimeUnit.SECONDS)
            shadowOf(Looper.getMainLooper()).idle()
            val picker = shadowOf(activity).nextStartedActivityForResult
            assertNotNull(picker)
            assertEquals(Intent.ACTION_CREATE_DOCUMENT, picker.intent.action)
            assertEquals("application/pdf", picker.intent.type)
            assertTrue(picker.intent.getStringExtra(Intent.EXTRA_TITLE)!!.endsWith(".pdf"))
            val content = MainActivity::class.java.getDeclaredField("pendingExport").apply { isAccessible = true }
                .get(activity) as ByteArray
            assertTrue(content.toString(Charsets.US_ASCII).startsWith("%PDF-1.4"))
            val destination = File(activity.cacheDir, "pdf-export-flow.pdf")
            val callback = MainActivity::class.java.getDeclaredMethod("onActivityResult", Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType, Intent::class.java).apply { isAccessible = true }
            callback.invoke(activity, picker.requestCode, Activity.RESULT_OK, Intent().setData(Uri.fromFile(destination)))
            executor.submit {}.get(30, TimeUnit.SECONDS)
            shadowOf(Looper.getMainLooper()).idle()
            assertArrayEquals(content, destination.readBytes())
        } finally {
            controller.pause().stop().destroy()
        }
    }
    @Test fun fixedPdfButtonIsEnabledWithoutDataAndExplainsHowToCollectReadings() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        try {
            MainActivity::class.java.getDeclaredMethod("showConfig").apply { isAccessible = true }.invoke(activity)
            val configuration = ShadowAlertDialog.getLatestAlertDialog()
            val button = configuration.findViewById<Button>(R.id.export_pdf_button)
            assertNotNull(button)
            assertTrue(button.isEnabled)
            assertEquals(View.VISIBLE, button.visibility)
            assertTrue(button.text.toString().contains("0 leituras"))
            button.performClick()
            val explanation = ShadowAlertDialog.getLatestAlertDialog()
            assertEquals("Nenhuma leitura para exportar", shadowOf(explanation).title.toString())
            assertTrue(explanation.findViewById<TextView>(android.R.id.message).text.toString().contains("Conecte o módulo"))
            assertNull(shadowOf(activity).nextStartedActivityForResult)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun installedVersionMatchesAppNameSettingsAndAbout() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        try {
            val packageInfo = activity.packageManager.getPackageInfo(activity.packageName, 0)
            assertEquals(BuildConfig.VERSION_NAME, packageInfo.versionName)
            assertEquals(BuildConfig.VERSION_CODE, packageInfo.versionCode)
            assertEquals("TechRace Flex ECU ${BuildConfig.VERSION_NAME}", activity.getString(R.string.app_name))
            assertEquals(activity.getString(R.string.app_name), activity.applicationInfo.loadLabel(activity.packageManager).toString())
            MainActivity::class.java.getDeclaredMethod("showConfig").apply { isAccessible = true }.invoke(activity)
            val configuration = ShadowAlertDialog.getLatestAlertDialog()
            val button = configuration.findViewById<Button>(R.id.export_pdf_button)
            val title = (button.parent as android.view.ViewGroup).getChildAt(0) as TextView
            assertEquals("Configurações • ${BuildConfig.VERSION_NAME}", title.text.toString())
            configuration.listView.performItemClick(configuration.listView.getChildAt(6), 6, 6L)
            assertEquals("TechRace ${BuildConfig.VERSION_NAME}", shadowOf(ShadowAlertDialog.getLatestAlertDialog()).title.toString())
        } finally {
            controller.pause().stop().destroy()
        }
    }

}
