package io.sensify.sensor.ui

import android.content.Context
import android.content.Intent
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat.startActivityForResult
import androidx.lifecycle.lifecycleScope
import io.sensify.sensor.domains.permissions.PermissionsRequest
import io.sensify.sensor.domains.permissions.RememberPermissionManager
import io.sensify.sensor.domains.permissions.forPurpose
import io.sensify.sensor.domains.permissions.runAtStart
import io.sensify.sensor.domains.sensors.packets.SensorPacketsProvider
import io.sensify.sensor.domains.sensors.provider.SensorsProvider
import io.sensify.sensor.ui.navigation.NavGraphApp
import io.sensify.sensor.ui.resource.themes.SensifyM3Theme
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

        setContent {
            SensifyM3Theme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    var hasPermissions by remember { mutableStateOf(false) }

                    val permissionRequest = PermissionsRequest
                        .forPurpose(PermissionsRequest.PURPOSE_DETAIL)
                        .runAtStart(true)

                    RememberPermissionManager(permissionRequest) { isGranted ->
                        hasPermissions = isGranted
                        Log.d("MainActivity", "Permissions granted: $isGranted")
                    }
//                    requestStoragePermission()
                    NavGraphApp()

//                    if (hasPermissions) {
//                        NavGraphApp()
//                    } else {
//                        RequestPermissionScreen()
//                    }
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
    private fun requestStoragePermission() {
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
