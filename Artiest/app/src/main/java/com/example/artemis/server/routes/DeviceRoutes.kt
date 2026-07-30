package com.example.artemis.server.routes

import com.example.artemis.feature.DeviceInfoProvider
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

fun Route.deviceRoutes(deviceInfoProvider: DeviceInfoProvider, json: Json) {
    get("/api/v1/device/info") {
        val info = deviceInfoProvider.getFullDeviceInfo()
        call.respond(HttpStatusCode.OK, json.encodeToString(info))
    }

    get("/api/v1/device/info/battery") {
        val batteryInfo = deviceInfoProvider.getBatteryInfo()
        call.respond(HttpStatusCode.OK, json.encodeToString(batteryInfo))
    }

    get("/api/v1/device/info/network") {
        val networkInfo = deviceInfoProvider.getNetworkInfo()
        call.respond(HttpStatusCode.OK, json.encodeToString(networkInfo))
    }

    get("/api/v1/device/info/storage") {
        val storageInfo = deviceInfoProvider.getStorageInfo()
        call.respond(HttpStatusCode.OK, json.encodeToString(storageInfo))
    }
}
