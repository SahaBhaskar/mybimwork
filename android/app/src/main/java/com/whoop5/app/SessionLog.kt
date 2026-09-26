package com.whoop5.app

import android.content.Context
import org.json.JSONObject
import whoop5.hex
import java.io.File
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Raw BLE traffic as JSON lines, the same format `python -m whoop5 sniff` writes,
 * so `python -m whoop5 decode <file>` works on logs shared from the phone.
 */
class SessionLog private constructor(val file: File) {
    private val out: Writer = file.bufferedWriter()

    @Synchronized
    fun write(direction: String, char: UUID, data: ByteArray) {
        val line = JSONObject()
            .put("t", System.currentTimeMillis() / 1000.0)
            .put("dir", direction)
            .put("char", char.toString())
            .put("hex", data.hex())
        out.write(line.toString())
        out.write("\n")
        out.flush()
    }

    @Synchronized
    fun close() = runCatching { out.close() }

    companion object {
        fun dir(context: Context) = File(context.getExternalFilesDir(null) ?: context.filesDir, "sessions")

        fun open(context: Context): SessionLog? = runCatching {
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            SessionLog(File(dir(context).apply { mkdirs() }, "whoop_$stamp.jsonl"))
        }.getOrNull()
    }
}
