package com.example.espble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import com.example.espble.data.Ack
import com.example.espble.data.DeltaS
import java.util.UUID

@SuppressLint("MissingPermission") // разрешения проверяются в Activity
class BleClient(
    private val context: Context,
    private val onDelta: (DeltaS) -> Unit,
    private val onState: (String) -> Unit = {}
) {
    companion object {
        private const val TAG = "BleClient"
        // TODO: подставь свои UUID из прошивки ESP32
        val SERVICE_UUID: UUID = UUID.fromString("0000aaaa-0000-1000-8000-00805f9b34fb")
        val DELTA_UUID: UUID   = UUID.fromString("0000aaab-0000-1000-8000-00805f9b34fb")
        val ACK_UUID: UUID     = UUID.fromString("0000aaac-0000-1000-8000-00805f9b34fb")
        val CCCD_UUID: UUID    = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val adapter: BluetoothAdapter =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private val scanner: BluetoothLeScanner?
        get() = adapter.bluetoothLeScanner

    private var gatt: BluetoothGatt? = null
    private var ackChar: BluetoothGattCharacteristic? = null

    // ---------- scan ----------
    fun start() {
        val s = scanner
        if (s == null) {
            onState("Bluetooth выключен")
            return
        }
        onState("scanning")
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        s.startScan(listOf(filter), settings, scanCallback)
    }
    fun stop() {
        scanner?.stopScan(scanCallback)
        gatt?.close()
        gatt = null
        ackChar = null
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            scanner?.stopScan(this)
            onState("connecting to ${result.device.address}")
            gatt = result.device.connectGatt(
                context, false, gattCallback, BluetoothDevice.TRANSPORT_LE
            )
        }
        override fun onScanFailed(errorCode: Int) {
            onState("scan failed: $errorCode")
        }
    }

    // ---------- gatt ----------
    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                g.requestMtu(185) // ответ придёт в onMtuChanged
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                onState("disconnected ($status), rescanning")
                g.close()
                gatt = null
                ackChar = null
                start() // автопереподключение
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            Log.d(TAG, "MTU=$mtu")
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val service = g.getService(SERVICE_UUID)
            val deltaChar = service?.getCharacteristic(DELTA_UUID)
            ackChar = service?.getCharacteristic(ACK_UUID)
            if (deltaChar == null || ackChar == null) {
                onState("service/characteristics not found")
                return
            }
            g.setCharacteristicNotification(deltaChar, true)
            val cccd = deltaChar.getDescriptor(CCCD_UUID)
            val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(cccd, value)
            } else {
                @Suppress("DEPRECATION")
                cccd.value = value
                @Suppress("DEPRECATION")
                g.writeDescriptor(cccd)
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            onState(if (status == BluetoothGatt.GATT_SUCCESS) "ready" else "notify enable failed: $status")
        }

        // API 33+
        override fun onCharacteristicChanged(
            g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray
        ) {
            if (c.uuid == DELTA_UUID) handleDelta(value)
        }

        // API < 33
        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33 && c.uuid == DELTA_UUID) {
                @Suppress("DEPRECATION")
                handleDelta(c.value)
            }
        }
    }

    private fun handleDelta(bytes: ByteArray) {
        try {
            onDelta(DeltaS.parseFrom(bytes))
        } catch (e: Exception) {
            Log.e(TAG, "bad protobuf: ${bytes.size} bytes", e)
        }
    }

    // ---------- отправка ack ----------
    fun sendAck(ack: Ack): Boolean {
        val g = gatt ?: return false
        val c = ackChar ?: return false
        val bytes = ack.toByteArray()
        return if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(c, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                    BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            c.value = bytes
            @Suppress("DEPRECATION")
            g.writeCharacteristic(c)
        }
    }
}