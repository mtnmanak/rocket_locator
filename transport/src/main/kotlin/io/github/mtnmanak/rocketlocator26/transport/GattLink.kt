package io.github.mtnmanak.rocketlocator26.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import io.github.mtnmanak.rocketlocator26.transport.BleUartSource.GattMessage
import kotlinx.coroutines.channels.Channel
import java.io.IOException
import java.util.UUID

/** Session operations only: callbacks remain the sole producer of GATT messages. */
internal interface GattLink {
    fun requestMtu(mtu: Int): Boolean

    fun discoverServices(): Boolean

    fun getService(uuid: UUID): GattService?

    fun setCharacteristicNotification(
        characteristic: GattCharacteristic,
        enabled: Boolean,
    ): Boolean

    fun writeDescriptor(descriptor: GattDescriptor): Boolean

    fun close()
}

internal fun interface GattService {
    fun getCharacteristic(uuid: UUID): GattCharacteristic?
}

internal fun interface GattCharacteristic {
    fun getDescriptor(uuid: UUID): GattDescriptor?
}

internal interface GattDescriptor

internal fun interface GattLinkFactory {
    fun connect(messages: Channel<GattMessage>): GattLink
}

/** Preserves the framework overloads and SDK branches; no disconnect call is added to close. */
@SuppressLint("MissingPermission")
internal class AndroidGattLinkFactory(
    private val context: Context,
    private val deviceAddress: String,
) : GattLinkFactory {
    override fun connect(messages: Channel<GattMessage>): GattLink {
        val device = resolveDevice()

        // Deprecated in the API 37 SDK; still the overload that covers minSdk 26 with TRANSPORT_LE.
        @Suppress("DEPRECATION")
        val gatt =
            device.connectGatt(
                context,
                // autoConnect =
                false,
                createCallback(messages),
                BluetoothDevice.TRANSPORT_LE,
            ) ?: throw IOException("Unable to initiate BLE connection")
        return AndroidGattLink(gatt)
    }

    /**
     * Resolves the adapter and the remote device.
     *
     * @throws IOException if Bluetooth is unavailable or turned off (recoverable).
     * @throws BleUartSource.FatalLinkException if [deviceAddress] is not a valid MAC.
     */
    private fun resolveDevice(): BluetoothDevice {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter =
            manager?.adapter
                ?: throw IOException("Bluetooth is not available on this device")
        if (!adapter.isEnabled) throw IOException("Bluetooth is turned off")
        return try {
            adapter.getRemoteDevice(deviceAddress)
        } catch (e: IllegalArgumentException) {
            throw BleUartSource.FatalLinkException("Invalid Bluetooth address \"$deviceAddress\"")
        }
    }

    /**
     * Builds the GATT callback that forwards framework events into [messages].
     * `trySend` on an unlimited channel only fails once the session has closed
     * it, at which point late callbacks are intentionally dropped.
     */
    private fun createCallback(messages: Channel<GattMessage>): BluetoothGattCallback =
        object : BluetoothGattCallback() {
            override fun onConnectionStateChange(
                gatt: BluetoothGatt,
                status: Int,
                newState: Int,
            ) {
                when {
                    status == BluetoothGatt.GATT_SUCCESS &&
                        newState == BluetoothProfile.STATE_CONNECTED -> {
                        messages.trySend(GattMessage.LinkUp)
                    }

                    newState == BluetoothProfile.STATE_DISCONNECTED ||
                        status != BluetoothGatt.GATT_SUCCESS -> {
                        messages.trySend(GattMessage.LinkDown(status))
                    }
                }
            }

            override fun onMtuChanged(
                gatt: BluetoothGatt,
                mtu: Int,
                status: Int,
            ) {
                messages.trySend(GattMessage.MtuChanged)
            }

            override fun onServicesDiscovered(
                gatt: BluetoothGatt,
                status: Int,
            ) {
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
                if (characteristic.uuid == BleUartSource.UART_CHARACTERISTIC_UUID) {
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
                if (characteristic.uuid != BleUartSource.UART_CHARACTERISTIC_UUID) return
                val value = characteristic.value ?: return
                messages.trySend(GattMessage.Data(value.copyOf()))
            }
        }
}

@SuppressLint("MissingPermission")
private class AndroidGattLink(
    private val gatt: BluetoothGatt,
) : GattLink {
    override fun requestMtu(mtu: Int): Boolean = gatt.requestMtu(mtu)

    override fun discoverServices(): Boolean = gatt.discoverServices()

    override fun getService(uuid: UUID): GattService? = gatt.getService(uuid)?.let(::AndroidGattService)

    override fun setCharacteristicNotification(
        characteristic: GattCharacteristic,
        enabled: Boolean,
    ): Boolean = gatt.setCharacteristicNotification((characteristic as AndroidGattCharacteristic).characteristic, enabled)

    override fun writeDescriptor(descriptor: GattDescriptor): Boolean {
        val cccd = (descriptor as AndroidGattDescriptor).descriptor
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ==
                BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(cccd)
        }
    }

    override fun close() = gatt.close()
}

private class AndroidGattService(
    private val service: BluetoothGattService,
) : GattService {
    override fun getCharacteristic(uuid: UUID): GattCharacteristic? = service.getCharacteristic(uuid)?.let(::AndroidGattCharacteristic)
}

private class AndroidGattCharacteristic(
    val characteristic: BluetoothGattCharacteristic,
) : GattCharacteristic {
    override fun getDescriptor(uuid: UUID): GattDescriptor? = characteristic.getDescriptor(uuid)?.let(::AndroidGattDescriptor)
}

private class AndroidGattDescriptor(
    val descriptor: BluetoothGattDescriptor,
) : GattDescriptor
