package io.github.mtnmanak.rocketlocator26.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import android.os.SystemClock
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * An [NmeaSource] backed by a BLE "UART over GATT" link, as used by
 * HM-10/BT-04-style transparent-UART modules on receiver boards.
 *
 * Collection of [events] runs an infinite supervision loop until the collector
 * cancels: connect GATT (`TRANSPORT_LE`), request MTU [REQUESTED_MTU]
 * (proceeding regardless of the outcome), discover services, locate the
 * FFE0 service / FFE1 characteristic, and enable notifications — both via
 * [BluetoothGatt.setCharacteristicNotification] and, when present, by writing
 * `ENABLE_NOTIFICATION_VALUE` to the CCCD descriptor. A missing CCCD is
 * tolerated: many HM-10/BT-04 clones notify without it (the original 2020 app
 * never wrote it at all and worked). Once notifications are set up it emits
 * [SourceEvent.Connected] and streams notification fragments (~20 bytes each,
 * more with a larger MTU) through an [NmeaLineAssembler], emitting one
 * [SourceEvent.LineReceived] per complete NMEA line, timestamped with
 * [SystemClock.elapsedRealtime].
 *
 * On a link failure — GATT error, remote disconnect, setup timeout, or
 * watchdog expiry (no bytes for [WATCHDOG_TIMEOUT_MS] while connected) — it
 * emits [SourceEvent.SourceError] with `willRetry = true`, then
 * [SourceEvent.Disconnected], waits per [ReconnectPolicy] (reset on every
 * successful connect), and retries. If the device lacks the FFE0/FFE1 UART
 * service, the error is terminal (`willRetry = false`) and the flow ends.
 *
 * The flow is cold: every collection opens a fresh GATT client, and cancelling
 * the collector closes it and unregisters the callback with no leaks
 * (`awaitClose` guarantees [BluetoothGatt.close]).
 *
 * Callers must hold the `BLUETOOTH_CONNECT` runtime permission (the UI layer
 * gates on it). If it is missing, the [SecurityException] is surfaced as a
 * [SourceEvent.SourceError] with `willRetry = false` followed by
 * [SourceEvent.Disconnected], and the flow ends — the permission will not
 * appear by retrying.
 *
 * @param context used for [BluetoothDevice.connectGatt] and to obtain the
 *   [BluetoothManager].
 * @param deviceAddress MAC address of the receiver's BLE module.
 * @param deviceName optional friendly device name for the UI; [name] falls back
 *   to [deviceAddress] when null.
 */
