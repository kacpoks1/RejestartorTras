package com.kacpoks.rejestartortras

import android.content.Context
import android.location.Location
import com.google.android.gms.location.*

class LocationHelper {
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null
    var onLocationUpdate: ((Location) -> Unit)? = null

    fun startTracking(context: Context, onUpdate: (Location) -> Unit) {
        this.onLocationUpdate = onUpdate
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)

        val locationRequest = LocationRequest.create().apply {
            interval = 3000
            fastestInterval = 2000
            priority = LocationRequest.PRIORITY_HIGH_ACCURACY
        }

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.locations.forEach { location ->
                    onLocationUpdate?.invoke(location)
                }
            }
        }

        locationCallback?.let {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                it,
                null
            )
        }
    }

    fun stopTracking() {
        locationCallback?.let {
            fusedLocationClient?.removeLocationUpdates(it)
        }
        onLocationUpdate = null
    }
}