package io.github.jqssun.gpssetter.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.RadioButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.view.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.withCreated
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.location.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.elevation.ElevationOverlayProvider
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import io.github.jqssun.gpssetter.BuildConfig
import io.github.jqssun.gpssetter.R
import io.github.jqssun.gpssetter.adapter.FavListAdapter
import io.github.jqssun.gpssetter.databinding.ActivityMapBinding
import io.github.jqssun.gpssetter.ui.viewmodel.MainViewModel
import io.github.jqssun.gpssetter.utils.JoystickService
import io.github.jqssun.gpssetter.utils.NotificationsChannel
import io.github.jqssun.gpssetter.utils.PrefManager
import io.github.jqssun.gpssetter.utils.RouteFinder
import io.github.jqssun.gpssetter.utils.RouteStore
import io.github.jqssun.gpssetter.utils.RoutePlaybackService
import io.github.jqssun.gpssetter.utils.Route
import io.github.jqssun.gpssetter.utils.ext.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import java.io.IOException
import java.util.regex.Matcher
import java.util.regex.Pattern
import kotlin.properties.Delegates

@AndroidEntryPoint
abstract class BaseMapActivity: AppCompatActivity() {

    protected var lat by Delegates.notNull<Double>()
    protected var lon by Delegates.notNull<Double>()
    protected val viewModel by viewModels<MainViewModel>()
    protected val binding by lazy { ActivityMapBinding.inflate(layoutInflater) }
    protected lateinit var alertDialog: MaterialAlertDialogBuilder
    protected lateinit var dialog: AlertDialog
    protected val update by lazy { viewModel.getAvailableUpdate() }

    private val notificationsChannel by lazy { NotificationsChannel() }
    private var favListAdapter: FavListAdapter = FavListAdapter()
    private var xposedDialog: AlertDialog? = null
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private val PERMISSION_ID = 42

    private val elevationOverlayProvider by lazy {
        ElevationOverlayProvider(this)
    }

    private val headerBackground by lazy {
        elevationOverlayProvider.compositeOverlayWithThemeSurfaceColorIfNeeded(
            resources.getDimension(R.dimen.bottom_sheet_elevation)
        )
    }

