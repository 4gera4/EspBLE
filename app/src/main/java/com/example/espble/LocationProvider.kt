package com.example.espble

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import com.google.android.gms.location.*

@SuppressLint("MissingPermission")
class LocationProvider(context: Context) {
    private val client = LocationServices.getFusedLocationProviderClient(context)

    @Volatile var last: Location? = null
        private set

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            last = result.lastLocation
        }
    }

    fun start() {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L).build()
        client.requestLocationUpdates(request, callback, Looper.getMainLooper())
    }

    fun stop() = client.removeLocationUpdates(callback)
}