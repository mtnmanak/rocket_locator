package io.github.mtnmanak.rocketlocator26.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.SystemClock
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * An [NmeaSource] backed by a Bluetooth Classic RFCOMM (SPP) link, as used by
 * HC-05/HC-06-style receiver modules (e.g. Eggfinder RX).
 *
 * Collection of [events] runs an infinite supervision loop until the collector
 * cancels: connect to the device's SPP service, emit [SourceEvent.Connected],
 * then stream the input, emitting one [SourceEvent.LineReceived] per complete
 * NMEA line (reassembled by [NmeaLineAssembler], timestamped with
 * [SystemClock.elapsedRealtime]). On an I/O error, remote close, or watchdog
 * expiry (no bytes for [WATCHDOG_TIMEOUT_MS] while connected) it emits
 * [SourceEvent.SourceError] with `willRetry = true`, then
 * [SourceEvent.Disconnected], waits per [ReconnectPolicy], and retries.
 * The backoff resets on every successful connect.
 *
 * The flow is cold: every collection starts a fresh connection, and cancelling
 * the collector closes the socket and stops all internal work with no leaks.
 * Blocking socket I/O runs on [Dispatchers.IO]; cancellation unblocks it by
 * closing the socket.
 *
 * Callers must hold the `BLUETOOTH_CONNECT` runtime permission (the UI layer
 * gates on it). If it is missing, a [SecurityException] is surfaced as a
 * [SourceEvent.SourceError] with `willRetry = false` followed by
 * [SourceEvent.Disconnected], and the flow ends — the permission will not
 * appear by retrying.
 *
 * @param context any context; used to obtain the [BluetoothManager].
 * @param deviceAddress MAC address of the bonded receiver, e.g. "00:14:03:05:59:02".
 * @param deviceName optional friendly device name for the UI; [name] falls back
 *   to [deviceAddress] when null.
 */
@SuppressLint("MissingPermission")
class ClassicSppSource(
    private val context: Context,
    private val deviceAddress: String,
    deviceName: String? = null,
) : NmeaSource {

    override val name: String = deviceName ?: deviceAddress

    override fun events(): Flow<SourceEvent> = flow {
        val policy = ReconnectPolicy()
        while (true) {
            try {
                runSession(policy) // Returns only by throwing.
            } catch (e: SecurityException) {
                emit(
                    SourceEvent.SourceError(
                        message = "Bluetooth permission BLUETOOTH_CONNECT not granted: ${e.message}",
                        willRetry = false,
                    ),
                )
                emit(SourceEvent.Disconnected)
                return@flow
            } catch (e: IllegalArgumentException) {
                // getRemoteDevice() rejects malformed MAC addresses; retrying cannot fix that.
                emit(
                    SourceEvent.SourceError(
                        message = "Invalid Bluetooth address \"$deviceAddress\"",
                        willRetry = false,
                    ),
                )
                emit(SourceEvent.Disconnected)
                return@flow
            } catch (e: IOException) {
                emit(
                    SourceEvent.SourceError(
                        message = e.message ?: "Bluetooth I/O error",
                        willRetry = true,
                    ),
                )
            }
            emit(SourceEvent.Disconnected)
            delay(policy.nextDelayMs())
        }
    }

    /**
     * Runs one connect-and-stream session. Never returns normally: it either
     * throws [IOException] (recoverable link failure, including the watchdog),
     * [SecurityException]/[IllegalArgumentException] (terminal), or a
     * cancellation exception when the collector stops.
     *
     * Blocking connect/read calls run in a child coroutine on [Dispatchers.IO]
     * that forwards data chunks over a channel; the collector-side loop only
     * ever suspends at cancellable points, and its `finally` closes the socket,
     * which in turn unblocks any in-flight blocking call. That makes the whole
     * session cancellation-safe even though Bluetooth sockets ignore thread
     * interrupts.
     */
    private suspend fun FlowCollector<SourceEvent>.runSession(policy: ReconnectPolicy) {
        val socket = createSocket()
        coroutineScope {
            val inbound = Channel<ByteArray>(Channel.BUFFERED)
            val reader = launch(Dispatchers.IO) {
                try {
                    socket.connect()
                    inbound.send(CONNECTED_SENTINEL)
                    val input = socket.inputStream
                    val buffer = ByteArray(READ_BUFFER_SIZE)
                    while (true) {
                        val bytesRead = input.read(buffer)
                        if (bytesRead < 0) {
                            inbound.close(IOException("Connection closed by remote device"))
                            return@launch
                        }
                        if (bytesRead > 0) inbound.send(buffer.copyOf(bytesRead))
                    }
                } catch (e: IOException) {
                    inbound.close(e)
                } catch (e: SecurityException) {
                    inbound.close(e)
                }
            }
            try {
                // First message is the post-connect sentinel (or the close cause rethrows).
                withTimeoutOrNull(CONNECT_TIMEOUT_MS) { inbound.receive() }
                    ?: throw IOException("Timed out connecting to $name")
                emit(SourceEvent.Connected)
                policy.reset()

                val assembler = NmeaLineAssembler()
                while (true) {
                    val chunk = withTimeoutOrNull(WATCHDOG_TIMEOUT_MS) { inbound.receive() }
                        ?: throw IOException(
                            "No data received for ${WATCHDOG_TIMEOUT_MS / 1000} s (watchdog)",
                        )
                    for (line in assembler.feed(chunk)) {
                        emit(SourceEvent.LineReceived(line, SystemClock.elapsedRealtime()))
                    }
                }
            } finally {
                // Unblocks the reader if it is parked in connect()/read().
                socket.closeQuietly()
                reader.cancel()
            }
        }
    }

    /**
     * Resolves the adapter and prepares an RFCOMM socket for the SPP service.
     *
     * @throws IOException if Bluetooth is unavailable or turned off (recoverable:
     *   the user may switch it on).
     * @throws SecurityException if `BLUETOOTH_CONNECT` is not granted.
     * @throws IllegalArgumentException if [deviceAddress] is not a valid MAC.
     */
    private fun createSocket(): BluetoothSocket {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = manager?.adapter
            ?: throw IOException("Bluetooth is not available on this device")
        if (!adapter.isEnabled) throw IOException("Bluetooth is turned off")
        val device = adapter.getRemoteDevice(deviceAddress)
        try {
            // Discovery degrades RFCOMM connection reliability; stop it first.
            adapter.cancelDiscovery()
        } catch (_: SecurityException) {
            // Requires BLUETOOTH_SCAN, which the app may legitimately lack. Any
            // running discovery was not ours; proceed and let connect() try.
        }
        return device.createRfcommSocketToServiceRecord(SPP_UUID)
    }

    private fun BluetoothSocket.closeQuietly() {
        try {
            close()
        } catch (_: IOException) {
            // Already closed or closing; nothing useful to do.
        }
    }

    private companion object {
        /** Standard Serial Port Profile service record UUID. */
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        /** Zero-length marker the reader sends once `connect()` has succeeded. */
        val CONNECTED_SENTINEL = ByteArray(0)

        const val READ_BUFFER_SIZE = 1024

        /** Upper bound on `connect()`; the framework usually fails sooner on its own. */
        const val CONNECT_TIMEOUT_MS = 30_000L

        /** Reconnect if the link is up but silent this long (receivers talk at 1 Hz). */
        const val WATCHDOG_TIMEOUT_MS = 7_000L
    }
}
