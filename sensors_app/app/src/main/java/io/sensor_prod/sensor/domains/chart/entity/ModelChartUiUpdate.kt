package io.sensor_prod.sensor.domains.chart.entity

import io.sensor_prod.sensor.domains.sensors.packets.ModelSensorPacket

/**
 * Created by Niraj on 18-09-2022.
 */
data class ModelChartUiUpdate(
    var sensorType: Int,
    var size: Int,
    var packets: List<ModelSensorPacket> = listOf(),
    var timestamp: Long = System.currentTimeMillis()
) {
}