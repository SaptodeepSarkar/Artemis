package com.example.artemis.server.routes

import com.example.artemis.server.ArtemisServer
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.healthRoutes(server: ArtemisServer) {
    get("/api/v1/health") {
        call.respond(
            HttpStatusCode.OK,
            mapOf(
                "status" to "ok",
                "version" to "1.0.0",
                "deviceName" to android.os.Build.MODEL,
                "uptimeSeconds" to ((System.currentTimeMillis() - server.startTime) / 1000),
                "activeConnections" to server.activeConnections,
                "timestamp" to System.currentTimeMillis()
            )
        )
    }

    get("/health") {
        call.respond(
            HttpStatusCode.OK,
            mapOf(
                "status" to "ok",
                "version" to "1.0.0",
                "deviceName" to android.os.Build.MODEL,
                "uptimeSeconds" to ((System.currentTimeMillis() - server.startTime) / 1000),
                "activeConnections" to server.activeConnections,
                "timestamp" to System.currentTimeMillis()
            )
        )
    }
}
