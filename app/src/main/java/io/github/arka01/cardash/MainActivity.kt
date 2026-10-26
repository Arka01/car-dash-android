package io.github.arka01.cardash

import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
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
import androidx.core.content.FileProvider
import io.github.arka01.cardash.obd.Adapters
import io.github.arka01.cardash.obd.SavedAdapter
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
            keyTest = ::keyTest,
            shareTest = ::shareTest,
            markKey = { Dash.markKey(it) },
            shareDrive = ::shareDrive,
        )
        setContent { TerminalApp(adapterName, actions) }

        if (!Adapters.hasPermission(this)) askPermission()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        adapterName = Adapters.chosen(this)?.name   // paired devices are only visible with the permission
    }

    private fun connect() {
        val adapter = readyAdapter() ?: return
        Dash.startLive(this, adapter)
    }

    private fun keyTest() {
        val adapter = readyAdapter() ?: return
        Dash.startKeyTest(this, adapter)
    }

    /** The chosen adapter, or null after asking for the permission / a choice first. */
    private fun readyAdapter(): SavedAdapter? {
        if (!Adapters.hasPermission(this)) {
            askPermission()
            return null
        }
        val adapter = Adapters.chosen(this)
        if (adapter == null) pickAdapter()
        return adapter
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

    /** Sends the newest KEY TEST file (CSV) as an attachment, e.g. by email. */
    private fun shareTest() {
        val file = Dash.latestKeyTest(this)
        if (file == null) {
            Toast.makeText(this, "No key test recorded yet", Toast.LENGTH_LONG).show()
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_SUBJECT, "Car Dash key test ${file.name}")
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "Share key test"))
    }

    /** Sends the newest drive recording (CSV + profile JSON) as attachments. */
    private fun shareDrive() {
        val files = Dash.latestDrive(this)
        if (files.isEmpty()) {
            Toast.makeText(this, "No drive recorded yet", Toast.LENGTH_LONG).show()
            return
        }
        val uris = ArrayList(files.map { FileProvider.getUriForFile(this, "$packageName.files", it) })
        val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putExtra(Intent.EXTRA_SUBJECT, "Car Dash drive ${files.first().name}")
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            clipData = ClipData.newRawUri(files.first().name, uris.first()).also { clip ->
                uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "Share drive"))
    }

    private fun askPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 1)
        }
    }
}