    protected abstract fun getActivityInstance(): BaseMapActivity
    protected abstract fun hasMarker(): Boolean
    protected abstract fun initializeMap()
    protected abstract fun setupButtons()
    protected abstract fun moveMapToNewLocation(moveNewLocation: Boolean)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))

        WindowCompat.setDecorFitsSystemWindows(window, false)
        lifecycleScope.launch {
            withCreated { setContentView(binding.root) }
        }
        setSupportActionBar(binding.toolbar)
        initializeMap()
        checkModuleEnabled()
        checkUpdates()
        setupNavView()
        setupButtons()
        setupDrawer()
        if (PrefManager.isJoystickEnabled && PrefManager.isStarted &&
            Settings.canDrawOverlays(this) && !JoystickService.isRunning) {
            ContextCompat.startForegroundService(this, Intent(this, JoystickService::class.java))
        }
    }

    private fun setupDrawer() {
        supportActionBar?.setDisplayShowTitleEnabled(false)
        val mDrawerToggle = object : ActionBarDrawerToggle(
            this,
            binding.container,
            binding.toolbar,
            R.string.drawer_open,
            R.string.drawer_close
        ) {
            override fun onDrawerClosed(view: View) {
                super.onDrawerClosed(view)
                invalidateOptionsMenu()
            }

            override fun onDrawerOpened(drawerView: View) {
                super.onDrawerOpened(drawerView)
                invalidateOptionsMenu()
            }
        }
        binding.container.addDrawerListener(mDrawerToggle)
    }

    private fun setupNavView() {

        ViewCompat.setOnApplyWindowInsetsListener(binding.mapContainer.map) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.navView.setPadding(0, bars.top, 0, 0)
            WindowInsetsCompat.CONSUMED
        }

        val progress = binding.search.searchProgress
        binding.search.searchBox.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                if (isNetworkConnected()) {
                    lifecycleScope.launch(Dispatchers.Main) {
                        val getInput = v.text.toString()
                        if (getInput.isNotEmpty()){
                            getSearchAddress(getInput).let {
                                it.collect { result ->
                                    when(result) {
                                        is SearchProgress.Progress -> {
                                            progress.visibility = View.VISIBLE
                                        }
                                        is SearchProgress.Complete -> {
                                            progress.visibility = View.GONE
                                            lat = result.lat
                                            lon = result.lon
                                            moveMapToNewLocation(true)
                                        }
                                        is SearchProgress.Fail -> {
                                            progress.visibility = View.GONE
                                            showToast(result.error!!)
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    showToast(getString(R.string.no_internet))
                }
                return@setOnEditorActionListener true
            }
            return@setOnEditorActionListener false
        }

        binding.navView.setNavigationItemSelectedListener {
            when(it.itemId){
                R.id.get_favorite -> {
                    openFavoriteListDialog()
                }
                R.id.routes -> {
                    openRoutesDialog()
                }
                R.id.settings -> {
                    startActivity(Intent(this,ActivitySettings::class.java))
                }
                R.id.help -> {
                    startActivity(Intent(this, HelpActivity::class.java))
                }
                R.id.about -> {
                    aboutDialog()
                }
            }
            binding.container.closeDrawer(GravityCompat.START)
            true
        }
    }

    private fun checkModuleEnabled(){
        viewModel.isXposed.observe(this) { isXposed ->
            xposedDialog?.dismiss()
            xposedDialog = null
            if (!isXposed) {
                xposedDialog = MaterialAlertDialogBuilder(this).run {
                    setTitle(R.string.error_xposed_module_missing)
                    setMessage(R.string.error_xposed_module_missing_desc)
                    // setCancelable(BuildConfig.DEBUG)
                    setCancelable(true)
                    show()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.updateXposedState()
        PrefManager.pref.registerOnSharedPreferenceChangeListener(positionListener)
        syncStartButtons()
        // Back in the app while a route/joystick runs: show where the point is right now.
        if (JoystickService.isRunning || RoutePlaybackService.isRunning) {
            lat = PrefManager.getLat
            lon = PrefManager.getLng
            onPositionChangedExternally(lat, lon)
        }
    }

    override fun onPause() {
        PrefManager.pref.unregisterOnSharedPreferenceChangeListener(positionListener)
        super.onPause()
    }

    /** Called when the point is moved from outside the map (the joystick). */
    protected open fun onPositionChangedExternally(lat: Double, lon: Double) {}

    /** ▶ / ■ always reflect the real state, also when a route started or stopped spoofing. */
    private fun syncStartButtons() {
        val started = PrefManager.isStarted
        binding.startButton.visibility = if (started) View.GONE else View.VISIBLE
        binding.stopButton.visibility = if (started) View.VISIBLE else View.GONE
    }

    // Lat and lon are written in one edit; reacting to longitude alone avoids doing it twice.
    private val positionListener =
        android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "start") {
                syncStartButtons()
                // ■ in the app stops a running route too (same as Stop in its notification).
                if (!PrefManager.isStarted && RoutePlaybackService.isRunning) {
                    PrefManager.playbackState = null
                    stopService(Intent(this, RoutePlaybackService::class.java))
                }
            }
            if (key == "longitude" && (JoystickService.isRunning || RoutePlaybackService.isRunning)) {
                lat = PrefManager.getLat
                lon = PrefManager.getLng
                onPositionChangedExternally(lat, lon)
            }
        }

    // ---------------------------------------------------------------- routes

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
        }
    }

    private fun openRoutesDialog() {
        ensureNotificationPermission()
        val routes = RouteStore.list(this)
        val builder = MaterialAlertDialogBuilder(this).setTitle(R.string.routes)
        if (routes.isEmpty()) {
            builder.setMessage(R.string.routes_empty)
        } else {
            val labels = routes.map {
                "${it.name}\n${RouteStore.formatDuration(it.durationMs)} · ${RouteStore.formatDistance(it.distanceMeters)}"
            }.toTypedArray()
            builder.setItems(labels) { _, which -> openRouteActions(routes[which]) }
        }
        if (RoutePlaybackService.isRunning) {
            builder.setNeutralButton(R.string.route_stop_playback) { _, _ ->
                PrefManager.playbackState = null // explicit stop: do not resume later
                stopService(Intent(this, RoutePlaybackService::class.java))
            }
        }
        builder.setPositiveButton(R.string.route_new) { _, _ -> newRouteDialog() }
        builder.setNegativeButton(android.R.string.cancel, null).show()
    }

    private data class Place(val lat: Double, val lon: Double, val label: String)

    // Start/destination picked by long-pressing the map; pre-fill the A -> B dialog.
    private var pickedFrom: String? = null
    private var pickedTo: String? = null

    private fun coordinates(lat: Double, lon: Double) =
        String.format(java.util.Locale.ROOT, "%.6f, %.6f", lat, lon)

    private fun copyToClipboard(text: String) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("coordinates", text))
        // Android 13+ shows its own "copied" confirmation.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) showToast(getString(R.string.coordinates_copied))
    }

    /** Tap: show the coordinates of the tapped point, with Copy. */
    protected fun onMapTapped(lat: Double, lon: Double) {
        val text = coordinates(lat, lon)
        Snackbar.make(binding.root, text, Snackbar.LENGTH_LONG)
            .setAction(R.string.copy) { copyToClipboard(text) }
            .show()
    }

    /** Long press: copy, or use the point as route start / destination (does not move the marker). */
    protected fun onMapLongPressed(lat: Double, lon: Double) {
        val text = coordinates(lat, lon)
        MaterialAlertDialogBuilder(this)
            .setTitle(text)
            .setItems(arrayOf(
                getString(R.string.copy),
                getString(R.string.route_set_start),
                getString(R.string.route_set_destination)
            )) { _, which ->
                when (which) {
                    0 -> copyToClipboard(text)
                    1 -> {
                        pickedFrom = text
                        showToast(getString(R.string.route_start_set, text))
                    }
                    2 -> {
                        pickedTo = text
                        newRouteDialog()
                    }
                }
            }
            .show()
    }

    private fun newRouteDialog() {
        val pad = (20 * resources.displayMetrics.density).toInt()
        val from = EditText(this).apply {
            hint = getString(R.string.route_from_hint); isSingleLine = true; setText(pickedFrom ?: "")
        }
        val to = EditText(this).apply {
            hint = getString(R.string.route_to_hint); isSingleLine = true; setText(pickedTo ?: "")
        }
        val modes = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val modeIds = RouteFinder.Mode.entries.map { mode ->
            RadioButton(this).apply {
                id = View.generateViewId()
                text = getString(modeLabel(mode))
                tag = mode
            }.also { modes.addView(it) }.id
        }
        modes.check(modeIds.first())
        val modeHint = TextView(this).apply {
            text = getString(R.string.route_mode_car_hint)
            textSize = 12f
        }
        modes.setOnCheckedChangeListener { group, checkedId ->
            val isCar = group.findViewById<View>(checkedId)?.tag == RouteFinder.Mode.CAR
            modeHint.visibility = if (isCar) View.VISIBLE else View.GONE
        }
        val credit = TextView(this).apply {
            text = getString(R.string.route_attribution)
            textSize = 12f
            setPadding(0, pad / 2, 0, 0)
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(from); addView(to); addView(modes); addView(modeHint); addView(credit)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.route_new)
            .setView(layout)
            .setPositiveButton(R.string.route_create) { _, _ ->
                val mode = modes.findViewById<View>(modes.checkedRadioButtonId)?.tag as? RouteFinder.Mode
                    ?: RouteFinder.Mode.CAR
                createRoute(from.text.toString().trim(), to.text.toString().trim(), mode)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun modeLabel(mode: RouteFinder.Mode) = when (mode) {
        RouteFinder.Mode.CAR -> R.string.route_mode_car
        RouteFinder.Mode.BIKE -> R.string.route_mode_bike
        RouteFinder.Mode.FOOT -> R.string.route_mode_foot
    }

    private fun createRoute(fromText: String, toText: String, mode: RouteFinder.Mode) {
        if (toText.isEmpty()) {
            showToast(getString(R.string.route_need_destination))
            return
        }
        showToast(getString(R.string.route_calculating))
        lifecycleScope.launch {
            try {
                val a = if (fromText.isEmpty())
                    Place(PrefManager.getLat, PrefManager.getLng, getString(R.string.route_current_point))
                else resolvePlace(fromText)
                val b = resolvePlace(toText)
                val points = RouteFinder.find(a.lat, a.lon, b.lat, b.lon, mode)
                // Real way back (one-way streets differ); FOSSGIS allows 1 request/s.
                delay(1100)
                val back = try {
                    RouteFinder.find(b.lat, b.lon, a.lat, a.lon, mode)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null // round trip falls back to the outward path in reverse
                }
                val name = "${a.label} → ${b.label} (${getString(modeLabel(mode))})"
                val route = withContext(Dispatchers.IO) {
                    RouteStore.save(this@BaseMapActivity, name, points, back)
                }
                pickedFrom = null
                pickedTo = null
                MaterialAlertDialogBuilder(this@BaseMapActivity)
                    .setTitle(route.name)
                    .setMessage(getString(R.string.route_created,
                        RouteStore.formatDistance(route.distanceMeters),
                        RouteStore.formatDuration(route.durationMs)))
                    .setPositiveButton(R.string.route_play) { _, _ -> playRoute(route, false) }
                    .setNegativeButton(R.string.route_later, null)
                    .show()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showToast(getString(R.string.route_failed, e.message ?: e.javaClass.simpleName))
            }
        }
    }

    /** "lat, lon" or an address/place name (Geocoder). */
    private suspend fun resolvePlace(text: String): Place {
        val m = Regex("""^\s*([-+]?\d{1,3}(?:\.\d+)?)\s*,\s*([-+]?\d{1,3}(?:\.\d+)?)\s*$""").find(text)
        if (m != null) return Place(m.groupValues[1].toDouble(), m.groupValues[2].toDouble(), text)
        val geo = try {
            withContext(Dispatchers.IO) { Geocoder(this@BaseMapActivity).findByName(text, 1) }.firstOrNull()
        } catch (e: IOException) {
            null
        }
        if (geo != null) return Place(geo.latitude, geo.longitude, text)
        val osm = RouteFinder.searchPlace(text)
            ?: throw IOException(getString(R.string.route_place_not_found, text))
        return Place(osm.first, osm.second, text)
    }

    private fun openRouteActions(route: Route) {
        val actions = arrayOf(
            getString(R.string.route_play),
            getString(R.string.route_play_loop),
            getString(R.string.route_round_trip),
            getString(R.string.route_rename),
            getString(R.string.route_delete)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(route.name)
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> playRoute(route, false)
                    1 -> playRoute(route, true)
                    2 -> chooseStay(route)
                    3 -> renameRoute(route)
                    4 -> {
                        RouteStore.delete(this, route.id)
                        showToast(getString(R.string.route_deleted))
                    }
                }
            }
            .show()
    }

    /** Round trip: pick how long to stay at the destination before coming back. */
    private fun chooseStay(route: Route) {
        val minutes = intArrayOf(1, 3, 5, 10, 20, 30, 60, 120, 240, 360, 720, 1440)
        val labels = minutes.map {
            if (it < 60) getString(R.string.duration_minutes, it) else getString(R.string.duration_hours, it / 60)
        }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.route_stay_title)
            .setItems(labels) { _, which -> playRoute(route, false, minutes[which] * 60_000L) }
            .show()
    }

    private fun playRoute(route: Route, loop: Boolean, stayMs: Long = -1L) {
        route.points.firstOrNull()?.let {
            lat = it.lat
            lon = it.lon
            moveMapToNewLocation(true)
        }
        ContextCompat.startForegroundService(
            this,
            Intent(this, RoutePlaybackService::class.java)
                .putExtra(RoutePlaybackService.EXTRA_ROUTE_ID, route.id)
                .putExtra(RoutePlaybackService.EXTRA_LOOP, loop)
                .putExtra(RoutePlaybackService.EXTRA_STAY_MS, stayMs)
        )
    }

    private fun renameRoute(route: Route) {
        val input = EditText(this).apply { setText(route.name); setSelection(text.length) }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.route_rename)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) RouteStore.rename(this, route.id, name)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    protected fun aboutDialog(){
        alertDialog = MaterialAlertDialogBuilder(this)
        layoutInflater.inflate(R.layout.about,null).apply {
            val  titlele = findViewById<TextView>(R.id.design_about_title)
            val  version = findViewById<TextView>(R.id.design_about_version)
            val  info = findViewById<TextView>(R.id.design_about_info)
            titlele.text = getString(R.string.app_name)
            version.text = BuildConfig.VERSION_NAME
            info.text = getString(R.string.about_info)
        }.run {
            alertDialog.setView(this)
            alertDialog.show()
        }
    }

    protected fun addFavoriteDialog() {
        alertDialog =  MaterialAlertDialogBuilder(this).apply {
            val view = layoutInflater.inflate(R.layout.dialog,null)
            val editText = view.findViewById<EditText>(R.id.search_edittxt)
            setTitle(getString(R.string.add_fav_dialog_title))
            setPositiveButton(getString(R.string.dialog_button_add)) { _, _ ->
                val s = editText.text.toString()
                if (hasMarker()){
                  showToast(getString(R.string.location_not_select))
                }else{
                    viewModel.storeFavorite(s, lat, lon)
                    viewModel.response.observe(getActivityInstance()){
                        if (it == (-1).toLong()) showToast(getString(R.string.cant_save)) else showToast(getString(R.string.save))
                    }
                }
            }
            setView(view)
            show()
        }
    }

    private fun openFavoriteListDialog() {
        getAllUpdatedFavList()
        alertDialog = MaterialAlertDialogBuilder(this)
        alertDialog.setTitle(getString(R.string.favorites))
        val view = layoutInflater.inflate(R.layout.fav,null)
        val rcv = view.findViewById<RecyclerView>(R.id.favorites_list)
        rcv.layoutManager = LinearLayoutManager(this)
        rcv.adapter = favListAdapter
        favListAdapter.onItemClick = {
            it.let {
                lat = it.lat!!
                lon = it.lng!!
            }
            moveMapToNewLocation(true)
            if (dialog.isShowing) dialog.dismiss()

        }
        favListAdapter.onItemDelete = {
            viewModel.deleteFavorite(it)
        }
        alertDialog.setView(view)
        dialog = alertDialog.create()
        dialog.show()

    }

    private fun getAllUpdatedFavList(){
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED){
                viewModel.doGetUserDetails()
                viewModel.allFavList.collect {
                    favListAdapter.submitList(it)
                }
            }
        }

    }

    private var updateDialogShown = false

    private fun checkUpdates(){
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                viewModel.update.collect {
                    // Show the dialog once per activity, not again on every resume.
                    if (it != null && !updateDialogShown) {
                        updateDialogShown = true
                        updateDialog()
                    }
                }
            }
        }
    }

    private fun updateDialog(){
        alertDialog = MaterialAlertDialogBuilder(this)
        alertDialog.setTitle(R.string.update_available)
        alertDialog.setMessage(update?.changelog)
        alertDialog.setPositiveButton(getString(R.string.update_button)) { _, _ ->
            MaterialAlertDialogBuilder(this).apply {
                val view = layoutInflater.inflate(R.layout.update_dialog, null)
                val progress = view.findViewById<LinearProgressIndicator>(R.id.update_download_progress)
                val cancel = view.findViewById<AppCompatButton>(R.id.update_download_cancel)
                setView(view)
                cancel.setOnClickListener {
                    viewModel.cancelDownload(getActivityInstance())
                    dialog.dismiss()
                }
                lifecycleScope.launch {
                    viewModel.downloadState.collect {
                        when (it) {
                            is MainViewModel.State.Downloading -> {
                                if (it.progress > 0) {
                                    progress.isIndeterminate = false
                                    progress.progress = it.progress
                                }
                            }
                            is MainViewModel.State.Done -> {
                                viewModel.openPackageInstaller(getActivityInstance(), it.fileUri)
                                viewModel.clearUpdate()
                                dialog.dismiss()
                            }
                            is MainViewModel.State.Failed -> {
                                Toast.makeText(
                                    getActivityInstance(),
                                    R.string.bs_update_download_failed,
                                    Toast.LENGTH_LONG
                                ).show()
                                dialog.dismiss()

                            }
                            else -> {}
                        }
                    }
                }
                update?.let { it ->
                    viewModel.startDownload(getActivityInstance(), it)
                } ?: run {
                    dialog.dismiss()
                }
            }.run {
                dialog = create()
                dialog.show()
            }
        }
        dialog = alertDialog.create()
        dialog.show()
    }

    private suspend fun getSearchAddress(address: String) = callbackFlow {
        withContext(Dispatchers.IO){
            trySend(SearchProgress.Progress)
            val matcher: Matcher =
                Pattern.compile("[-+]?\\d{1,3}([.]\\d+)?, *[-+]?\\d{1,3}([.]\\d+)?").matcher(address)

            if (matcher.matches()){
                delay(3000)
                trySend(SearchProgress.Complete(matcher.group().split(",")[0].toDouble(),matcher.group().split(",")[1].toDouble()))
            }else {
                try {
                    // The lookup itself throws IOException without network, so it is inside the try.
                    // Upstream required exactly one match, so streets with several matches
                    // were reported as "not found". Take the best (first) match instead.
                    val first: Address? =
                        Geocoder(getActivityInstance()).findByName(address, 3).firstOrNull()
                    val osm = if (first == null) RouteFinder.searchPlace(address) else null
                    when {
                        first != null -> trySend(SearchProgress.Complete(first.latitude, first.longitude))
                        osm != null -> trySend(SearchProgress.Complete(osm.first, osm.second))
                        else -> trySend(SearchProgress.Fail(getString(R.string.address_not_found)))
                    }
                } catch (io : IOException){
                    trySend(SearchProgress.Fail(getString(R.string.no_internet)))
                }
            }
        }
        awaitClose { this.cancel() }
    }

    protected fun showStartNotification(address: String){
        notificationsChannel.showNotification(this){
            it.setSmallIcon(R.drawable.ic_stop)
            it.setContentTitle(getString(R.string.location_set))
            it.setContentText(address)
            it.setAutoCancel(true)
            // Upstream had no tap action; open the app like the route/joystick notifications.
            packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
                it.setContentIntent(android.app.PendingIntent.getActivity(
                    this, 2,
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
                ))
            }
            it.setCategory(Notification.CATEGORY_EVENT)
            it.priority = NotificationCompat.PRIORITY_HIGH
        }
    }

    protected fun cancelNotification(){
        notificationsChannel.cancelAllNotifications(this)
    }

    // Get current location
    @SuppressLint("MissingPermission")
    protected fun getLastLocation() {
        // While spoofing, "my location" is the simulated point (what other apps see).
        // The app itself is never hooked, so the fused provider would return the real position.
        if (PrefManager.isStarted) {
            lat = PrefManager.getLat
            lon = PrefManager.getLng
            moveMapToNewLocation(true)
            return
        }
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        if (checkPermissions()) {
            if (isLocationEnabled()) {
                // Always ask for a fresh fix: the cached "last location" can still be the
                // simulated point right after spoofing was stopped.
                showToast(getString(R.string.locating))
                requestNewLocationData()
            } else {
                showToast("Turn on location")
                val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                startActivity(intent)
            }
        } else {
            requestPermissions()
        }
    }

    /**
     * Called by both flavors once the map is ready. With spoofing off, the map opens on the
     * real current position (fresh fix) instead of the last simulated point. Quiet: no toast,
     * no permission prompt, no settings screen; the my-location button still does all that.
     */
    protected fun onMapLoaded() {
        if (!PrefManager.isStarted && checkPermissions() && isLocationEnabled()) {
            fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
            requestNewLocationData()
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestNewLocationData() {
        val mLocationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 0)
            .setMinUpdateIntervalMillis(0)
            .setMaxUpdates(1)
            .setMaxUpdateAgeMillis(0) // no cached fix
            .setDurationMillis(30_000)
            .build()

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        fusedLocationClient.requestLocationUpdates(
            mLocationRequest, mLocationCallback,
            Looper.myLooper()
        )
    }

    private val mLocationCallback = object : LocationCallback() {
        override fun onLocationResult(locationResult: LocationResult) {
            val mLastLocation: Location = locationResult.lastLocation ?: return
            lat = mLastLocation.latitude
            lon = mLastLocation.longitude
            moveMapToNewLocation(true) // upstream set lat/lon but never moved the map
        }
    }

    private fun isLocationEnabled(): Boolean {
        val locationManager: LocationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) || locationManager.isProviderEnabled(
            LocationManager.NETWORK_PROVIDER
        )
    }

    private fun checkPermissions(): Boolean {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        ) {
            return true
        }
        return false
    }

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION),
            PERMISSION_ID
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if ((grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)) {
            getLastLocation()
        }
    }
}

sealed class SearchProgress {
    object Progress : SearchProgress()
    data class Complete(val lat: Double , val lon : Double) : SearchProgress()
    data class Fail(val error: String?) : SearchProgress()
}
