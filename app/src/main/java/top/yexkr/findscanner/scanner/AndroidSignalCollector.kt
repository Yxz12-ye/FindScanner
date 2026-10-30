package top.yexkr.findscanner.scanner

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import top.yexkr.findscanner.data.*
import java.util.UUID

interface SignalCollector {
    fun start()
    fun stop()
    fun snapshot(config: ConnectionConfig, sessionId: String, window: SamplingWindow, nowElapsed: Long): SignalFrame
    fun deviceStatus(): String
}

@SuppressLint("MissingPermission")
class AndroidSignalCollector(private val context: Context) : SignalCollector {
    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
    private val bluetooth = context.getSystemService(BluetoothManager::class.java)
    private val locations = context.getSystemService(LocationManager::class.java)
    private val ble = mutableMapOf<String, Pair<ScanResult, Long>>()
    private var scanner: android.bluetooth.le.BluetoothLeScanner? = null
    private var lastLocation: Location? = null
    private var wifiError: String? = null
    private var bleError: String? = null
    private var gpsError: String? = null
    private var lastWifiRequest = -30_000L
    private var pendingWifiAt: Long? = null
    private var receiverRegistered = false
    private var active = false

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (active) synchronized(ble) { ble[result.device.address] = result to (result.timestampNanos / 1_000_000) }
        }
        override fun onBatchScanResults(results: MutableList<ScanResult>) { results.forEach { onScanResult(0, it) } }
        override fun onScanFailed(errorCode: Int) { bleError = "scan_failed:$errorCode" }
    }
    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (active && (lastLocation == null || location.elapsedRealtimeNanos > lastLocation!!.elapsedRealtimeNanos)) lastLocation = location
        }
        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit
        @Deprecated("Legacy Android callback") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    }
    private val wifiReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            pendingWifiAt = null
            wifiError = if (intent?.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false) == true) null else "scan_not_updated"
        }
    }

    override fun start() {
        stop(); active = true
        bleError = null; gpsError = null; wifiError = null
        try {
            ContextCompat.registerReceiver(context, wifiReceiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION), ContextCompat.RECEIVER_EXPORTED)
            receiverRegistered = true
            requestWifi(SystemClock.elapsedRealtime())
        } catch (e: Exception) { wifiError = e.javaClass.simpleName }
        try {
            if (blePermission() && bluetooth?.adapter?.isEnabled == true) {
                scanner = bluetooth.adapter.bluetoothLeScanner
                scanner?.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCallback)
            }
        } catch (e: Exception) { bleError = e.javaClass.simpleName }
        try {
            if (locationPermission()) {
                listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter { locations?.isProviderEnabled(it) == true }.forEach { provider ->
                    val cached = locations.getLastKnownLocation(provider)
                    if (cached != null && (lastLocation == null || cached.elapsedRealtimeNanos > lastLocation!!.elapsedRealtimeNanos)) lastLocation = cached
                    locations.requestLocationUpdates(provider, 1_000L, 0f, locationListener, Looper.getMainLooper())
                }
            }
        } catch (e: Exception) { gpsError = e.javaClass.simpleName }
    }
    override fun stop() {
        active = false
        runCatching { scanner?.stopScan(scanCallback) }; scanner = null
        runCatching { locations?.removeUpdates(locationListener) }
        if (receiverRegistered) runCatching { context.unregisterReceiver(wifiReceiver) }
        receiverRegistered = false; pendingWifiAt = null
        synchronized(ble) { ble.clear() }
    }
    private fun locationPermission() = context.hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) || context.hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
    private fun blePermission(): Boolean {
        val locationAllowed = if (Build.VERSION.SDK_INT >= 29) context.hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) else locationPermission()
        return locationAllowed && (Build.VERSION.SDK_INT < 31 || context.hasPermission(Manifest.permission.BLUETOOTH_SCAN) && context.hasPermission(Manifest.permission.BLUETOOTH_CONNECT))
    }
    private fun locationEnabled(): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= 28) locations?.isLocationEnabled == true
        else locations?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true || locations?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true
    }.getOrDefault(false)
    @Suppress("DEPRECATION")
    private fun requestWifi(now: Long) {
        if (now - lastWifiRequest < 30_000 || !context.hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) || !locationEnabled() || wifi?.isWifiEnabled != true) return
        lastWifiRequest = now
        if (wifi.startScan()) pendingWifiAt = now else wifiError = "scan_throttled_or_failed"
    }

    @Suppress("DEPRECATION")
    override fun snapshot(config: ConnectionConfig, sessionId: String, window: SamplingWindow, nowElapsed: Long): SignalFrame {
        val endedAt = window.endedAt(nowElapsed)
        var wifiSignals = emptyList<RadioSignal>()
        var bleSignals = emptyList<RadioSignal>()
        var gps: GpsFix? = null
        val wifiStatus = try {
            when {
                !context.hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) -> SensorStatus("permission_denied", "precise_location_required")
                wifi == null -> SensorStatus("unavailable", "hardware_missing")
                !wifi.isWifiEnabled -> SensorStatus("disabled", "wifi_off")
                !locationEnabled() -> SensorStatus("disabled", "location_services_off")
                else -> {
                    if (pendingWifiAt?.let { nowElapsed - it >= 12_000 } == true) { pendingWifiAt = null; wifiError = "scan_timeout" }
                    wifiSignals = wifi.scanResults.filter { it.BSSID != null && it.BSSID != "02:00:00:00:00:00" && it.timestamp > 0 && it.timestamp / 1_000 <= nowElapsed }.map { result ->
                        val observedElapsed = result.timestamp / 1_000
                        val age = (nowElapsed - observedElapsed).coerceAtLeast(0)
                        RadioSignal(result.BSSID, result.level, endedAt - age, age, observedElapsed in window.startedElapsed..nowElapsed,
                            result.SSID?.takeIf { it.isNotBlank() }, result.frequency)
                    }.distinctBy { it.mac }.sortedByDescending { it.rssi }
                    val status = SensorStatus(if (wifiSignals.isEmpty()) "empty" else if (wifiSignals.any { it.fresh }) "ok" else "cached", wifiError)
                    requestWifi(nowElapsed)
                    status
                }
            }
        } catch (e: SecurityException) { SensorStatus("permission_denied", e.javaClass.simpleName) }
        catch (e: Exception) { SensorStatus("error", e.javaClass.simpleName) }
        val bleStatus = try {
            when {
                !blePermission() -> SensorStatus("permission_denied", "scan_permission_required")
                bluetooth?.adapter == null -> SensorStatus("unavailable", "hardware_missing")
                bluetooth.adapter.isEnabled != true -> SensorStatus("disabled", "bluetooth_off")
                Build.VERSION.SDK_INT < 31 && !locationEnabled() -> SensorStatus("disabled", "location_services_off")
                bleError != null -> SensorStatus("error", bleError)
                scanner == null -> SensorStatus("unavailable", "restart_scan_required")
                else -> {
                    synchronized(ble) {
                        bleSignals = ble.values.filter { it.second in window.startedElapsed..nowElapsed }.map { (result, elapsed) ->
                            val age = (nowElapsed - elapsed).coerceAtLeast(0)
                            RadioSignal(result.device.address, result.rssi, endedAt - age, age, true, result.scanRecord?.deviceName)
                        }.sortedByDescending { it.rssi }
                        ble.entries.removeAll { it.value.second <= nowElapsed }
                    }
                    SensorStatus(if (bleSignals.isEmpty()) "empty" else "ok")
                }
            }
        } catch (e: SecurityException) { SensorStatus("permission_denied", e.javaClass.simpleName) }
        catch (e: Exception) { SensorStatus("error", e.javaClass.simpleName) }
        val gpsStatus = try {
            val fix = lastLocation
            when {
                !locationPermission() -> SensorStatus("permission_denied", "location_permission_required")
                locations == null -> SensorStatus("unavailable", "hardware_missing")
                !locationEnabled() -> SensorStatus("disabled", "location_services_off")
                gpsError != null -> SensorStatus("error", gpsError)
                fix == null -> SensorStatus("unavailable", "no_fix")
                nowElapsed - fix.elapsedRealtimeNanos / 1_000_000 > 60_000 -> SensorStatus("unavailable", "stale_fix")
                !fix.hasAccuracy() -> SensorStatus("unavailable", "accuracy_missing")
                else -> {
                    gps = GpsFix(fix.latitude, fix.longitude, fix.accuracy, fix.time, if (fix.hasAltitude()) fix.altitude else null)
                    SensorStatus("ok", if (!context.hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) "approximate_location" else null)
                }
            }
        } catch (e: Exception) { SensorStatus("error", e.javaClass.simpleName) }
        return SignalFrame(UUID.randomUUID().toString(), sessionId, config.region, config.deviceId,
            window.startedAt, endedAt, wifiSignals, bleSignals, gps, SensorStatuses(wifiStatus, bleStatus, gpsStatus))
    }

    override fun deviceStatus(): String {
        val wifiText = runCatching { if (wifi?.isWifiEnabled == true) "开启" else "关闭/不支持" }.getOrDefault("无权限")
        val bleText = runCatching { if (bluetooth?.adapter?.isEnabled == true) "开启" else "关闭/不支持" }.getOrDefault("无权限")
        return "Wi-Fi $wifiText · 蓝牙 $bleText · 定位服务${if (locationEnabled()) "开启" else "关闭"}"
    }
}
