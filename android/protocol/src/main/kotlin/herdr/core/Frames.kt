package herdr.core

import java.io.EOFException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Frame kinds of herdr-core's framed mode (herdr-core schema/README.md, Framed mode). */
object Kind {
    const val DATA = 0
    const val CREDIT = 1
    const val CLOSE = 2
    const val RESIZE = 3
    const val FOCUS = 4
    const val FACTS = 5
    const val AUTOMATIC_INPUT = 6
}

const val HEADER = 9
const val MAX_PAYLOAD = 16 * 1024 * 1024

class Frame(val channel: Int, val kind: Int, val payload: ByteArray)

/** `[channel u32 LE][kind u8][len u32 LE][payload]`. */
fun encodeFrame(channel: Int, kind: Int, payload: ByteArray): ByteArray {
    val out = ByteBuffer.allocate(HEADER + payload.size).order(ByteOrder.LITTLE_ENDIAN)
    out.putInt(channel)
    out.put(kind.toByte())
    out.putInt(payload.size)
    out.put(payload)
    return out.array()
}

fun u32(n: Int): ByteArray = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(n).array()

fun resizePayload(cols: Int, rows: Int): ByteArray =
    ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putShort(cols.toShort()).putShort(rows.toShort()).array()

private fun InputStream.readFully(buf: ByteArray) {
    var at = 0
    while (at < buf.size) {
        val n = read(buf, at, buf.size - at)
        if (n < 0) throw EOFException()
        at += n
    }
}

/** Reads one frame; null at the end of the stream. */
fun readFrame(input: InputStream): Frame? {
    val header = ByteArray(HEADER)
    try {
        input.readFully(header)
    } catch (_: EOFException) {
        return null
    }
    val h = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
    val channel = h.getInt(0)
    val kind = header[4].toInt() and 0xff
    val len = h.getInt(5)
    if (len < 0 || len > MAX_PAYLOAD) throw IllegalStateException("frame too large: $len")
    val payload = ByteArray(len)
    try {
        input.readFully(payload)
    } catch (_: EOFException) {
        return null
    }
    return Frame(channel, kind, payload)
}

/** One line (without `\n`) read byte by byte, so nothing after it is consumed. */
fun readLine(input: InputStream, max: Int = 1 shl 20): String? {
    val out = java.io.ByteArrayOutputStream()
    while (true) {
        val b = input.read()
        if (b < 0) return if (out.size() == 0) null else out.toString(Charsets.UTF_8)
        if (b == '\n'.code) return out.toString(Charsets.UTF_8)
        if (out.size() >= max) throw IllegalStateException("line too long")
        out.write(b)
    }
}
