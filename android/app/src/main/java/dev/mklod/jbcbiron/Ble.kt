package dev.mklod.jbcbiron

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "JbcBle"

private val SERVICE_UUID: UUID = UUID.fromString("2bbe5a4a-b9df-11ea-b3de-0242ac130004")
private val WRITE_UUID: UUID = UUID.fromString("2bbe5f90-b9df-11ea-b3de-0242ac130004")
private val NOTIFY_UUID: UUID = UUID.fromString("2bbe5c8e-b9df-11ea-b3de-0242ac130004")
private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

private const val NAME_PREFIX = "JBC_"
private const val POLL_MS = 500L          // 2 Hz — gentler on the radio than 4 Hz
private const val HISTORY_MS = 300_000L   // 5 min of tip-temp history
private const val PREFS = "jbc_known_irons"
private const val KEY_IRONS = "irons"

/**
 * Discovers JBC handles and keeps a PERSISTENT card per handle forever — a card
 * once seen never disappears; it just shows "offline" (last-known values) when
 * the link is down. Connections are scanner-driven: a handle is connected only
 * when we actually see it advertising (fast, autoConnect=false), with a per-iron
 * backoff so a flaky link can't trigger a reconnect storm.
 */
@SuppressLint("MissingPermission")
class BleManager(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)?.adapter
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // One record per KNOWN iron, keyed by address. Never removed.
    private val conns = ConcurrentHashMap<String, IronConn>()
    private val _state = MutableStateFlow<List<IronUi>>(emptyList())
    val state: StateFlow<List<IronUi>> = _state

    val bluetoothEnabled: Boolean get() = adapter?.isEnabled == true

    @Volatile private var scanning = false

    init {
        // Pre-create a card for every iron we've ever seen, so both cards show
        // immediately (offline) on launch — before scanning finds anything.
        loadKnown().forEach { (addr, name) ->
            val dev = try { adapter?.getRemoteDevice(addr) } catch (e: Exception) { null } ?: return@forEach
            conns[addr] = IronConn(context, scope, dev, name) { publish() }
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val addr = result.device.address
            val existing = conns[addr]
            if (existing != null) {
                // Known iron re-appeared (matched by address filter — the name may
                // be absent in a filtered result, so don't gate on it). Reconnect
                // if its link is down.
                existing.ensureConnected()
                return
            }
            // New device: confirm it's a JBC handle by advertised name.
            val name = result.scanRecord?.deviceName ?: result.device.name ?: return
            if (!name.startsWith(NAME_PREFIX)) return
            val conn = IronConn(context, scope, result.device, name) { publish() }
            if (conns.putIfAbsent(addr, conn) == null) {
                Log.d(TAG, "new iron $name ($addr)")
                saveKnown(addr, name)
                conn.connect()
                publish()
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "scan failed: $errorCode")
        }
    }

    /**
     * Start scanning. Call from the Activity's onStart (screen-on / foreground):
     * Android suspends BLE scans on screen-off and does NOT auto-resume, so the
     * scan must be re-armed each time the app comes forward. Idempotent.
     */
    fun start() {
        publish()   // show every known iron's (offline) card immediately
        val scanner = adapter?.bluetoothLeScanner ?: return
        if (scanning) return
        scanning = true
        // Filtered scans survive/behave better than unfiltered ones. Match the
        // service UUID plus each known iron by address (covers handles that don't
        // advertise the service UUID). Fresh install (no known irons) → unfiltered
        // so a brand-new handle can still be discovered by name.
        val filters: List<ScanFilter>? = if (conns.isEmpty()) null else {
            val fs = mutableListOf<ScanFilter>()
            fs.add(ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build())
            for (addr in conns.keys) {
                try { fs.add(ScanFilter.Builder().setDeviceAddress(addr).build()) } catch (_: Exception) {}
            }
            fs
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        try {
            if (filters == null) scanner.startScan(scanCallback)
            else scanner.startScan(filters, settings, scanCallback)
        } catch (e: Exception) {
            Log.e(TAG, "startScan failed", e); scanning = false
        }
    }

    /**
     * Stop scanning and drop the links. Call from onStop (screen-off / background)
     * — this also frees the irons' battery (no 24/7 connections).
     */
    fun stop() {
        scanning = false
        try {
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: Exception) {
        }
        conns.values.forEach { it.close() }
        // conns records are kept in memory; prefs keep them across restarts.
    }

    private fun publish() {
        _state.value = conns.values
            .map { it.snapshot() }
            .sortedBy { it.name }
    }

    fun setTemp(id: String, c: Int) = conns[id]?.setTemp(c)
    fun powerOn(id: String) = conns[id]?.powerOn()
    fun powerOff(id: String) = conns[id]?.powerOff()

    // --- persistence of the known-iron set (addr \t name) ---
    private fun loadKnown(): List<Pair<String, String>> =
        prefs.getStringSet(KEY_IRONS, emptySet()).orEmpty().mapNotNull {
            val i = it.indexOf('\t')
            if (i <= 0) null else it.substring(0, i) to it.substring(i + 1)
        }

    private fun saveKnown(addr: String, name: String) {
        val set = prefs.getStringSet(KEY_IRONS, emptySet()).orEmpty().toMutableSet()
        set.removeAll { it.startsWith("$addr\t") }
        set.add("$addr\t$name")
        prefs.edit().putStringSet(KEY_IRONS, set).apply()
    }
}

