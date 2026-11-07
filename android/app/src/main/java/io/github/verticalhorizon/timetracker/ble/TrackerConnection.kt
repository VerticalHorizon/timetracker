package io.github.verticalhorizon.timetracker.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

/** A tracker found by [TrackerConnection.scan]. */
data class FoundTracker(val address: String, val name: String, val rssi: Int)

/** The device answered but doesn't speak this app's protocol. */
class IncompatibleTrackerException : IOException("Not a TimeTracker, or an incompatible firmware")

/**
 * Keeps a GATT connection to one tracker while the app is in the foreground: connects,
 * reconnects, feeds [state] with snapshots and sends commands.
 *
 * GATT callbacks arrive on one thread, in order, and [state] is only set there, so a
 * read result can never overtake a newer notification.
 */
@SuppressLint("MissingPermission") // Bluetooth is only touched after readiness() returned Ready
class TrackerConnection(context: Context) {

    enum class Phase { Idle, NoPermission, BluetoothOff, Connecting, Connected, Incompatible }
    enum class Readiness { Ready, NoPermission, BluetoothOff }

    private val context = context.applicationContext
    private val adapter: BluetoothAdapter? =
        this.context.getSystemService(BluetoothManager::class.java)?.adapter

    private val _phase = MutableStateFlow(Phase.Idle)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    private val _state = MutableStateFlow<TrackerState?>(null)
    /** The latest snapshot, null while not connected. */
    val state: StateFlow<TrackerState?> = _state.asStateFlow()

    private val callbackThread = HandlerThread("tracker-gatt").apply { start() }
    private val callbackHandler = Handler(callbackThread.looper)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var loop: Job? = null
    private var loopAddress: String? = null
    @Volatile private var session: Session? = null
    private val commandLock = Mutex()

    fun readiness(): Readiness = when {
        !hasPermissions(context) -> Readiness.NoPermission
        adapter?.isEnabled != true -> Readiness.BluetoothOff
        else -> Readiness.Ready
    }

    /** Stays connected to [address], reconnecting as needed, until [stop]. */
    fun start(address: String) {
        if (loop?.isActive == true && loopAddress == address) return
        stop()
        loopAddress = address
        loop = scope.launch { run(address) }
    }

    fun stop() {
        loop?.cancel()
        loop = null
        loopAddress = null
        session?.close()
        _state.value = null
        _phase.value = Phase.Idle
    }

    /** Reads a fresh snapshot. */
    suspend fun refresh(): TrackerState {
        val s = session ?: throw IOException("Not connected")
        return s.read() ?: throw IncompatibleTrackerException()
    }

    /** Sets tracker [slot]'s shown time to zero; returns the snapshot acknowledging it. */
    suspend fun resetTracker(slot: Int): TrackerState =
        command { seq -> TrackerProtocol.resetTracker(seq, slot) }

    /** Sets countdown [index] to [seconds]; returns the snapshot acknowledging it. */
    suspend fun setCountdown(index: Int, seconds: Long): TrackerState =
        command { seq -> TrackerProtocol.setCountdown(seq, index, seconds) }

