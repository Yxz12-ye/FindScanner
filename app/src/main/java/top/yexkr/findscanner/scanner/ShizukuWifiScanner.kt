package top.yexkr.findscanner.scanner

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.*
import rikka.shizuku.Shizuku
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Owns one non-daemon privileged service. Only a fixed Wi-Fi scan command is exposed. */
interface PrivilegedWifiScanner {
    val enabled: Boolean
    var onUnavailable: (() -> Unit)?
    fun ready(): Boolean
    suspend fun enable()
    suspend fun startScan(): String
    fun disable()
    fun close()
}

class ShizukuWifiScanner(context: Context) : PrivilegedWifiScanner {
    private val args = Shizuku.UserServiceArgs(ComponentName(context, WifiScanService::class.java))
        .daemon(false).processNameSuffix("wifi_scan").tag("wifi_scan").version(1)
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var service: IWifiScanService? = null
    override var enabled = false
        private set
    override var onUnavailable: (() -> Unit)? = null
    private var connection: ServiceConnection? = null
    private val dead = Shizuku.OnBinderDeadListener { main.post { unavailable() } }

    init { Shizuku.addBinderDeadListener(dead) }

    override fun ready(): Boolean = runCatching {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED &&
            service?.asBinder()?.isBinderAlive == true
    }.getOrDefault(false)

    override suspend fun enable() {
        check(Shizuku.pingBinder()) { "Shizuku 未运行，请先安装并启动 Shizuku" }
        check(!Shizuku.isPreV11() && Shizuku.getVersion() >= 12) { "请更新 Shizuku 至 v12 或更高版本" }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            check(!Shizuku.shouldShowRequestPermissionRationale()) { "Shizuku 授权已被拒绝，请在 Shizuku 中允许本应用" }
            val granted = withTimeoutOrNull(60_000) {
                suspendCancellableCoroutine { continuation ->
                    val listener = object : Shizuku.OnRequestPermissionResultListener {
                        override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                            if (requestCode != REQUEST_CODE) return
                            Shizuku.removeRequestPermissionResultListener(this)
                            if (continuation.isActive) continuation.resume(grantResult == PackageManager.PERMISSION_GRANTED)
                        }
                    }
                    Shizuku.addRequestPermissionResultListener(listener)
                    continuation.invokeOnCancellation { Shizuku.removeRequestPermissionResultListener(listener) }
                    try { Shizuku.requestPermission(REQUEST_CODE) }
                    catch (error: Exception) {
                        Shizuku.removeRequestPermissionResultListener(listener)
                        if (continuation.isActive) continuation.resumeWithException(IllegalStateException("Shizuku 授权请求失败", error))
                    }
                }
            }
            check(granted == true) { if (granted == null) "Shizuku 授权超时，请重试" else "未获得 Shizuku 授权" }
        }
        if (!ready()) {
            disconnect()
            val bound = withTimeoutOrNull(15_000) {
                suspendCancellableCoroutine { continuation ->
                    val callback = object : ServiceConnection {
                        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                            if (connection !== this || !continuation.isActive) return
                            service = IWifiScanService.Stub.asInterface(binder)
                            if (continuation.isActive) continuation.resume(service != null)
                        }
                        override fun onServiceDisconnected(name: ComponentName?) {
                            main.post { if (connection === this) unavailable() }
                        }
                    }
                    connection = callback
                    continuation.invokeOnCancellation { main.post { disconnect() } }
                    try { Shizuku.bindUserService(args, callback) }
                    catch (error: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(IllegalStateException("Shizuku 扫描服务启动失败", error))
                    }
                }
            }
            check(bound == true) { "Shizuku 扫描服务启动超时，请重试" }
        }
        // Verify the actual command path before allowing navigation.
        val error = startScan()
        check(error.isEmpty()) { "Shizuku 扫描验证失败：$error" }
        enabled = true
    }

    override suspend fun startScan(): String = withContext(Dispatchers.IO) {
        if (!ready()) {
            main.post { unavailable() }
            return@withContext "shizuku_unavailable"
        }
        try { service!!.startScan() }
        catch (error: Exception) {
            main.post { unavailable() }
            "shizuku_disconnected:${error.javaClass.simpleName}"
        }
    }

    private fun unavailable() {
        val wasEnabled = enabled
        enabled = false
        service = null
        if (wasEnabled) onUnavailable?.invoke()
    }

    private fun disconnect() {
        service = null
        val previous = connection
        connection = null
        previous?.let { runCatching { Shizuku.unbindUserService(args, it, true) } }
    }

    override fun disable() { enabled = false; disconnect() }
    override fun close() { disable(); Shizuku.removeBinderDeadListener(dead); onUnavailable = null }

    private companion object { const val REQUEST_CODE = 4201 }
}
