package io.github.jqssun.gpssetter.ui


import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.view.View
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import io.github.jqssun.gpssetter.R
import io.github.jqssun.gpssetter.utils.ext.getAddress
import io.github.jqssun.gpssetter.utils.ext.showToast
import kotlinx.coroutines.launch
import org.maplibre.android.MapLibre
import org.maplibre.android.annotations.Marker
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.OnMapReadyCallback
import org.maplibre.android.maps.SupportMapFragment

typealias CustomLatLng = LatLng

class MapActivity: BaseMapActivity(), OnMapReadyCallback, MapLibreMap.OnMapClickListener {

    private lateinit var mMap: MapLibreMap
    private var mLatLng: LatLng? = null
    private var mMarker: Marker? = null

    /** Same meaning as in the full build: true when NO point is selected yet. */
    override fun hasMarker(): Boolean = mMarker == null
    /** Shows the marker at [point], creating it when needed (MapLibre markers have no 'hidden'). */
    private fun updateMarker(point: LatLng) {
        val marker = mMarker
        if (marker == null) {
            mMarker = mMap.addMarker(MarkerOptions().position(point))
        } else {
            marker.position = point
        }
    }
    private fun removeMarker() {
        mMarker?.remove()
        mMarker = null
    }
    override fun initializeMap() {
        // OpenFreeMap (OpenStreetMap data): free vector tiles, no account, no API key.
        MapLibre.getInstance(this)
        // When Android recreates this screen, the map fragment is restored automatically.
        // Reuse it instead of stacking a second map on top (taps went to one map while the
        // other one was drawn, so the marker seemed to vanish).
        val existing = supportFragmentManager.findFragmentById(R.id.map) as? SupportMapFragment
        val mapFragment = existing ?: SupportMapFragment.newInstance().also {
            supportFragmentManager.beginTransaction()
                .replace(R.id.map, it)
                .commit()
        }
        mapFragment.getMapAsync(this)
    }
    override fun onPositionChangedExternally(lat: Double, lon: Double) {
        if (!::mMap.isInitialized) return
        val p = LatLng(lat, lon)
        mLatLng = p
        updateMarker(p)
        mMap.moveCamera(CameraUpdateFactory.newLatLng(p))
    }
    override fun moveMapToNewLocation(moveNewLocation: Boolean) {
        if (moveNewLocation) {
            mLatLng = LatLng(lat, lon)
            mLatLng.let { latLng ->
                // mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng!!, 12.0f.toDouble()))
                mMap.animateCamera(CameraUpdateFactory.newCameraPosition(
                        CameraPosition.Builder()
                        .target(latLng!!)
                        .zoom(12.0f.toDouble())
                        .bearing(0f.toDouble())
                        .tilt(0f.toDouble())
                        .build()
                ))
                updateMarker(latLng)
            }
        }
    }
    override fun onMapReady(mapLibreMap: MapLibreMap) {
        mMap = mapLibreMap
        mMarker = null // any old marker belonged to a previous map instance
        with(mMap){


            // maplibre custom ui
            // OpenFreeMap styles (no key). The foss map type list is Liberty, Bright,
            // Positron, Dark (res/values/arrays.xml of the foss flavor).
            val typeUrl = "https://tiles.openfreemap.org/styles/" + when (viewModel.mapType) {
                2 -> "bright"
                3 -> "positron"
                4 -> "dark"
                else -> "liberty"
            }
            setStyle(typeUrl) { style ->
                if (ActivityCompat.checkSelfPermission(this@MapActivity, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) { 
                    val locationComponent = mMap.locationComponent
                    locationComponent.activateLocationComponent(
                        LocationComponentActivationOptions.builder(this@MapActivity, style)
                        .useDefaultLocationEngine(true)
                        .build()
                    )
                    locationComponent.isLocationComponentEnabled = true
                    // Show the real position as a dot, but do NOT follow it: TRACKING kept pulling
                    // the camera back while the user tried to pick a point.
                    locationComponent.cameraMode = CameraMode.NONE
                    locationComponent.renderMode = RenderMode.COMPASS
                } else {
                    ActivityCompat.requestPermissions(this@MapActivity, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 99);
                }
            }
            // TODO: fix bug with drawer
            uiSettings.setAllGesturesEnabled(true)
            uiSettings.setCompassEnabled(true)
            uiSettings.setCompassMargins(0,480,120,0)
            uiSettings.setLogoEnabled(true)
            uiSettings.setLogoMargins(0,0,0,80)
            // OpenStreetMap / OpenFreeMap attribution is required by the data license (ODbL).
            uiSettings.setAttributionEnabled(true)
            uiSettings.setAttributionMargins(
                uiSettings.attributionMarginLeft, uiSettings.attributionMarginTop,
                uiSettings.attributionMarginRight, 80
            )


            val zoom = 12.0f
            lat = viewModel.getLat
            lon  = viewModel.getLng
            val start = LatLng(lat, lon)
            mLatLng = start
            // Same as the full build: marker visible when spoofing runs, otherwise after a tap.
            if (viewModel.isStarted) updateMarker(start)
            mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(start, zoom.toDouble()))

            addOnMapClickListener(this@MapActivity)
            addOnMapLongClickListener { onMapLongPressed(it.latitude, it.longitude); true }
            onMapLoaded()
        }
    }
    override fun onMapClick(latLng: LatLng): Boolean {
        // Upstream only moved an existing marker, but the foss marker is created lazily,
        // so the first tap never showed one. Create or move it on every tap.
        mLatLng = latLng
        updateMarker(latLng)
        mMap.animateCamera(CameraUpdateFactory.newLatLng(latLng))
        lat = latLng.latitude
        lon = latLng.longitude
        onMapTapped(latLng.latitude, latLng.longitude)
        return true
    }



    override fun getActivityInstance(): BaseMapActivity {
        return this@MapActivity
    }

    @SuppressLint("MissingPermission")
    override fun setupButtons(){
        binding.addfavorite.setOnClickListener {
            addFavoriteDialog()
        }
        binding.getlocation.setOnClickListener {
            getLastLocation()
        }

        if (viewModel.isStarted) {
            binding.startButton.visibility = View.GONE
            binding.stopButton.visibility = View.VISIBLE
        }

        binding.startButton.setOnClickListener {
            val point = mLatLng ?: return@setOnClickListener // map not ready yet
            viewModel.update(true, point.latitude, point.longitude)
            updateMarker(point)
            binding.startButton.visibility = View.GONE
            binding.stopButton.visibility = View.VISIBLE
            lifecycleScope.launch {
                mLatLng?.getAddress(getActivityInstance())?.let { address ->
                    address.collect{ value ->
                        showStartNotification(value)
                    }
                }
            }
            showToast(getString(R.string.location_set))
        }
        binding.stopButton.setOnClickListener {
            val point = mLatLng ?: return@setOnClickListener // map not ready yet
            viewModel.update(false, point.latitude, point.longitude)
            removeMarker()
            binding.stopButton.visibility = View.GONE
            binding.startButton.visibility = View.VISIBLE
            cancelNotification()
            showToast(getString(R.string.location_unset))
        }
    }
}
