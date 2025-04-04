package io.sensor_prod.sensor.ui.pages.home.model

import android.hardware.Sensor
import io.sensor_prod.sensor.domains.sensors.SensorsConstants

data class ModelHomeSensor(
    var type: Int = -1,
    var sensor: Sensor? = null,
    var info: Map<String, Any> = mutableMapOf(),
    var valueRms: Float? = 0.0f,
    var isActive:  Boolean =  false,
var name: String = ""
) {

    init {

        name = SensorsConstants.MAP_TYPE_TO_NAME.get( type,sensor?.name?:"")
    }
}