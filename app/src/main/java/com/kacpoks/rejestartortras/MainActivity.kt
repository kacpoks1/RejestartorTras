package com.kacpoks.cartracker

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.kacpoks.rejestartortras.R
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.compass.CompassOverlay
import org.osmdroid.views.overlay.compass.InternalCompassOrientationProvider
import org.osmdroid.views.overlay.gestures.RotationGestureOverlay
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import kotlin.random.Random
import javax.xml.parsers.DocumentBuilderFactory

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var mapView: MapView
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationOverlay: MyLocationNewOverlay
    private lateinit var userMarker: Marker

    private var isTracking = false
    private val currentRoutePoints = mutableListOf<GeoPoint>()
    private val currentRouteLine = Polyline()
    private val savedRoutes = mutableListOf<Polyline>()

    private val locationPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.getOrDefault(Manifest.permission.ACCESS_FINE_LOCATION, false) ||
            permissions.getOrDefault(Manifest.permission.ACCESS_COARSE_LOCATION, false)
        ) {
            initializeMap()
            setupLocationOverlay()
            checkIfGpsEnabled()
            loadSavedRoutes()
        } else {
            Toast.makeText(this, "Brak uprawnień lokalizacji", Toast.LENGTH_SHORT).show()
            initializeMapWithDefaultLocation()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        val btnStart: Button = findViewById(R.id.btnStart)
        val btnStop: Button = findViewById(R.id.btnStop)
        val btnExport: Button = findViewById(R.id.btnExport)
        val btnManageRoutes: Button = findViewById(R.id.btnManageRoutes)

        mapView = findViewById(R.id.mapView)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        btnStart.setOnClickListener { startTracking() }
        btnStop.setOnClickListener { stopTracking() }
        btnExport.setOnClickListener { exportCurrentRoute() }
        btnManageRoutes.setOnClickListener { manageRoutes() }

        requestLocationPermissions()
    }

    private fun initializeMap() {
        Configuration.getInstance().load(this, getSharedPreferences("osmdroid", MODE_PRIVATE))
        Configuration.getInstance().userAgentValue = packageName

        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true)
        mapView.controller.setZoom(18.0)

        val rotationGestureOverlay = RotationGestureOverlay(mapView)
        rotationGestureOverlay.isEnabled = true
        mapView.overlays.add(rotationGestureOverlay)

        val compassOverlay = CompassOverlay(this, InternalCompassOrientationProvider(this), mapView)
        compassOverlay.enableCompass()
        mapView.overlays.add(compassOverlay)

        // przygotowanie polyline dla bieżącej trasy
        currentRouteLine.color = randomColor()
        currentRouteLine.width = 8f
        mapView.overlays.add(currentRouteLine)
    }

    private fun initializeMapWithDefaultLocation() {
        initializeMap()
        val defaultPoint = GeoPoint(52.2297, 21.0122)
        mapView.controller.setCenter(defaultPoint)
        Toast.makeText(this, "Użyto domyślnej lokalizacji", Toast.LENGTH_SHORT).show()
    }

    private fun setupLocationOverlay() {
        locationOverlay = MyLocationNewOverlay(GpsMyLocationProvider(this), mapView)
        locationOverlay.enableMyLocation()
        locationOverlay.enableFollowLocation()
        locationOverlay.runOnFirstFix {
            runOnUiThread { mapView.controller.animateTo(locationOverlay.myLocation) }
        }
        mapView.overlays.add(locationOverlay)

        userMarker = Marker(mapView)
        userMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        mapView.overlays.add(userMarker)
    }

    private fun checkIfGpsEnabled() {
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val isGpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        if (isGpsEnabled) {
            updateStatus("GPS włączony - gotowy do śledzenia")
        } else {
            updateStatus("GPS wyłączony - włącz, aby śledzić trasę")
            AlertDialog.Builder(this)
                .setTitle("Włącz GPS")
                .setMessage("Aplikacja wymaga włączonego GPS. Chcesz włączyć?")
                .setPositiveButton("Tak") { _, _ ->
                    startActivity(android.content.Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                }
                .setNegativeButton("Nie", null)
                .show()
        }
    }

    private fun startTracking() {
        if (!isTracking) {
            isTracking = true
            currentRoutePoints.clear()
            currentRouteLine.setPoints(currentRoutePoints)
            currentRouteLine.color = randomColor()
            updateStatus("Śledzenie trasy AKTYWNE")
            Toast.makeText(this, "Rozpoczęto śledzenie trasy", Toast.LENGTH_SHORT).show()
            trackLocation()
        }
    }

    private fun stopTracking() {
        if (isTracking) {
            isTracking = false
            updateStatus("Śledzenie zatrzymane. Gotowy do eksportu")
            Toast.makeText(this, "Zatrzymano śledzenie trasy", Toast.LENGTH_SHORT).show()
        }
    }

    private fun trackLocation() {
        fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
            location?.let {
                val point = GeoPoint(it.latitude, it.longitude)
                userMarker.position = point
                mapView.controller.animateTo(point)

                if (isTracking) {
                    currentRoutePoints.add(point)
                    currentRouteLine.setPoints(currentRoutePoints)
                    mapView.invalidate()
                }
            }
            // Powtarzamy tracking co kilka sekund, np. 3 sekundy
            if (isTracking) {
                mapView.postDelayed({ trackLocation() }, 500)
            }
        }
    }

    private fun exportCurrentRoute() {
        if (currentRoutePoints.isEmpty()) {
            Toast.makeText(this, "Brak punktów do eksportu", Toast.LENGTH_SHORT).show()
            return
        }

        val gpxHeader = """<?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1" creator="CarTrackerApp">
        """.trimIndent()

        val gpxFooter = "</gpx>"

        val gpxPoints = currentRoutePoints.joinToString("\n") { point ->
            "  <wpt lat=\"${point.latitude}\" lon=\"${point.longitude}\" />"
        }

        val gpxData = "$gpxHeader\n$gpxPoints\n$gpxFooter"

        val fileName = "Route_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.gpx"
        val file = File(getExternalFilesDir(null), fileName)
        FileOutputStream(file).use { it.write(gpxData.toByteArray()) }

        Toast.makeText(this, "Trasa wyeksportowana do $fileName", Toast.LENGTH_LONG).show()
        savedRoutes.add(currentRouteLine)
    }

    private fun manageRoutes() {
        if (savedRoutes.isEmpty()) {
            Toast.makeText(this, "Brak zapisanych tras", Toast.LENGTH_SHORT).show()
            return
        }
        val routeNames = savedRoutes.mapIndexed { index, _ -> "Trasa ${index + 1}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Zarządzaj trasami")
            .setItems(routeNames) { _, which ->
                // Usuwanie wybranej trasy
                mapView.overlays.remove(savedRoutes[which])
                savedRoutes.removeAt(which)
                mapView.invalidate()
                Toast.makeText(this, "Usunięto trasę", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun loadSavedRoutes() {
        val folder = getExternalFilesDir(null)
        folder?.listFiles()?.forEach { file ->
            if (file.extension == "gpx") {
                try {
                    val points = mutableListOf<GeoPoint>()
                    val db = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                    val doc = db.parse(FileInputStream(file))
                    val nodeList = doc.getElementsByTagName("wpt")
                    for (i in 0 until nodeList.length) {
                        val node = nodeList.item(i)
                        val lat = node.attributes.getNamedItem("lat").nodeValue.toDouble()
                        val lon = node.attributes.getNamedItem("lon").nodeValue.toDouble()
                        points.add(GeoPoint(lat, lon))
                    }
                    val polyline = Polyline()
                    polyline.setPoints(points)
                    polyline.color = randomColor()
                    polyline.width = 8f
                    mapView.overlays.add(polyline)
                    savedRoutes.add(polyline)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    private fun randomColor(): Int {
        val r = Random.nextInt(50, 256)
        val g = Random.nextInt(50, 256)
        val b = Random.nextInt(50, 256)
        return android.graphics.Color.rgb(r, g, b)
    }

    private fun updateStatus(message: String) {
        tvStatus.text = "Status: $message"
    }

    private fun requestLocationPermissions() {
        locationPermissionRequest.launch(arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ))
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
        if (::locationOverlay.isInitialized) locationOverlay.enableMyLocation()
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause()
        if (::locationOverlay.isInitialized) locationOverlay.disableMyLocation()
    }
}
