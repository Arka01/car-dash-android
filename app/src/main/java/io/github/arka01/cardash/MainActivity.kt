package io.github.arka01.cardash

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import io.github.arka01.cardash.obd.Adapters
import io.github.arka01.cardash.obd.ObdSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Phone screen: pick the adapter, connect, and see every sensor as plain text. */
class MainActivity : Activity() {
    private val ui = MainScope()
    private var refresh: Job? = null
    private lateinit var output: TextView
    private lateinit var adapterButton: Button
    private lateinit var connectButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        adapterButton = Button(this).apply { setOnClickListener { pickAdapter() } }
        connectButton = Button(this).apply { setOnClickListener { toggleConnection() } }
        val shareButton = Button(this).apply {
            text = "Share"
            setOnClickListener { share() }
        }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(adapterButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f))
            addView(connectButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(shareButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        output = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextColor(Color.rgb(0x33, 0xFF, 0x66))
        }
        val scroll = ScrollView(this).apply { addView(output) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            addView(buttons)
            addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        keepClearOfSystemBars(root)
        setContentView(root)

        if (!Adapters.hasPermission(this)) askPermission()
    }

    override fun onStart() {
        super.onStart()
        refresh = ui.launch {
            while (isActive) {
                render()
                delay(250)
            }
        }
    }

    override fun onStop() {
        refresh?.cancel()
        super.onStop()
    }

    override fun onDestroy() {
        ui.cancel()   // only the screen; the connection keeps running for the car screen
        super.onDestroy()
    }

    private fun render() {
        val adapter = Adapters.chosen(this)
        adapterButton.text = "Adapter: ${adapter?.name ?: "choose"}"
        connectButton.text = if (ObdSession.isRunning) "Stop" else "Connect"
        output.text = Report.full(ObdSession.state.value)
    }

    private fun toggleConnection() {
        if (ObdSession.isRunning) {
            ObdSession.stop()
            return
        }
        if (!Adapters.hasPermission(this)) {
            askPermission()
            return
        }
        val adapter = Adapters.chosen(this)
        if (adapter == null) {
            pickAdapter()
            return
        }
        ObdSession.start(this, adapter)
    }

    private fun pickAdapter() {
        if (!Adapters.hasPermission(this)) {
            askPermission()
            return
        }
        val paired = Adapters.paired(this)
        if (paired.isEmpty()) {
            Toast.makeText(this, "Pair the vLinker in Android's Bluetooth settings first", Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Which device is the OBD adapter?")
            .setItems(paired.map { it.name }.toTypedArray()) { _, i -> Adapters.save(this, paired[i]) }
            .show()
    }

    private fun share() {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Car Dash ${BuildConfig.VERSION_NAME}")
            putExtra(Intent.EXTRA_TEXT, Report.full(ObdSession.state.value))
        }
        startActivity(Intent.createChooser(send, "Share readings"))
    }

    private fun askPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 1)
        }
    }

    /** Android 15+ draws apps under the status and navigation bars; pad the content away from them. */
    private fun keepClearOfSystemBars(root: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            root.setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        } else {
            root.fitsSystemWindows = true
        }
    }
}
