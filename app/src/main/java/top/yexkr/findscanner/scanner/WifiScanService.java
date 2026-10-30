package top.yexkr.findscanner.scanner;

import androidx.annotation.Keep;
import android.os.SystemClock;
import java.io.ByteArrayOutputStream;

/** Instantiated by Shizuku as shell/root, never as an Android application service. */
@Keep
public class WifiScanService extends IWifiScanService.Stub {
    public WifiScanService() {}

    @Override
    public String startScan() {
        Process process = null;
        try {
            process = new ProcessBuilder("/system/bin/cmd", "wifi", "start-scan")
                    .redirectErrorStream(true).start();
            long deadline = SystemClock.elapsedRealtime() + 8000;
            while (true) {
                try { process.exitValue(); break; }
                catch (IllegalThreadStateException running) {
                    if (SystemClock.elapsedRealtime() >= deadline) return "shell_scan_timeout";
                    Thread.sleep(50);
                }
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int count;
            while ((count = process.getInputStream().read(buffer)) != -1) {
                if (output.size() < 4096) output.write(buffer, 0, Math.min(count, 4096 - output.size()));
            }
            String message = output.toString("UTF-8").trim();
            if (process.exitValue() != 0 || !message.isEmpty()) {
                return "shell_scan_failed:" + process.exitValue() + ":" + message;
            }
            // Command acceptance does not imply that fresh scan results are available yet.
            return "";
        } catch (Exception error) {
            return "shell_scan_failed:" + error.getClass().getSimpleName();
        } finally {
            if (process != null) process.destroy();
        }
    }

    @Override
    public void destroy() { System.exit(0); }
}
