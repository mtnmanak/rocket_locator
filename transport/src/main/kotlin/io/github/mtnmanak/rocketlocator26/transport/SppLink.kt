package io.github.mtnmanak.rocketlocator26.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import java.io.IOException
import java.util.UUID

/** Blocking production operations run in the source's IO child; fakes may suspend in virtual time. */
internal interface SppLink {
    suspend fun connect()

    val inputStream: SppInput

    fun close()
}

internal fun interface SppInput {
    suspend fun read(buffer: ByteArray): Int
}

internal fun interface SppLinkFactory {
    fun create(): SppLink
}

/** Keeps adapter resolution, discovery cancellation and RFCOMM creation in their original order. */
@SuppressLint("MissingPermission")
internal class AndroidSppLinkFactory(
    private val context: Context,
    private val deviceAddress: String,
) : SppLinkFactory {
    /**
     * Resolves the adapter and prepares an RFCOMM socket for the SPP service.
     *
     * @throws IOException if Bluetooth is unavailable or turned off (recoverable:
     *   the user may switch it on).
     * @throws SecurityException if `BLUETOOTH_CONNECT` is not granted.
     * @throws IllegalArgumentException if [deviceAddress] is not a valid MAC.
     */
    override fun create(): SppLink {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter =
            manager?.adapter
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
        return AndroidSppLink(device.createRfcommSocketToServiceRecord(SPP_UUID))
    }

    private companion object {
        /** Standard Serial Port Profile service record UUID. */
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}

@SuppressLint("MissingPermission")
private class AndroidSppLink(
    private val socket: BluetoothSocket,
) : SppLink {
    override suspend fun connect() = socket.connect()

    override val inputStream: SppInput
        get() {
            val input = socket.inputStream
            return SppInput { buffer -> input.read(buffer) }
        }

    override fun close() = socket.close()
}
