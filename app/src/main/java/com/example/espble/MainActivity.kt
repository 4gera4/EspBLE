package com.example.espble

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.espble.data.Ack

class MainActivity : AppCompatActivity() {

    private lateinit var ble: BleClient
    private lateinit var gps: LocationProvider
    private lateinit var tv: TextView

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) startAll() else tv.text = "Нет разрешений"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tv = TextView(this).also { setContentView(it) }

        gps = LocationProvider(this)
        ble = BleClient(
            context = this,
            onDelta = { delta ->
                runOnUiThread {
                    tv.text = "dX=${delta.deltaX} dY=${delta.deltaY}\n" +
                            "${delta.timeFrom}..${delta.timeTo}"
                }
                val loc = gps.last
                if (loc != null) {
                    ble.sendAck(
                        Ack.newBuilder()
                            .setTime(System.currentTimeMillis())
                            .setLat(loc.latitude)
                            .setLon(loc.longitude)
                            .build()
                    )
                }
            },
            onState = { s -> runOnUiThread { title = s } }
        )

        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 31) {
            perms += Manifest.permission.BLUETOOTH_SCAN
            perms += Manifest.permission.BLUETOOTH_CONNECT
        }
        permLauncher.launch(perms.toTypedArray())
    }

    private fun startAll() {
        gps.start()
        ble.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        ble.stop()
        gps.stop()
    }
}