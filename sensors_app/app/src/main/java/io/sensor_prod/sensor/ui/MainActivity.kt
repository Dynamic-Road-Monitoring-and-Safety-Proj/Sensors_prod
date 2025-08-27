package io.sensor_prod.sensor.ui

import android.content.Context
import android.content.Intent
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import io.sensor_prod.sensor.domains.permissions.PermissionsRequest
import io.sensor_prod.sensor.domains.permissions.RememberPermissionManager
import io.sensor_prod.sensor.domains.permissions.forPurpose
import io.sensor_prod.sensor.domains.permissions.runAtStart
import io.sensor_prod.sensor.domains.sensors.packets.SensorPacketsProvider
import io.sensor_prod.sensor.domains.sensors.provider.SensorsProvider
import io.sensor_prod.sensor.ui.navigation.NavGraphApp
import io.sensor_prod.sensor.ui.resource.themes.SensifyM3Theme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val STORAGE_PERMISSION_CODE = 100

    @RequiresApi(Build.VERSION_CODES.S)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager

        lifecycleScope.launch {
            SensorsProvider.getInstance().setSensorManager(sensorManager)
            SensorPacketsProvider.getInstance().setSensorManager(sensorManager)
        }

        // Enable edge-to-edge; we'll pad content with safeDrawing insets in Compose
        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContent {
            SensifyM3Theme {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.safeDrawing),
                    color = MaterialTheme.colorScheme.background
                ) {
                    var hasPermissions by remember { mutableStateOf(false) }

                    val permissionRequest = PermissionsRequest
                        .forPurpose(PermissionsRequest.PURPOSE_DETAIL)
                        .runAtStart(true)

                    val permissionState = RememberPermissionManager(permissionRequest) { isGranted ->
                        hasPermissions = isGranted
                        Log.d("MainActivity", "Permissions granted: $isGranted")

                        // If regular permissions granted but still need storage access
                        if (isGranted && !Environment.isExternalStorageManager()) {
                            requestStoragePermission()
                        }
                    }
                    NavGraphApp()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        lifecycleScope.launch(Dispatchers.Default) {
            SensorsProvider.getInstance().clearAll()
            SensorPacketsProvider.getInstance().clearAll()
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun requestStoragePermission()  {
        try {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
            val uri = Uri.parse("package:$packageName")
            intent.data = uri
            startActivityForResult(intent, STORAGE_PERMISSION_CODE) // Use a constant for the request code
            Toast.makeText(this, "Please allow access to all files", Toast.LENGTH_LONG).show()

        } catch (e: Exception) {
            // Fallback for some devices
            val intent = Intent()
            intent.action = Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION
            startActivityForResult(intent, STORAGE_PERMISSION_CODE)
        }
    }
}
