package br.com.anderson.techrace

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ImageView

/** Display the supplied brand artwork before opening the existing dashboard. */
class SplashActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val openDashboard = Runnable {
        if (!isFinishing && !isDestroyed) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val padding = (24 * resources.displayMetrics.density).toInt()
        setContentView(ImageView(this).apply {
            setBackgroundColor(Color.WHITE)
            setImageResource(R.drawable.techrace_logo)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(padding, padding, padding, padding)
            contentDescription = "TechRace Fuel Injection"
        })
    }

    override fun onResume() {
        super.onResume()
        handler.postDelayed(openDashboard, 1400L)
    }

    override fun onPause() {
        handler.removeCallbacks(openDashboard)
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
