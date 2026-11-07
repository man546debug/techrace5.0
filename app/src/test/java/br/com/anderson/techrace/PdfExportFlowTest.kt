package br.com.anderson.techrace

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Looper
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
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
            val method = MainActivity::class.java.getDeclaredMethod("preparePdfExport", List::class.java, Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }
            val samples = listOf(TelemetrySample(1793534400000L, false, "2.0", 980.0, 4.25, 31.25, .842, 800.0, 84.5))
            method.invoke(activity, samples, true)
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
}
