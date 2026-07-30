package com.example.artemis.server.routes

import com.example.artemis.feature.LocationTracker
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class LocationQuery(
    val from: Long? = null,
    val to: Long? = null,
    val limit: Int = 1000,
    val offset: Int = 0
)

fun Route.locationRoutes(locationTracker: LocationTracker, json: Json) {

    // Get current location
    get("/api/v1/location/current") {
        val location = locationTracker.getCurrentLocation()
        if (location != null) {
            call.respond(
                HttpStatusCode.OK,
                json.encodeToString(location)
            )
        } else {
            call.respond(
                HttpStatusCode.OK,
                json.encodeToString(mapOf("error" to "no_location", "message" to "No location data available yet"))
            )
        }
    }

    // Get location history with optional date range
    get("/api/v1/location/history") {
        val from = call.request.queryParameters["from"]?.toLongOrNull()
        val to = call.request.queryParameters["to"]?.toLongOrNull()
        val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 1000
        val offset = call.request.queryParameters["offset"]?.toIntOrNull() ?: 0

        val history = locationTracker.getLocationHistory(from, to, limit, offset)

        call.respond(
            HttpStatusCode.OK,
            json.encodeToString(
                mapOf(
                    "points" to history,
                    "count" to history.size,
                    "limit" to limit,
                    "offset" to offset
                )
            )
        )
    }
}
