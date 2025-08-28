package io.sensor_prod.sensor.ui.pages.home

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.rounded.AddChart
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.google.accompanist.pager.ExperimentalPagerApi
import io.sensify.sensor.R
import io.sensor_prod.sensor.ui.navigation.NavDirectionsApp
import io.sensor_prod.sensor.ui.pages.home.items.HomeSensorItem
import io.sensor_prod.sensor.ui.resource.values.JlResDimens
import io.sensor_prod.sensor.ui.resource.values.JlResShapes
import io.sensor_prod.sensor.ui.resource.values.JlResTxtStyles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
//import androidx.compose.material3.ModalDrawerSheet
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

@RequiresApi(Build.VERSION_CODES.O)
@OptIn(
    ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalTextApi::class,
    ExperimentalPagerApi::class, ExperimentalAnimationApi::class
)
@Composable
fun HomePage(
    modifier: Modifier = Modifier, navController: NavController? = null,
    viewModel: HomeViewModel = viewModel(
        factory = HomeViewModel.Factory
    )
) {

    val context = LocalContext.current
    viewModel.startGyroListening(context)

    val camVM: CameraViewModel = viewModel(
        factory = CameraViewModel.Factory()
    )
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraxSelector = CameraSelector.Builder().requireLensFacing(CameraSelector.LENS_FACING_BACK).build()

    LaunchedEffect(Unit) {
        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.fromOrderedList(
                listOf(Quality.HD, Quality.FHD) // prefer HD to save power
            ))
            .build()
        val videoCapture = VideoCapture.withOutput(recorder)

        // Initialize the ViewModel with application context inside VM
        camVM.initialize(context, videoCapture)
        val cameraProvider = context.getCameraProvider()
        cameraProvider.unbindAll()
        cameraProvider.bindToLifecycle(lifecycleOwner, cameraxSelector, videoCapture)
        camVM.startRecordingClips()
    }

    val lazyListState = rememberLazyListState()

    val potholeDetected = viewModel.potholeDetected.collectAsState()
    val threshold = viewModel.thresholdFlow.collectAsState()

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    // BLE dialog state
    val showBleDialog = remember { mutableStateOf(false) }
    val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    val pairedDevices = remember { mutableStateListOf<BluetoothDevice>() }

    LaunchedEffect(showBleDialog.value)  {
        if (showBleDialog.value) {
            pairedDevices.clear()
            // Guard with permission to avoid SecurityException on Android 12+
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                bluetoothAdapter?.bondedDevices?.let { pairedDevices.addAll(it) }
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Spacer(Modifier.height(12.dp))
                NavigationDrawerItem(
                    label = { Text("Home") },
                    selected = true,
                    onClick = { scope.launch { drawerState.close() } }
                )
                NavigationDrawerItem(
                    label = { Text("Upload to server") },
                    selected = false,
                    icon = { Icon(Icons.Default.CloudUpload, contentDescription = null) },
                    onClick = { /* TODO: navigate or trigger upload screen placeholder */ }
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    "Controls",
                    style = JlResTxtStyles.p2,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
                // You can add more toggles here later
            }
        }
    ) {
        val isAtTop = remember {
            derivedStateOf { lazyListState.firstVisibleItemIndex == 0 && lazyListState.firstVisibleItemScrollOffset == 0 }
        }

        // Trigger recording on detection
        LaunchedEffect(potholeDetected.value) {
            if (potholeDetected.value) {
                withContext(Dispatchers.Main) {
                    try { camVM.triggerEventRecording() } catch (e: Exception) { Log.e("Recording", "Error: ${e.message}", e) }
                }
                Toast.makeText(context, "Pothole detected! Recording started.", Toast.LENGTH_SHORT).show()
            }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    colors = if (!isAtTop.value) TopAppBarDefaults.mediumTopAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                    ) else TopAppBarDefaults.mediumTopAppBarColors(
                        containerColor = Color.Transparent
                    ),
                    navigationIcon = {
                        IconButton (onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "Menu")
                        }
                    },
                    title = {
                        Text(
                            text = "ILGC",
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center,
                            style = JlResTxtStyles.h4,
                            fontWeight = FontWeight(400),
                            modifier = modifier.fillMaxWidth(),
                        )
                    },
                    actions = {
                        Box(Modifier.padding(horizontal = JlResDimens.dp20)) {
                            Image(
                                painterResource(id = R.drawable.pic_sensify_logo),
                                modifier = Modifier
                                    .alpha(0f)
                                    .width(JlResDimens.dp32)
                                    .height(JlResDimens.dp36),
                                contentDescription = null,
                                contentScale = ContentScale.FillBounds
                            )
                        }
                    },
                )
            },
            floatingActionButton = {
                // Two FABs: CSV toggle and BLE picker
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ToggleableCsvFAB(viewModel)
                    FloatingActionButton(
                        onClick = { showBleDialog.value = true },
                        shape = RoundedCornerShape(50),
                        containerColor = Color(0xFF1565C0),
                        modifier = Modifier
                            .background(
                                brush = Brush.linearGradient(
                                    listOf(Color(0xFF1565C0), Color(0xFF42A5F5))
                                ),
                                shape = RoundedCornerShape(50.dp)
                            )
                            .border(
                                brush = Brush.verticalGradient(
                                    listOf(Color.Black.copy(alpha = 0.1f), Color.Black.copy(alpha = 0.3f))
                                ),
                                width = JlResDimens.dp1,
                                shape = RoundedCornerShape(50.dp)
                            )
                    ) {
                        Icon(Icons.Default.Bluetooth, contentDescription = "BLE Devices", tint = Color.White)
                    }
                }
            },
        ) { innerPadding ->

            // Content
            LazyColumn(
                modifier = Modifier
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f),
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.02f),
                            )
                        )
                    ),
                contentPadding = innerPadding,
                state = lazyListState
            ) {
                // Pothole banner at the top, below app bar
                if (potholeDetected.value) {
                    item {
                        PotholeBanner()
                        Spacer(Modifier.height(8.dp))
                    }
                }

                item { Spacer(modifier = JlResShapes.Space.H18) }

                // Threshold slider
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Pothole threshold (RMS)", style = JlResTxtStyles.p2)
                            Spacer(Modifier.height(8.dp))
                            Slider(
                                value = threshold.value,
                                onValueChange = { viewModel.setThreshold(it) },
                                valueRange = 0.5f..15f,
                                steps = 14
                            )
                            Text(
                                text = String.format(Locale.US, "Current: %.2f", threshold.value),
                                style = JlResTxtStyles.p3,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            )
                        }
                    }
                }

                item { Spacer(modifier = JlResShapes.Space.H18) }

                // Available Sensors title
                item {
                    Box(
                        modifier = Modifier
                            .padding(
                                start = JlResDimens.dp40, end = JlResDimens.dp32,
                                top = JlResDimens.dp12, bottom = JlResDimens.dp16
                            ),
                    ) {
                        Text(
                            text = "Available Sensors",
                            fontSize = JlResDimens.sp16,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                items(viewModel.mSensorsList.windowed(2, 2, true)) { item ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = JlResDimens.dp32)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            for (i in item.indices) {
                                Box(modifier = Modifier.weight(1f)) {
                                    HomeSensorItem(
                                        modelSensor = item[i],
                                        onCheckChange = { type: Int, isChecked: Boolean -> viewModel.onSensorChecked(type, isChecked) },
                                        onClick = { navController?.navigate("${NavDirectionsApp.SensorDetailPage.route}/${it}") }
                                    )
                                }
                                if (i < item.size - 1) { Spacer(modifier = Modifier.width(JlResDimens.dp8)) }
                            }
                            if (item.size % 2 != 0) {
                                Spacer(modifier = Modifier.width(JlResDimens.dp8))
                                Box(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(JlResDimens.dp8))
                }
                item { Spacer(modifier = Modifier.height(JlResDimens.dp16)) }
            }
        }

        // BLE picker dialog
        if (showBleDialog.value) {
            AlertDialog(
                onDismissRequest = { showBleDialog.value = false },
                confirmButton = {
                    TextButton(onClick = { showBleDialog.value = false }) { Text("Close") }
                },
                title = { Text("Bluetooth devices") },

                text = {
                    Column(Modifier.fillMaxWidth()) {
                        val connected = viewModel.bleConnectedDevice.collectAsState()
                        // Use LocalContext instead of incorrect cast
                        if (ActivityCompat.checkSelfPermission(
                                context,
                                Manifest.permission.BLUETOOTH_CONNECT
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            Text("Bluetooth permission required")
                        } else {
                            Text(
                                text = connected.value?.name?.let { "Connected: $it" } ?: "Not connected",
                                style = JlResTxtStyles.p3,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.height(8.dp))
                            LazyColumn(Modifier.heightIn(max = 280.dp)) {
                                items(pairedDevices.size) { idx ->
                                    val dev = pairedDevices[idx]
                                    ListItem(
                                        headlineContent =  { Text(dev.name ?: "Unknown") },
                                        supportingContent = { Text(dev.address) },
                                        modifier = Modifier.clickable {
                                            viewModel.connectBle(dev)
                                        }
                                    )
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val bleLogging = viewModel.bleLogging.collectAsState()
                                Text("Log BLE to CSV", modifier = Modifier.weight(1f))
                                Switch(checked = bleLogging.value, onCheckedChange = { viewModel.toggleBleLogging() })
                            }
                        }
                    }
                }
            )
        }
    }
}

@Composable
fun ToggleableCsvFAB(viewModel: HomeViewModel) {
    var isLogging by remember { mutableStateOf(false) }
    val onColor = Color(0xFF2E7D32) // green
    val offColor = Color(0xFF8B0000) // dark red

    FloatingActionButton(
        onClick = {
            isLogging = !isLogging
            viewModel.toggleCsvLogging()
        },
        shape = RoundedCornerShape(50),
        containerColor = if (isLogging) onColor else offColor,
        modifier = Modifier
            .padding(bottom = 4.dp)
            .background(
                brush = Brush.linearGradient(
                    colors = if (isLogging)
                        listOf(onColor.copy(alpha = 0.9f), onColor.copy(alpha = 0.6f))
                    else listOf(offColor.copy(alpha = 0.9f), offColor.copy(alpha = 0.6f))
                ),
                shape = RoundedCornerShape(50.dp)
            )
            .border(
                brush = Brush.verticalGradient(
                    listOf(Color.Black.copy(alpha = 0.1f), Color.Black.copy(alpha = 0.3f))
                ),
                width = JlResDimens.dp1,
                shape = RoundedCornerShape(50.dp)
            )
    ) {
        Icon(Icons.Rounded.AddChart, "Record CSV", tint = Color.White)
    }
}

@Composable
private fun PotholeBanner() {
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val bannerFontSize = if (screenWidthDp < 360) 12.sp else 14.sp
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .background(Color.Red.copy(alpha = 0.7f), shape = RoundedCornerShape(8.dp))
            .padding(8.dp)
    ) {
        Icon(
            Icons.Default.Warning,
            contentDescription = "Warning",
            tint = Color.White,
            modifier = Modifier.size(if (screenWidthDp < 360) 18.dp else 24.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "Pothole detected!",
            color = Color.White,
            style = JlResTxtStyles.p3.copy(fontSize = bannerFontSize)
        )
    }
}

private suspend fun Context.getCameraProvider(): ProcessCameraProvider =
    suspendCoroutine { continuation ->
        ProcessCameraProvider.getInstance(this).also { cameraProvider ->
            cameraProvider.addListener({
                continuation.resume(cameraProvider.get())
            }, ContextCompat.getMainExecutor(this))
        }
    }