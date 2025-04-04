package io.sensify.sensor.domains.permissions

import android.Manifest
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState

class PermissionsManager

@RequiresApi(Build.VERSION_CODES.S)
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun RememberPermissionManager(
    query: PermissionsRequest,
    callbackResult: (isGranted: Boolean) -> Unit = {}
): MutablePermissionState {

    val permissionsList = query.getPermissionsList()
    Log.d("RememberPermissionManager", "Permissions List: $permissionsList")

    val multiplePermissionsState = rememberMultiplePermissionsState(
        permissions = permissionsList,
        onPermissionsResult = { result ->
            callbackResult(result.all { it.value })
        }
    )

    val permissionsState = remember(query, multiplePermissionsState) {
        MutablePermissionState(query, multiplePermissionsState)
    }

    val lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> Log.d("RememberPermissionManager", "Lifecycle ON_START")
                Lifecycle.Event.ON_STOP -> Log.d("RememberPermissionManager", "Lifecycle ON_STOP")
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (query.shouldRunAtStart() && !permissionsState.isGranted) {
        LaunchedEffect(Unit) {
            permissionsState.requestManually()
        }
    }

    return permissionsState
}

interface PermissionsRequest {
    fun getPurposeList(): List<Int>
    fun getPermissionsList(): List<String>
    fun shouldRunAtStart(): Boolean
    fun _runCondition(): Int?
    fun then(latest: PermissionsRequest): PermissionsRequest

    companion object : PermissionsRequest {
        const val PURPOSE_DETAIL = 101
        const val PURPOSE_SENSOR_STEP_COUNTER = 201
        internal const val RUN_AT_START = 1
        internal const val RUN_MANUALLY = 2

        @RequiresApi(Build.VERSION_CODES.S)
        private val PERMISSIONS_MAP = mapOf(
            PURPOSE_DETAIL to listOf(
                Manifest.permission.CAMERA,Manifest.permission.MANAGE_MEDIA,
//                Manifest.permission.HIGH_SAMPLING_RATE_SENSORS,
//                Manifest.permission.MANAGE_MEDIA,
//                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ),
            PURPOSE_SENSOR_STEP_COUNTER to listOf(
                Manifest.permission.ACTIVITY_RECOGNITION
            )
        )

        @RequiresApi(Build.VERSION_CODES.S)
        internal fun permissionsFor(purpose: Int): List<String> = PERMISSIONS_MAP[purpose] ?: emptyList()

        override fun getPurposeList(): List<Int> = emptyList()
        override fun getPermissionsList(): List<String> = emptyList()
        override fun shouldRunAtStart(): Boolean = true
        override fun _runCondition(): Int? = null
        override fun then(latest: PermissionsRequest): PermissionsRequest = PermissionsRequestDelegate(null).then(latest)
    }
}

class PermissionsRequestDelegate(private var purpose: Int?, private var runAtStart: Boolean? = null) : PermissionsRequest {
    private val mPurposeList = mutableListOf<Int>().apply { purpose?.let { add(it) } }
    private val mPermissions = mutableListOf<String>()
    private var mRunCondition: Int? = if (runAtStart == true) PermissionsRequest.RUN_AT_START else PermissionsRequest.RUN_MANUALLY

    override fun getPurposeList(): List<Int> = mPurposeList

    override fun then(latest: PermissionsRequest): PermissionsRequest {
        updateValues(latest)
        return this
    }

    @RequiresApi(Build.VERSION_CODES.S)
    override fun getPermissionsList(): List<String> {
        mPermissions.clear()
        mPurposeList.forEach { mPermissions.addAll(PermissionsRequest.permissionsFor(it)) }
        return mPermissions.distinct()
    }

    override fun shouldRunAtStart(): Boolean = mRunCondition == PermissionsRequest.RUN_AT_START
    override fun _runCondition(): Int? = mRunCondition

    private fun updateValues(latest: PermissionsRequest) {
        latest._runCondition()?.let { mRunCondition = it }
        latest.getPurposeList().forEach { if (!mPurposeList.contains(it)) mPurposeList.add(it) }
    }
}

@Stable
fun PermissionsRequest.forPurpose(purpose: Int) = then(PermissionsRequestDelegate(purpose))

@Stable
fun PermissionsRequest.runAtStart(shouldRun: Boolean) = then(PermissionsRequestDelegate(null, shouldRun))
