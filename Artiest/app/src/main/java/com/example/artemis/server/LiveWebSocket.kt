package com.example.artemis.server

import java.io.InputStream
import java.io.OutputStream

/**
 * RFC 6455 WebSocket framing — server side of the LIVE VIEW pipeline.
 *
 * The phone is the WebSocket SERVER (the dashboard bridges the browser to
 * it). Server→client frames are unmasked; client→server frames are masked
 * (required by the RFC) and are read by [readFrame]. Only the small subset
 * needed for the live view is implemented: text (1), binary (2), close (8),
 * ping (9), pong (10); no fragmentation (frames are tiny control messages
 * or self-contained JPEG/PCM chunks).
 */
object LiveWsProtocol {

    private const val MAGIC = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

    const val OP_TEXT = 0x1
    const val OP_BINARY = 0x2
    const val OP_CLOSE = 0x8
    const val OP_PING = 0x9
    const val OP_PONG = 0xA

    /** Sec-WebSocket-Accept value for a given Sec-WebSocket-Key. */
    fun acceptKey(secWebSocketKey: String): String {
        val sha1 = java.security.MessageDigest.getInstance("SHA-1")
            .digest((secWebSocketKey + MAGIC).toByteArray(Charsets.UTF_8))
        return android.util.Base64.encodeToString(sha1, android.util.Base64.NO_WRAP)
    }

    /** Encode one server→client frame (unmasked, FIN set). */
    fun encodeFrame(opcode: Int, payload: ByteArray): ByteArray {
        val headerSize = when {
            payload.size < 126 -> 2
            payload.size <= 0xFFFF -> 4
            else -> 10 // 64-bit extended length — full-res screen JPEGs
        }                                // routinely exceed 64 KiB
        val out = ByteArray(headerSize + payload.size)
        out[0] = (0x80 or opcode).toByte()
        when {
            payload.size < 126 -> {
                out[1] = payload.size.toByte()
            }
            payload.size <= 0xFFFF -> {
                out[1] = 126.toByte()
                out[2] = ((payload.size shr 8) and 0xFF).toByte()
                out[3] = (payload.size and 0xFF).toByte()
            }
            else -> {
                out[1] = 127.toByte()
                var len = payload.size.toLong()
                for (i in 9 downTo 2) {
                    out[i] = (len and 0xFF).toByte()
                    len = len shr 8
                }
            }
        }
        System.arraycopy(payload, 0, out, headerSize, payload.size)
        return out
    }

    /**
     * Read one client→server frame. Returns (opcode, payload) or null on
     * EOF/close/oversized. Masks are applied. Payloads are capped at 1 MiB
     * — control messages are tiny and a pathological peer gets dropped.
     */
    fun readFrame(input: InputStream): Pair<Int, ByteArray>? {
        val b0 = input.read(); if (b0 == -1) return null
        val b1 = input.read(); if (b1 == -1) return null
        val opcode = b0 and 0x0F
        val masked = (b1 and 0x80) != 0
        var len = (b1 and 0x7F).toLong()
        if (len == 126L) {
            val hi = input.read(); val lo = input.read()
            if (hi == -1 || lo == -1) return null
            len = ((hi shl 8) or lo).toLong()
        } else if (len == 127L) {
            val buf = ByteArray(8)
            if (input.read(buf) != 8) return null
            len = 0
            for (i in 0..7) len = (len shl 8) or (buf[i].toLong() and 0xFF)
        }
        if (len < 0 || len > (1 shl 20)) return null
        val maskKey = if (masked) {
            val m = ByteArray(4)
            if (input.read(m) != 4) return null
            m
        } else null
        val payload = ByteArray(len.toInt())
        var off = 0
        while (off < payload.size) {
            val n = input.read(payload, off, payload.size - off)
            if (n == -1) return null
            off += n
        }
        if (maskKey != null) {
            for (i in payload.indices) {
                payload[i] = (payload[i].toInt() xor maskKey[i % 4].toInt()).toByte()
            }
        }
        return opcode to payload
    }

    /** Write one server→client frame, flushing. Returns false on I/O error. */
    fun writeFrame(output: OutputStream, opcode: Int, payload: ByteArray): Boolean {
        return try {
            output.write(encodeFrame(opcode, payload))
            output.flush()
            true
        } catch (_: Exception) {
            false
        }
    }
}