@SuppressLint("MissingPermission")
class BleUartSource(
    private val context: Context,
    private val deviceAddress: String,
    deviceName: String? = null,
) : NmeaSource {

    override val name: String = deviceName ?: deviceAddress

    /** Internal messages bridging [BluetoothGattCallback] into coroutine land. */
    private sealed interface GattMessage {
        /** The ACL link is up; service discovery may begin. */
        data object LinkUp : GattMessage

        /** The link dropped or the connect attempt failed with [status]. */
        data class LinkDown(val status: Int) : GattMessage

        /** The MTU exchange finished (successfully or not — we proceed either way). */
        data object MtuChanged : GattMessage

        /** Service discovery finished with [status]. */
        data class ServicesDiscovered(val status: Int) : GattMessage

        /** The CCCD write completed (any status — we proceed either way). */
        data object DescriptorWritten : GattMessage

        /** One notification payload from the UART characteristic. */
        class Data(val bytes: ByteArray) : GattMessage
    }

    /** Terminal, non-retryable link failure (e.g. the device is not a UART bridge). */
    private class FatalLinkException(message: String) : Exception(message)

    override fun events(): Flow<SourceEvent> = callbackFlow {
        val policy = ReconnectPolicy()
        // The GATT client of the session currently in flight, so awaitClose can
        // reach it. Sessions clear-and-close it themselves between retries; the
        // atomic handoff ensures exactly one side closes it.
        val activeGatt = AtomicReference<BluetoothGatt?>(null)

        val supervisor = launch {
            while (true) {
                val fatal = runSession(activeGatt, policy)
                if (fatal) break
                delay(policy.nextDelayMs())
            }
            close() // Terminal failure: end the flow for the collector.
        }

        awaitClose {
            supervisor.cancel()
            activeGatt.getAndSet(null)?.closeQuietly()
        }
    }

    /**
     * Runs one connect-and-stream session: emits [SourceEvent.Connected] /
     * [SourceEvent.LineReceived] events, always finishes with
     * [SourceEvent.Disconnected] (preceded by a [SourceEvent.SourceError]
     * describing the failure), and closes its GATT client.
     *
     * @return true if the failure was terminal and the supervision loop must
     *   stop (missing permission, bad address, no UART service).
     */
    private suspend fun ProducerScope<SourceEvent>.runSession(
        activeGatt: AtomicReference<BluetoothGatt?>,
        policy: ReconnectPolicy,
    ): Boolean {
        var fatal = false
        var gatt: BluetoothGatt? = null
        val messages = Channel<GattMessage>(Channel.UNLIMITED)
        try {
            val device = resolveDevice()
            gatt = device.connectGatt(
                context,
                /* autoConnect = */ false,
                createCallback(messages),
                BluetoothDevice.TRANSPORT_LE,
            ) ?: throw IOException("Unable to initiate BLE connection")
            activeGatt.set(gatt)

            awaitLinkUp(messages)
            negotiateMtu(gatt, messages)
            val uart = discoverUartCharacteristic(gatt, messages)
            enableNotifications(gatt, uart, messages)

            send(SourceEvent.Connected)
            policy.reset()
            pumpData(messages) // Returns only by throwing.
        } catch (e: SecurityException) {
            send(
                SourceEvent.SourceError(
                    message = "Bluetooth permission BLUETOOTH_CONNECT not granted: ${e.message}",
                    willRetry = false,
                ),
            )
            fatal = true
        } catch (e: FatalLinkException) {
            send(SourceEvent.SourceError(e.message ?: "Unusable BLE device", willRetry = false))
            fatal = true
        } catch (e: IOException) {
            send(SourceEvent.SourceError(e.message ?: "BLE link error", willRetry = true))
        } finally {
            messages.close()
            if (gatt != null && activeGatt.compareAndSet(gatt, null)) {
                gatt.closeQuietly()
            }
        }
        send(SourceEvent.Disconnected)
        return fatal
    }

    /**
     * Resolves the adapter and the remote device.
     *
     * @throws IOException if Bluetooth is unavailable or turned off (recoverable).
     * @throws FatalLinkException if [deviceAddress] is not a valid MAC.
     */
    private fun resolveDevice(): BluetoothDevice {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = manager?.adapter
            ?: throw IOException("Bluetooth is not available on this device")
        if (!adapter.isEnabled) throw IOException("Bluetooth is turned off")
        return try {
            adapter.getRemoteDevice(deviceAddress)
        } catch (e: IllegalArgumentException) {
            throw FatalLinkException("Invalid Bluetooth address \"$deviceAddress\"")
        }
    }

    /**
     * Builds the GATT callback that forwards framework events into [messages].
     * `trySend` on an unlimited channel only fails once the session has closed
     * it, at which point late callbacks are intentionally dropped.
     */
    private fun createCallback(messages: Channel<GattMessage>): BluetoothGattCallback =
        object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                when {
                    status == BluetoothGatt.GATT_SUCCESS &&
                        newState == BluetoothProfile.STATE_CONNECTED ->
                        messages.trySend(GattMessage.LinkUp)

                    newState == BluetoothProfile.STATE_DISCONNECTED ||
                        status != BluetoothGatt.GATT_SUCCESS ->
                        messages.trySend(GattMessage.LinkDown(status))
                }
            }

            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                messages.trySend(GattMessage.MtuChanged)
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                messages.trySend(GattMessage.ServicesDiscovered(status))
            }

            override fun onDescriptorWrite(
                gatt: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int,
            ) {
                messages.trySend(GattMessage.DescriptorWritten)
            }

            // API 33+ delivers the payload directly.
            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
            ) {
                if (characteristic.uuid == UART_CHARACTERISTIC_UUID) {
                    messages.trySend(GattMessage.Data(value.copyOf()))
                }
            }

            // Pre-33 devices only invoke this deprecated overload.
            @Deprecated("Deprecated in Java")
            @Suppress("DEPRECATION")
            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
            ) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return // 33+ overload handles it.
                if (characteristic.uuid != UART_CHARACTERISTIC_UUID) return
                val value = characteristic.value ?: return
                messages.trySend(GattMessage.Data(value.copyOf()))
            }
        }

    /** Waits for the ACL link to come up, tolerating stale queued messages. */
    private suspend fun awaitLinkUp(messages: ReceiveChannel<GattMessage>) {
        while (true) {
            val message = withTimeoutOrNull(CONNECT_TIMEOUT_MS) { messages.receive() }
                ?: throw IOException("Timed out connecting to $name")
            when (message) {
                GattMessage.LinkUp -> return
                is GattMessage.LinkDown ->
                    throw IOException("BLE connection failed (status ${message.status})")
                else -> Unit
            }
        }
    }

    /**
     * Requests MTU [REQUESTED_MTU] and waits for the exchange to settle before
     * service discovery, but proceeds regardless of the result — a failed or
     * silent negotiation just leaves the default 23-byte MTU in place.
     */
    private suspend fun negotiateMtu(gatt: BluetoothGatt, messages: ReceiveChannel<GattMessage>) {
        if (!gatt.requestMtu(REQUESTED_MTU)) return
        while (true) {
            val message = withTimeoutOrNull(MTU_TIMEOUT_MS) { messages.receive() } ?: return
            when (message) {
                GattMessage.MtuChanged -> return
                is GattMessage.LinkDown ->
                    throw IOException("BLE link lost during MTU exchange (status ${message.status})")
                else -> Unit
            }
        }
    }

    /**
     * Discovers services and returns the FFE1 UART characteristic.
     *
     * @throws FatalLinkException if the device exposes no FFE0/FFE1 UART service
     *   — it is not a transparent-UART bridge and retrying cannot change that.
     */
    private suspend fun discoverUartCharacteristic(
        gatt: BluetoothGatt,
        messages: ReceiveChannel<GattMessage>,
    ): BluetoothGattCharacteristic {
        if (!gatt.discoverServices()) throw IOException("Failed to start GATT service discovery")
        while (true) {
            val message = withTimeoutOrNull(DISCOVERY_TIMEOUT_MS) { messages.receive() }
                ?: throw IOException("Timed out discovering GATT services")
            when (message) {
                is GattMessage.ServicesDiscovered -> {
                    if (message.status != BluetoothGatt.GATT_SUCCESS) {
                        throw IOException("GATT service discovery failed (status ${message.status})")
                    }
                    val service = gatt.getService(UART_SERVICE_UUID)
                    return service?.getCharacteristic(UART_CHARACTERISTIC_UUID)
                        ?: throw FatalLinkException("Device has no FFE0/FFE1 UART service")
                }
                is GattMessage.LinkDown ->
                    throw IOException("BLE link lost during service discovery (status ${message.status})")
                else -> Unit
            }
        }
    }

    /**
     * Enables notifications on the UART characteristic. The CCCD descriptor is
     * written when present, but its absence — and even a failed write — is
     * tolerated: many HM-10/BT-04 clones notify without it.
     */
    private suspend fun enableNotifications(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        messages: ReceiveChannel<GattMessage>,
    ) {
        if (!gatt.setCharacteristicNotification(characteristic, true)) {
            throw IOException("Failed to enable notifications on the UART characteristic")
        }
        val cccd = characteristic.getDescriptor(CCCD_UUID) ?: return
        val writeStarted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ==
                BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(cccd)
        }
        if (!writeStarted) return
        while (true) {
            val message = withTimeoutOrNull(DESCRIPTOR_TIMEOUT_MS) { messages.receive() } ?: return
            when (message) {
                GattMessage.DescriptorWritten -> return
                is GattMessage.LinkDown ->
                    throw IOException("BLE link lost while enabling notifications (status ${message.status})")
                else -> Unit // Early Data fragments are dropped; NMEA repeats every second.
            }
        }
    }

    /**
     * Streams notification payloads through the line assembler forever.
     * Throws [IOException] on remote disconnect or when the watchdog sees no
     * bytes for [WATCHDOG_TIMEOUT_MS].
     */
    private suspend fun ProducerScope<SourceEvent>.pumpData(messages: ReceiveChannel<GattMessage>) {
        val assembler = NmeaLineAssembler()
        while (true) {
            val message = withTimeoutOrNull(WATCHDOG_TIMEOUT_MS) { messages.receive() }
                ?: throw IOException("No data received for ${WATCHDOG_TIMEOUT_MS / 1000} s (watchdog)")
            when (message) {
                is GattMessage.Data -> {
                    for (line in assembler.feed(message.bytes)) {
                        send(SourceEvent.LineReceived(line, SystemClock.elapsedRealtime()))
                    }
                }
                is GattMessage.LinkDown ->
                    throw IOException("BLE device disconnected (status ${message.status})")
                else -> Unit
            }
        }
    }

    private fun BluetoothGatt.closeQuietly() {
        try {
            close()
        } catch (_: SecurityException) {
            // Permission revoked mid-flight; the stack reclaims the client anyway.
        }
    }

    private companion object {
        /** HM-10/BT-04-style transparent UART service. */
        val UART_SERVICE_UUID: UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")

        /** Notify characteristic carrying the UART byte stream. */
        val UART_CHARACTERISTIC_UUID: UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")

        /** Client Characteristic Configuration Descriptor. */
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /**
         * MTU to request: 185 fits a full NMEA sentence in one notification on
         * most stacks while staying inside common controller limits.
         */
        const val REQUESTED_MTU = 185

        const val CONNECT_TIMEOUT_MS = 30_000L
        const val MTU_TIMEOUT_MS = 2_000L
        const val DISCOVERY_TIMEOUT_MS = 10_000L
        const val DESCRIPTOR_TIMEOUT_MS = 2_000L

        /** Reconnect if the link is up but silent this long (receivers talk at 1 Hz). */
        const val WATCHDOG_TIMEOUT_MS = 7_000L
    }
}
