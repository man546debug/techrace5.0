package br.com.anderson.techrace

import android.app.AlertDialog
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class InitialMixtureEditorTest {
    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    private fun openEditor(activity: MainActivity): AlertDialog {
        MainActivity::class.java.getDeclaredMethod("showModuleAdjustmentEditor", ModuleSettings::class.java,
            Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType).apply { isAccessible = true }
            .invoke(activity, ModuleSettings.editableDefaults(1.0), false, false)
        shadowOf(Looper.getMainLooper()).idle()
        return ShadowAlertDialog.getLatestAlertDialog()
    }

    private fun mixtureInput(dialog: AlertDialog): EditText {
        val label = descendants(dialog.window!!.decorView).filterIsInstance<TextView>()
            .single { it.text.toString() == "Mistura inicial (%)" }
        return descendants(label.parent as View).filterIsInstance<EditText>().single()
    }

    @Test fun lowValuesAreSavedWithTheProtocolResolutionAndPreservedWhenMerged() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        try {
            for ((requested, raw) in listOf("0" to 0, "2" to 1, "3" to 2, "3.125" to 2)) {
                val editor = openEditor(activity)
                mixtureInput(editor).setText(requested)
                val views = descendants(editor.window!!.decorView)
                assertTrue(views.filterIsInstance<TextView>().any {
                    it.text.toString().startsWith("Valor a enviar:") && it.text.contains("teste de compatibilidade")
                })
                views.filterIsInstance<Button>().single { it.text.toString() == "Salvar ajustes no celular" }.performClick()
                assertFalse("Low initial mixture must pass the actual editor validation", editor.isShowing)
                val saved = activity.getSharedPreferences("techrace_settings", 0)
                    .getString("ecu_settings_draft", null)!!
                assertEquals(raw, saved.split(" ")[14].toInt(16))
                val draft = saved.split(" ").map { it.toInt(16).toByte() }.toByteArray()
                val merged = ModuleSettings.mergeEditableDraft(ModuleSettings.editableDefaults(1.0).raw, draft)
                assertEquals(raw, merged[14].toInt() and 255)
            }
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun negativeAndAboveLimitValuesRemainBlocked() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        try {
            for (invalid in listOf("-1", "40")) {
                val editor = openEditor(activity)
                mixtureInput(editor).setText(invalid)
                descendants(editor.window!!.decorView).filterIsInstance<Button>()
                    .single { it.text.toString() == "Salvar ajustes no celular" }.performClick()
                assertTrue(editor.isShowing)
                assertEquals("Mistura inicial deve ficar entre 0% e a correção máxima", ShadowToast.getTextOfLatestToast())
                assertNull(activity.getSharedPreferences("techrace_settings", 0).getString("ecu_settings_draft", null))
                editor.dismiss()
            }
        } finally { controller.pause().stop().destroy() }
    }
}
