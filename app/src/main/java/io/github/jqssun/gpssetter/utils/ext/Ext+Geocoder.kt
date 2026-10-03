package io.github.jqssun.gpssetter.utils.ext

import android.location.Address
import android.location.Geocoder
import android.os.Build
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// Android 13+ has callback-based Geocoder lookups; the blocking ones are deprecated there.
// Below 13 only the blocking calls exist (callers run them on Dispatchers.IO).

suspend fun Geocoder.findByName(name: String, max: Int): List<Address> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        suspendCancellableCoroutine { cont ->
            getFromLocationName(name, max, object : Geocoder.GeocodeListener {
                override fun onGeocode(addresses: MutableList<Address>) = cont.resume(addresses)
                override fun onError(errorMessage: String?) =
                    cont.resumeWithException(IOException(errorMessage ?: "Geocoder error"))
            })
        }
    } else {
        @Suppress("DEPRECATION")
        getFromLocationName(name, max) ?: emptyList()
    }

suspend fun Geocoder.findByLocation(latitude: Double, longitude: Double, max: Int): List<Address> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        suspendCancellableCoroutine { cont ->
            getFromLocation(latitude, longitude, max, object : Geocoder.GeocodeListener {
                override fun onGeocode(addresses: MutableList<Address>) = cont.resume(addresses)
                override fun onError(errorMessage: String?) =
                    cont.resumeWithException(IOException(errorMessage ?: "Geocoder error"))
            })
        }
    } else {
        @Suppress("DEPRECATION")
        getFromLocation(latitude, longitude, max) ?: emptyList()
    }
