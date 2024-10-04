package com.example.landmarkapp

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.fragment.app.Fragment
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query


class MapFragment : Fragment() {

    private lateinit var mapView: MapView
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var requestPermissionLauncher: ActivityResultLauncher<String>
    private var startMarker: Marker? = null
    private var endMarker: Marker? = null
    private var routeOverlay: Polyline? = null

    private lateinit var nominatimApiService: NominatimApiService

    private lateinit var loadingIndicator: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted: Boolean ->
            if (isGranted) {
                getUserLocation()
            } else {
                Toast.makeText(context, "Location permission denied", Toast.LENGTH_SHORT).show()
            }
        }

        nominatimApiService = Retrofit.Builder()
            .baseUrl("https://nominatim.openstreetmap.org/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(NominatimApiService::class.java)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_map, container, false)

        val ctx = requireContext().applicationContext
        Configuration.getInstance().load(ctx, androidx.preference.PreferenceManager.getDefaultSharedPreferences(ctx))
        Configuration.getInstance().userAgentValue = ctx.packageName

        mapView = view.findViewById(R.id.mapView)
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true)
        loadingIndicator = view.findViewById(R.id.loadingIndicator)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireActivity())

        if (ActivityCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            getUserLocation()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        setupMapLongPress()

        val getDirectionsButton: Button = view.findViewById(R.id.getDirectionsButton)
        getDirectionsButton.setOnClickListener {
            if (startMarker != null && endMarker != null) {
                getDirections(startMarker!!.position, endMarker!!.position)
            } else {
                Toast.makeText(context, "Please select start and end points", Toast.LENGTH_SHORT).show()
            }
        }

        return view
    }

    private fun getUserLocation() {
        if (ActivityCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            try {
                fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
                    location?.let {
                        val userLocation = GeoPoint(it.latitude, it.longitude)
                        val mapController = mapView.controller
                        mapController.setZoom(15.0)
                        mapController.setCenter(userLocation)

                        val userMarker = Marker(mapView)
                        userMarker.position = userLocation
                        userMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                        userMarker.title = "You are here"
                        mapView.overlays.add(userMarker)
                        mapView.invalidate()
                    } ?: run {
                        Toast.makeText(context, "Unable to get location", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: SecurityException) {
                Toast.makeText(context, "Location access denied", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(context, "Location permission is required", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupMapLongPress() {
        mapView.overlays.add(object : org.osmdroid.views.overlay.Overlay() {
            override fun onLongPress(e: MotionEvent, mapView: MapView): Boolean {
                val projection = mapView.projection
                val igeoPoint = projection.fromPixels(e.x.toInt(), e.y.toInt())
                val geoPoint = GeoPoint(igeoPoint.latitude, igeoPoint.longitude)
                addMarker(geoPoint)
                return true
            }
        })
    }

    private fun addMarker(geoPoint: GeoPoint) {
        val marker = Marker(mapView)
        marker.position = geoPoint
        marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)

        when {
            startMarker == null -> {
                startMarker = marker
                marker.title = "Start"
                marker.icon = resources.getDrawable(R.drawable.ic_start_marker)
            }
            endMarker == null -> {
                endMarker = marker
                marker.title = "End"
                marker.icon = resources.getDrawable(R.drawable.ic_end_marker)
            }
            else -> {
                mapView.overlays.remove(startMarker)
                startMarker = endMarker
                startMarker?.title = "Start"
                startMarker?.icon = resources.getDrawable(R.drawable.ic_start_marker)
                endMarker = marker
                endMarker?.title = "End"
                endMarker?.icon = resources.getDrawable(R.drawable.ic_end_marker)
            }
        }

        mapView.overlays.add(marker)
        mapView.invalidate()
    }

    private fun getDirections(start: GeoPoint, end: GeoPoint) {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                loadingIndicator.visibility = View.VISIBLE
                val startCoordinates = "${start.latitude},${start.longitude}"
                val endCoordinates = "${end.latitude},${end.longitude}"

                val response = withContext(Dispatchers.IO) {
                    nominatimApiService.getRoute(startCoordinates, endCoordinates)
                }

                if (response.isNotEmpty()) {
                    val routePoints = response.map { GeoPoint(it.lat, it.lon) }
                    drawRoute(routePoints)
                } else {
                    Toast.makeText(context, "No route found", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Error getting directions: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                loadingIndicator.visibility = View.GONE
            }
        }
    }

    private fun drawRoute(routePoints: List<GeoPoint>) {
        routeOverlay?.let { mapView.overlays.remove(it) }

        routeOverlay = Polyline().apply {
            setPoints(routePoints)
            color = Color.BLUE
            width = 5f
        }

        mapView.overlays.add(routeOverlay)
        mapView.invalidate()
    }
}

interface NominatimApiService {
    @GET("route/v1/driving")
    suspend fun getRoute(
        @Query("origin") origin: String,
        @Query("destination") destination: String
    ): List<RoutePoint>
}

data class RoutePoint(
    val lat: Double,
    val lon: Double
)