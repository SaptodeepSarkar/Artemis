package com.example.artemis.server.routes

import com.example.artemis.feature.CameraController
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class CaptureRequest(
    val cameraId: String = "0",
    val quality: Int = 80,
    val resolution: String? = null
)

@Serializable
data class CaptureResponse(
    val id: String,
    val cameraId: String,
    val filePath: String,
    val fileSize: Long,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val createdAt: Long
)

fun Route.cameraRoutes(cameraController: CameraController, json: Json) {

    // List available cameras
    get("/api/v1/camera/list") {
        val cameras = cameraController.getCameraList()
        call.respond(
            HttpStatusCode.OK,
            json.encodeToString(mapOf("cameras" to cameras))
        )
    }

    // Capture a photo
    post("/api/v1/camera/capture") {
        val request = try {
            call.receive<CaptureRequest>()
        } catch (e: Exception) {
            CaptureRequest()
        }

        val result = cameraController.capturePhoto(request.cameraId)
        if (result.isSuccess) {
            val capture = result.getOrThrow()
            call.respond(
                HttpStatusCode.OK,
                json.encodeToString(capture)
            )
        } else {
            call.respond(
                HttpStatusCode.InternalServerError,
                json.encodeToString(
                    mapOf("error" to "capture_failed", "message" to result.exceptionOrNull()?.message)
                )
            )
        }
    }

    // List previous captures
    get("/api/v1/camera/captures") {
        val captures = cameraController.getCaptures()
        call.respond(
            HttpStatusCode.OK,
            json.encodeToString(mapOf("captures" to captures))
        )
    }

    // Get a specific capture
    get("/api/v1/camera/captures/{id}") {
        val id = call.parameters["id"] ?: return@get
        val capture = cameraController.getCapture(id)
        if (capture != null) {
            call.respond(HttpStatusCode.OK, json.encodeToString(capture))
        } else {
            call.respond(
                HttpStatusCode.NotFound,
                json.encodeToString(mapOf("error" to "not_found", "message" to "Capture not found"))
            )
        }
    }
}
