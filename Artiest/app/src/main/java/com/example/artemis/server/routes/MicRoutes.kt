package com.example.artemis.server.routes

import com.example.artemis.feature.MicController
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class RecordRequest(
    val durationMs: Long = 30_000L,
    val encoding: String = "AAC"
)

@Serializable
data class RecordResponse(
    val id: String,
    val durationMs: Long,
    val fileSize: Long,
    val mimeType: String,
    val createdAt: Long
)

fun Route.micRoutes(micController: MicController, json: Json) {

    // Start recording
    post("/api/v1/mic/record/start") {
        val request = try {
            call.receive<RecordRequest>()
        } catch (e: Exception) {
            RecordRequest()
        }

        val result = micController.startRecording(request.durationMs)
        if (result.isSuccess) {
            val recording = result.getOrThrow()
            call.respond(
                HttpStatusCode.OK,
                json.encodeToString(recording)
            )
        } else {
            call.respond(
                HttpStatusCode.InternalServerError,
                json.encodeToString(
                    mapOf("error" to "record_failed", "message" to result.exceptionOrNull()?.message)
                )
            )
        }
    }

    // Stop recording
    post("/api/v1/mic/record/stop") {
        val result = micController.stopRecording()
        if (result.isSuccess) {
            val recording = result.getOrThrow()
            call.respond(
                HttpStatusCode.OK,
                json.encodeToString(recording)
            )
        } else {
            call.respond(
                HttpStatusCode.Conflict,
                json.encodeToString(
                    mapOf("error" to "not_recording", "message" to "No active recording to stop")
                )
            )
        }
    }

    // List recordings
    get("/api/v1/mic/recordings") {
        val recordings = micController.getRecordings()
        call.respond(
            HttpStatusCode.OK,
            json.encodeToString(mapOf("recordings" to recordings))
        )
    }

    // Get a specific recording
    get("/api/v1/mic/recordings/{id}") {
        val id = call.parameters["id"] ?: return@get
        val recording = micController.getRecording(id)
        if (recording != null) {
            call.respond(HttpStatusCode.OK, json.encodeToString(recording))
        } else {
            call.respond(
                HttpStatusCode.NotFound,
                json.encodeToString(mapOf("error" to "not_found", "message" to "Recording not found"))
            )
        }
    }
}
