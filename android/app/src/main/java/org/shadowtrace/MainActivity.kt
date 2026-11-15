package org.shadowtrace

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.util.UUID
import java.util.concurrent.Executors

/** Foreground-only collector. Never claims that missing scans imply safety. */
class MainActivity : Activity(), LocationListener {
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val session = UUID.randomUUID().toString()
    private lateinit var wifi: WifiManager
    private lateinit var locations: LocationManager
    private lateinit var screen: MonitorScreen
    private lateinit var outbox: Outbox
    private var location: Location? = null
    private var running = false
    private var busy = false
    private var deviceId = ""
    private var receiverRegistered = false
    private var nextScanAt = 0L
    private var scanNote = "Scans begin when you start a session"
    private val scanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (running && intent.action == WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) {
                scanNote = if (intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false))
                    "Android delivered scan results" else "Android has no new scan yet"
                collect()
            }
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        outbox = Outbox(applicationContext)
        wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        locations = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val prefs = getSharedPreferences("collector", MODE_PRIVATE)
        deviceId = prefs.getString("device", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("device", it).apply()
        }
        screen = MonitorScreen(this, deviceId, onRefresh = { requestScan() }) {
            if (running) { stop(); screen.message("Paused. Start monitoring again for current coverage.") }
            else start()
        }
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
            android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        setContentView(screen)
    }

    private fun permitted() = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun start() {
        if (!permitted()) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION), 1)
            return
        }
        if (running) return
        if (!locations.isLocationEnabled || !wifi.isWifiEnabled) {
            screen.message("Enable location services and Wi-Fi — insufficient monitoring coverage")
            return
        }
        if (screen.token.text.isBlank()) { screen.message("Enter the backend API token in Setup"); screen.showSetup(); return }
        val uri = runCatching { URI(screen.endpoint.text.toString()) }.getOrNull()
        if (uri == null || uri.host == null || uri.scheme !in listOf("https", "http")) {
            screen.message("Enter a valid backend URL in Setup"); screen.showSetup(); return
        }
        try {
            // No last-known location: only live fixes can enter a batch.
            for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                if (locations.isProviderEnabled(provider))
                    locations.requestLocationUpdates(provider, 2000L, 0f, this)
            }
            running = true
            screen.setMonitoring(true)
            if (android.os.Build.VERSION.SDK_INT >= 33)
                registerReceiver(scanReceiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION), RECEIVER_EXPORTED)
            else registerReceiver(scanReceiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))
            receiverRegistered = true
            requestScan()
            handler.post(poll)
            handler.post(heartbeat)
        } catch (error: SecurityException) {
            stop(); screen.message("Precise location permission required")
        }
    }

    override fun onRequestPermissionsResult(request: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(request, permissions, results)
        if (request == 1 && permitted()) start()
        else screen.message("Precise location permission required — insufficient monitoring coverage")
    }

    override fun onLocationChanged(value: Location) { location = value }
    override fun onProviderEnabled(provider: String) = Unit
    override fun onProviderDisabled(provider: String) { location = null }
    @Deprecated("Required for Android 10 compatibility")
    override fun onStatusChanged(provider: String?, value: Int, extras: Bundle?) = Unit

    private val poll = object : Runnable {
        override fun run() {
            if (!running) return
            collect()
            handler.postDelayed(this, 5000)
        }
    }

    private val heartbeat = object : Runnable {
        override fun run() {
            if (!running) return
            val remaining = ((nextScanAt - SystemClock.elapsedRealtime()).coerceAtLeast(0) + 999) / 1000
            if (remaining == 0L) requestScan()
            screen.scanSchedule(((nextScanAt - SystemClock.elapsedRealtime()).coerceAtLeast(0) + 999) / 1000, scanNote)
            screen.tick()
            handler.postDelayed(this, 1000)
        }
    }

    @Suppress("DEPRECATION")
    private fun requestScan() {
        if (!running) { screen.message("Start monitoring first to request fresh scans."); return }
        val now = SystemClock.elapsedRealtime()
        val prefs = getSharedPreferences("collector", MODE_PRIVATE)
        val last = prefs.getLong("last_scan_elapsed", -35000L)
        // Persist the budget across app restarts; elapsed clock resets on reboot.
        val remaining = if (now >= last) (35000 - (now-last)).coerceAtLeast(0) else 0
        if (remaining > 0) {
            nextScanAt = now + remaining
            screen.scanSchedule((remaining+999)/1000, "Waiting for the scan request cooldown")
            return
        }
        prefs.edit().putLong("last_scan_elapsed", now).apply()
        nextScanAt = now + 35000
        try {
            scanNote = if (wifi.startScan()) "Scan requested · waiting for Android"
                else "Android delayed the scan · retrying after cooldown"
            screen.scanSchedule(35, scanNote)
        } catch (error: SecurityException) {
            stop(); screen.message("Precise location permission is required. Check app permissions.")
        }
    }

    @Suppress("DEPRECATION")
    private fun collect() {
        try {
            val elapsedUs = SystemClock.elapsedRealtimeNanos() / 1000
            val points = JSONArray()
            val cached = wifi.scanResults.distinctBy { it.BSSID }.filter {
                !it.BSSID.isNullOrBlank() && it.BSSID.lowercase() !in listOf("02:00:00:00:00:00", "00:00:00:00:00:00", "ff:ff:ff:ff:ff:ff")
            }
            val visible = JSONArray()
            cached.forEach { ap ->
                val age = elapsedUs - ap.timestamp
                val name = scanName(ap)
                if (age >= 0) visible.put(JSONObject().put("bssid",ap.BSSID).put("ssid",name)
                    .put("rssi_dbm",ap.level).put("age_seconds",age/1e6))
                if (age in 0L..30_000_000L && !ap.BSSID.isNullOrBlank() &&
                    ap.BSSID.lowercase() !in listOf("02:00:00:00:00:00", "00:00:00:00:00:00", "ff:ff:ff:ff:ff:ff")) {
                    points.put(JSONObject().put("bssid", ap.BSSID).put("ssid", name)
                        .put("timestamp_us", ap.timestamp).put("rssi_dbm", ap.level)
                        .put("frequency_mhz", ap.frequency).put("capabilities", ap.capabilities))
                }
            }
            screen.localSnapshot(visible, location)
            // Local feedback keeps updating even while a previous upload is in flight.
            if (busy) return
            val batch = JSONObject().put("device_id", deviceId).put("session_id", session)
                .put("captured_at", System.currentTimeMillis() / 1000.0).put("elapsed_us", elapsedUs)
                .put("mode", screen.selectedMode).put("access_points", points)
            location?.let { fix ->
                val ageMs = (elapsedUs - fix.elapsedRealtimeNanos / 1000) / 1000
                if (ageMs in 0L..15000L && fix.hasAccuracy()) {
                    batch.put("location", JSONObject().put("latitude", fix.latitude)
                        .put("longitude", fix.longitude).put("accuracy_m", fix.accuracy.toDouble())
                        .put("age_ms", ageMs).apply {
                            if (fix.hasSpeed() && fix.hasSpeedAccuracy() && fix.speedAccuracyMetersPerSecond <= 2)
                                put("speed_mps", fix.speed.toDouble())
                        })
                }
            }
            val base = screen.endpoint.text.toString().trimEnd('/')
            val bearer = screen.token.text.toString()
            busy = true
            worker.execute {
                val result = runCatching {
                    fun drain() {
                        // Server-side batch identity makes retries safe if an acknowledgement is lost.
                        for (i in 0 until 10) {
                            val queued = outbox.first() ?: break
                            request("$base/v1/scans", bearer, queued.second)
                            outbox.acknowledge(queued.first)
                        }
                    }
                    try { outbox.add(batch.toString()) }
                    catch (full: IllegalStateException) {
                        drain() // A full queue must still be able to recover on reconnection.
                        outbox.add(batch.toString())
                    }
                    drain()
                    JSONObject(request("$base/v1/devices/$deviceId/status", bearer)).put("queued_batches",outbox.count())
                }
                runOnUiThread {
                    busy = false
                    if (running) result.fold(
                        onSuccess = { screen.render(it) },
                        onFailure = { screen.message(connectionHelp(it)) }
                    )
                }
            }
        } catch (error: SecurityException) {
            stop(); screen.message("Permission revoked — insufficient monitoring coverage")
        }
    }

    @Suppress("DEPRECATION")
    private fun scanName(ap: android.net.wifi.ScanResult): String {
        val modern = if (android.os.Build.VERSION.SDK_INT >= 33) runCatching {
            ap.wifiSsid?.bytes?.let { bytes ->
                Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
            }
        }.getOrNull() else null
        return listOfNotNull(modern,ap.SSID).firstOrNull {
            it.isNotBlank() && !it.equals("<unknown ssid>",ignoreCase = true) && it.any { character -> character != '\u0000' }
        } ?: ""
    }

    private fun request(url: String, bearer: String, body: String? = null): String {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 4000
            connection.readTimeout = 4000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Authorization", "Bearer $bearer")
            if (body != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            if (connection.responseCode !in 200..299)
                error("HTTP ${connection.responseCode}")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally { connection.disconnect() }
    }

    private fun connectionHelp(error: Throwable): String = when {
        error.message?.contains("401") == true -> "Connection needs attention: the API token was rejected. Update it in Setup after pausing."
        error.message?.contains("409") == true -> "Upload queue needs attention: the server rejected an older or conflicting batch. Check the backend logs."
        error.message?.contains("503") == true -> "Server not configured: set its API key, then retry."
        else -> "Connection interrupted. Saved batches will retry. Check that the backend is running and the server URL is reachable."
    }

    private fun stop() {
        running = false
        handler.removeCallbacks(poll)
        handler.removeCallbacks(heartbeat)
        if (receiverRegistered) { unregisterReceiver(scanReceiver); receiverRegistered = false }
        locations.removeUpdates(this)
        location = null
        if (::screen.isInitialized) screen.setMonitoring(false)
    }
    override fun onPause() { stop(); super.onPause() }
    override fun onDestroy() { worker.shutdown(); super.onDestroy() }
}
