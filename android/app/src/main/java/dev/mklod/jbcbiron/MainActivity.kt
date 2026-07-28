package dev.mklod.jbcbiron

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mklod.jbcbiron.ui.App
import dev.mklod.jbcbiron.ui.JbcTheme

class MainActivity : ComponentActivity() {

    private lateinit var ble: BleManager
    @Volatile private var permsReady = false

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result.values.all { it }) {
                permsReady = true
                ensureBluetoothThenScan()
            }
        }

    private val enableBtLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            startScanIfReady()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ble = BleManager(applicationContext)

        setContent {
            JbcTheme {
                val irons by ble.state.collectAsStateWithLifecycle()
                App(
                    irons = irons,
                    bluetoothOn = ble.bluetoothEnabled,
                    onSetTemp = { id, c -> ble.setTemp(id, c) },
                    onPower = { id, on -> if (on) ble.powerOn(id) else ble.powerOff(id) },
                )
            }
        }

        val missing = requiredPerms().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) permsReady = true
        else permLauncher.launch(missing.toTypedArray())
        // Actual scanning is driven by onStart (below).
    }

    // Re-arm scanning every time the app comes to the foreground / screen-on —
    // Android suspends BLE scans on screen-off and never auto-resumes.
    override fun onStart() {
        super.onStart()
        ensureBluetoothThenScan()
    }

    // Stop scanning + drop links when backgrounded — fixes the "won't reconnect"
    // bug's other half and saves the irons' battery.
    override fun onStop() {
        super.onStop()
        ble.stop()
    }

    private fun requiredPerms(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private fun ensureBluetoothThenScan() {
        if (!permsReady) return
        if (ble.bluetoothEnabled) startScanIfReady()
        else enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
    }

    private fun startScanIfReady() {
        if (permsReady && ble.bluetoothEnabled) ble.start()
    }
}
