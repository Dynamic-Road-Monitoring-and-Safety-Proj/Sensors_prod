package io.sensor_prod.sensor.domains.sensors.packets

import android.hardware.SensorManager

data class SensorPacketConfig(
    var sensorType: Int,
    var sensorDelay: Int = SensorManager.SENSOR_DELAY_UI
) {
}