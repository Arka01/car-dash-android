package io.github.arka01.cardash

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.arka01.cardash.obd.Adapters
import io.github.arka01.cardash.ui.Actions
import io.github.arka01.cardash.ui.TerminalApp

/** Phone screen. The look is in ui/Terminal.kt; this class handles Bluetooth permission, adapter and sharing. */
class MainActivity : ComponentActivity() {
    private var adapterName by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val black = android.graphics.Color.BLACK
        enableEdgeToEdge(SystemBarStyle.dark(black), SystemBarStyle.dark(black))

        adapterName = Adapters.chosen(this)?.name
        val actions = Actions(
            connect = ::connect,
            demo = { Dash.startDemo(this) },
            stop = { Dash.stop() },
            pickAdapter = ::pickAdapter,
            share = ::share,
        )
        setContent { TerminalApp(adapterName, actions) }

        if (!Adapters.hasPermission(this)) askPermission()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        adapterName = Adapters.chosen(this)?.name   // paired devices are only visible with the permission
    }

    private fun connect() {
        if (!Adapters.hasPermission(this)) {
            askPermission()
            return
        }
        val adapter = Adapters.chosen(this)
        if (adapter == null) {
            pickAdapter()
            return
        }
        Dash.startLive(this, adapter)
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
            .setItems(paired.map { it.name }.toTypedArray()) { _, i ->
                Adapters.save(this, paired[i])
                adapterName = paired[i].name
            }
            .show()
    }

    private fun share() {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Car Dash ${BuildConfig.VERSION_NAME}")
            putExtra(Intent.EXTRA_TEXT, Report.full(Dash.state.value))
        }
        startActivity(Intent.createChooser(send, "Share readings"))
    }

    private fun askPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 1)
        }
    }
}
