package io.github.arka01.cardash.obd

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

data class SavedAdapter(val name: String, val address: String)

/** Which paired Bluetooth device is the OBD adapter; remembered between app starts. */
object Adapters {
    private const val PREFS = "adapter"
    private val obdNames = Regex("vlinker|obd|elm|vgate", RegexOption.IGNORE_CASE)

    /** Android 12+ needs the "Nearby devices" permission to use Bluetooth. */
    fun hasPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    /** Devices paired in Android's Bluetooth settings. */
    @SuppressLint("MissingPermission")
    fun paired(context: Context): List<SavedAdapter> {
        if (!hasPermission(context)) return emptyList()
        val bluetooth = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return emptyList()
        return bluetooth.bondedDevices.orEmpty().map { SavedAdapter(it.name ?: it.address, it.address) }
    }

    /** The saved choice, else the first paired device whose name looks like an OBD adapter. */
    fun chosen(context: Context): SavedAdapter? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val address = prefs.getString("address", null)
        if (address != null) return SavedAdapter(prefs.getString("name", address)!!, address)
        return paired(context).firstOrNull { obdNames.containsMatchIn(it.name) }
    }

    fun save(context: Context, adapter: SavedAdapter) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("name", adapter.name)
            .putString("address", adapter.address)
            .apply()
    }
}
