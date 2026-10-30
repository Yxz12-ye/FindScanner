package top.yexkr.findscanner

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import top.yexkr.findscanner.ui.ScannerApp
import top.yexkr.findscanner.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
    private val scanner: ScannerViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme { ScannerApp(scanner) }
        }
    }

    override fun onStart() { super.onStart(); scanner.setForeground(true) }
    override fun onStop() {
        if (!isChangingConfigurations) scanner.setForeground(false)
        super.onStop()
    }
}