/** One BLE connection to one iron: scanner-driven connect + serialized writes. */
@SuppressLint("MissingPermission")
private class IronConn(
    private val context: Context,
    private val scope: CoroutineScope,
    private val device: BluetoothDevice,
    val name: String,
    private val onChange: () -> Unit,
) {
    val addr: String = device.address

    @Volatile var connected = false
    @Volatile private var status: IronStatus? = null   // last-known; retained while offline
    private val history = ArrayDeque<Pair<Long, Int>>()

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var pollJob: Job? = null

    private val writeMutex = Mutex()
    @Volatile private var pendingWrite: CompletableDeferred<Boolean>? = null
    @Volatile private var lastConnectAt = 0L

    fun snapshot(): IronUi = synchronized(history) {
        IronUi(addr, name, connected, status, history.toList())
    }

    /**
     * Connect now (call only when the iron was just seen advertising).
     * autoConnect=false is fast; a 3 s backoff stops a flaky link from storming.
     * Idempotent while a gatt is live.
     */
    fun connect() {
        if (gatt != null) return
        val now = System.currentTimeMillis()
        if (now - lastConnectAt < 3000) return
        lastConnectAt = now
        gatt = device.connectGatt(context, /* autoConnect = */ false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    fun ensureConnected() = connect()

    fun close() {
        pollJob?.cancel()
        try { gatt?.disconnect(); gatt?.close() } catch (_: Exception) {}
        gatt = null
        connected = false
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, s: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connected = false
                pollJob?.cancel()
                try { g.close() } catch (_: Exception) {}
                gatt = null   // let the scanner reconnect on the next advertisement
                onChange()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, s: Int) {
            val svc = g.getService(SERVICE_UUID) ?: run { onChange(); return }
            writeChar = svc.getCharacteristic(WRITE_UUID)
            val notify = svc.getCharacteristic(NOTIFY_UUID) ?: run { onChange(); return }
            g.setCharacteristicNotification(notify, true)
            val cccd = notify.getDescriptor(CCCD_UUID)
            if (cccd != null) {
                @Suppress("DEPRECATION")
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                g.writeDescriptor(cccd)
            } else {
                startPolling()
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, s: Int) {
            connected = true
            onChange()
            startPolling()
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (c.uuid != NOTIFY_UUID) return
            val text = String(c.value ?: return).trim()
            if (!text.startsWith("E")) return
            val st = parseStatus(text) ?: return
            status = st
            if (st.currentC != null) {
                synchronized(history) {
                    val now = System.currentTimeMillis()
                    history.addLast(now to st.currentC)
                    while (history.isNotEmpty() && now - history.first().first > HISTORY_MS) {
                        history.removeFirst()
                    }
                }
            }
            onChange()
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, s: Int) {
            pendingWrite?.complete(s == BluetoothGatt.GATT_SUCCESS)
        }
    }

    private fun startPolling() {
        if (pollJob?.isActive == true) return
        connected = true
        pollJob = scope.launch {
            while (connected) {
                write("<E>")
                delay(POLL_MS)
            }
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun write(frame: String): Boolean = writeMutex.withLock {
        val g = gatt ?: return false
        val ch = writeChar ?: return false
        val def = CompletableDeferred<Boolean>()
        pendingWrite = def
        ch.value = frame.toByteArray()
        ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        if (!g.writeCharacteristic(ch)) {
            pendingWrite = null
            return false
        }
        val ok = withTimeoutOrNull(1500) { def.await() } ?: false
        pendingWrite = null
        ok
    }

    // Commands are fire-and-forget from the UI; they share the write queue.
    fun setTemp(c: Int) {
        val clamped = c.coerceIn(100, 450)
        scope.launch { write("<T$clamped>") }
    }

    fun powerOn() { scope.launch { write("<M>") } }
    fun powerOff() { scope.launch { write("<L>") } }
}
