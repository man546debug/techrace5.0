package br.com.anderson.techrace

import android.app.Activity
import android.os.Build
import android.view.View
import android.view.WindowInsets
import android.widget.FrameLayout

/** Resize custom canvases inside the usable area when modern Android enforces edge-to-edge. */
internal fun Activity.setSafeContentView(content: View, backgroundColor: Int) {
    if (Build.VERSION.SDK_INT < 35) {
        setContentView(content)
        return
    }
    val container = FrameLayout(this).apply {
        setBackgroundColor(backgroundColor)
        addView(content, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        setOnApplyWindowInsetsListener { view, insets ->
            val safe = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            WindowInsets.CONSUMED
        }
    }
    setContentView(container)
    container.requestApplyInsets()
}