    /** Trackers advertising the TimeTracker service, for as long as the flow is collected. */
    fun scan(): Flow<FoundTracker> = callbackFlow {
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            close(IOException("Bluetooth is off"))
            return@callbackFlow
        }
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.scanRecord?.deviceName ?: "TimeTracker"
                trySend(FoundTracker(result.device.address, name, result.rssi))
            }

            override fun onScanFailed(errorCode: Int) {
                close(IOException("Scan failed: $errorCode"))
            }
        }
        val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(TrackerProtocol.SERVICE)).build())
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            scanner.startScan(filters, settings, callback)
        } catch (e: RuntimeException) {
            // Bluetooth went off, or the permission was revoked, since readiness() said Ready
            close(IOException("Scan refused", e))
            return@callbackFlow
        }
        awaitClose {
            try {
                scanner.stopScan(callback)
            } catch (e: RuntimeException) {
                // Bluetooth was turned off or the permission revoked meanwhile
            }
        }
    }

    private suspend fun run(address: String) {
        while (true) {
            when (readiness()) {
                Readiness.NoPermission -> {
                    _phase.value = Phase.NoPermission
                    delay(POLL_MS)
                }
                Readiness.BluetoothOff -> {
                    _phase.value = Phase.BluetoothOff
                    delay(POLL_MS)
                }
                Readiness.Ready -> {
                    _phase.value = Phase.Connecting
                    val s = Session(address)
                    session = s
                    val pause = try {
                        s.connectAndWait()
                        RETRY_MS
                    } catch (e: IncompatibleTrackerException) {
                        _phase.value = Phase.Incompatible
                        INCOMPATIBLE_RETRY_MS
                    } catch (e: IOException) {
                        RETRY_MS
                    } catch (e: SecurityException) {
                        RETRY_MS // permission revoked meanwhile; readiness() reports it next round
                    } catch (e: IllegalArgumentException) {
                        INCOMPATIBLE_RETRY_MS // not a Bluetooth address; the user can forget it
                    } finally {
                        s.close()
                    }
                    if (_phase.value == Phase.Connected) _phase.value = Phase.Connecting // the link dropped
                    delay(pause)
                }
            }
        }
    }

    private suspend fun command(build: (seq: Int) -> ByteArray): TrackerState = commandLock.withLock {
        val s = session ?: throw IOException("Not connected")
        val before = _state.value ?: throw IOException("Not connected")
        val chr = s.commandChr ?: throw IOException("Not connected")
        val seq = before.ackSeq % 255 + 1 // never equal to the current ack
        s.op<Unit>(Op.Write) { writeCharacteristic(it, chr, build(seq)) }
        val acked = withTimeoutOrNull(ACK_TIMEOUT_MS) { _state.filterNotNull().first { it.ackSeq == seq } }
            ?: s.read()?.takeIf { it.ackSeq == seq }
            ?: throw IOException("No acknowledgement from the tracker")
        if (acked.ackStatus != TrackerProtocol.ACK_OK) {
            throw IOException("The tracker rejected the command (${acked.ackStatus})")
        }
        acked
    }

    private enum class Op { Discover, Mtu, Subscribe, Read, Write }

    private class Pending(val op: Op) {
        val result = CompletableDeferred<Any>()
    }

    /** One connection attempt and, if it succeeds, the connection's lifetime. */
    private inner class Session(private val address: String) : BluetoothGattCallback() {
        private val connected = CompletableDeferred<Unit>()
        private val closed = CompletableDeferred<Unit>()
        private val opLock = Mutex()
        @Volatile private var gatt: BluetoothGatt? = null
        @Volatile private var pending: Pending? = null
        @Volatile private var stateChr: BluetoothGattCharacteristic? = null
        @Volatile var commandChr: BluetoothGattCharacteristic? = null
            private set

        private val current get() = session === this

        suspend fun connectAndWait() {
            val device = adapter?.getRemoteDevice(address) ?: throw IOException("No Bluetooth")
            val g = device.connectGatt(
                context, false, this, BluetoothDevice.TRANSPORT_LE,
                BluetoothDevice.PHY_LE_1M_MASK, callbackHandler,
            ) ?: throw IOException("Could not start connecting")
            gatt = g
            withTimeoutOrNull(CONNECT_TIMEOUT_MS) { connected.await() }
                ?: throw IOException("Connecting timed out")

            op<Unit>(Op.Discover) { it.discoverServices() }
            try {
                op<Int>(Op.Mtu) { it.requestMtu(MTU) }
            } catch (e: IOException) {
                // Keep the default MTU: notifications arrive truncated and we read instead
            }

            val service = g.getService(TrackerProtocol.SERVICE) ?: throw IncompatibleTrackerException()
            val stateChr = service.getCharacteristic(TrackerProtocol.STATE) ?: throw IncompatibleTrackerException()
            val commandChr = service.getCharacteristic(TrackerProtocol.COMMAND) ?: throw IncompatibleTrackerException()
            val cccd = stateChr.getDescriptor(TrackerProtocol.CCCD) ?: throw IncompatibleTrackerException()
            this.stateChr = stateChr
            this.commandChr = commandChr

            if (!g.setCharacteristicNotification(stateChr, true)) throw IOException("Notifications refused")
            op<Unit>(Op.Subscribe) { writeDescriptor(it, cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) }
            read() ?: throw IncompatibleTrackerException()

            if (current) _phase.value = Phase.Connected
            closed.await()
        }

        /** Reads the state characteristic; null if the bytes can't be parsed. */
        suspend fun read(): TrackerState? {
            val chr = stateChr ?: throw IOException("Not connected")
            val bytes = op<ByteArray>(Op.Read) { it.readCharacteristic(chr) }
            return TrackerProtocol.parse(bytes)
        }

        /** Runs one GATT operation; Android allows only one in flight. */
        suspend fun <T : Any> op(op: Op, start: (BluetoothGatt) -> Boolean): T = opLock.withLock {
            val g = gatt ?: throw IOException("Not connected")
            val p = Pending(op)
            pending = p
            try {
                if (!start(g)) throw IOException("$op could not start")
                val result = withTimeoutOrNull(OP_TIMEOUT_MS) { p.result.await() }
                    ?: throw IOException("$op timed out")
                @Suppress("UNCHECKED_CAST")
                result as T
            } finally {
                pending = null
            }
        }

        fun close() {
            if (current) {
                session = null
                _state.value = null
            }
            val e = IOException("Connection closed")
            connected.completeExceptionally(e)
            pending?.result?.completeExceptionally(e)
            closed.complete(Unit)
            val g = gatt ?: return
            gatt = null
            try {
                g.disconnect()
                g.close()
            } catch (e: SecurityException) {
                // Permission revoked meanwhile; the system drops the connection itself
            }
        }

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                connected.complete(Unit)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                val e = IOException("Disconnected (status $status)")
                connected.completeExceptionally(e)
                pending?.result?.completeExceptionally(e)
                closed.complete(Unit)
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) = finish(Op.Discover, status, Unit)

        // Any MTU will do, so a failed exchange is not an error
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) =
            finish(Op.Mtu, BluetoothGatt.GATT_SUCCESS, mtu)

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) =
            finish(Op.Subscribe, status, Unit)

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) =
            finish(Op.Write, status, Unit)

        override fun onCharacteristicRead(
            g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int,
        ) = handleRead(value, status)

        @Deprecated("Called before Android 13")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            @Suppress("DEPRECATION")
            handleRead(c.value ?: ByteArray(0), status)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) =
            handleNotification(value)

        @Deprecated("Called before Android 13")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            handleNotification(c.value ?: return)
        }

        private fun handleRead(value: ByteArray, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) publish(value)
            finish(Op.Read, status, value)
        }

        private fun handleNotification(value: ByteArray) {
            if (publish(value)) return
            // Truncated by a small MTU: a long read returns the whole snapshot
            scope.launch {
                try {
                    read()
                } catch (e: IOException) {
                    // The connection is going away; the session loop handles it
                }
            }
        }

        private fun publish(value: ByteArray): Boolean {
            val s = TrackerProtocol.parse(value) ?: return false
            if (current) _state.value = s
            return true
        }

        private fun finish(op: Op, status: Int, value: Any) {
            val p = pending ?: return
            if (p.op != op) return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                p.result.complete(value)
            } else {
                p.result.completeExceptionally(IOException("$op failed (status $status)"))
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun writeCharacteristic(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray): Boolean =
        if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(c, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                BluetoothStatusCodes.SUCCESS
        } else {
            c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            c.value = value
            g.writeCharacteristic(c)
        }

    @Suppress("DEPRECATION")
    private fun writeDescriptor(g: BluetoothGatt, d: BluetoothGattDescriptor, value: ByteArray): Boolean =
        if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(d, value) == BluetoothStatusCodes.SUCCESS
        } else {
            d.value = value
            g.writeDescriptor(d)
        }

    companion object {
        /** Runtime permissions needed to scan for and talk to the tracker. */
        val PERMISSIONS: Array<String> =
            if (Build.VERSION.SDK_INT >= 31) {
                arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            }

        fun hasPermissions(context: Context): Boolean = PERMISSIONS.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

        private const val MTU = 247
        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val OP_TIMEOUT_MS = 5_000L
        private const val ACK_TIMEOUT_MS = 4_000L
        private const val RETRY_MS = 3_000L
        private const val INCOMPATIBLE_RETRY_MS = 15_000L
        private const val POLL_MS = 1_000L
    }
}
