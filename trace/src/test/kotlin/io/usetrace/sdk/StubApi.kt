package io.usetrace.sdk

import org.json.JSONObject
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/** One request as it arrived, which is the only side of a send that proves anything. */
internal class Recorded(
    val path: String,
    private val headers: Map<String, String>,
    val body: String,
) {
    fun header(name: String): String? = headers[name.lowercase()]

    fun json(): JSONObject = JSONObject(body)
}

/**
 * The Trace API, reduced to the status codes it answers with: an event with 202 and a consent call with 201,
 * because those are the real ones. It records what arrived, in the order it arrived.
 *
 * A plain [ServerSocket] rather than `com.sun.net.httpserver`, which the plan assumed: an Android unit test
 * compiles against `android.jar`, so the JDK's own HTTP server is not on the classpath at all. Serving one
 * connection at a time is enough, because the SDK sends from one thread and sends synchronously.
 *
 * Shared by the transport tests and the end to end test rather than written twice. A stub that disagreed with
 * itself about what the API answers would let one suite pass on behaviour the other proved wrong.
 */
internal class StubApi(
    private val eventStatus: Int = 202,
    private val consentStatus: Int = 201,
    private val delayMillis: Long = 0,
) {
    val requests = CopyOnWriteArrayList<Recorded>()

    /** The status from the second request onwards, for the case where a server error clears on a retry. */
    @Volatile
    var statusAfterFirst: Int? = null

    private val listening = AtomicBoolean(true)
    private val socket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())

    val url: String get() = "http://127.0.0.1:${socket.localPort}"

    init {
        Thread {
            while (listening.get()) {
                runCatching { socket.accept() }.getOrNull()?.let { runCatching { serve(it) } }
            }
        }.apply { isDaemon = true }.start()
    }

    fun stop() {
        listening.set(false)
        runCatching { socket.close() }
    }

    private fun serve(connection: Socket) = connection.use {
        val input = connection.getInputStream()

        // The request line and the headers, to the blank line. One byte at a time: a test does not need a
        // buffered reader's lookahead, and reading past the headers would eat the body.
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) {
            val byte = input.read()
            if (byte == -1) return@use
            head.append(byte.toChar())
        }
        val lines = head.toString().trimEnd().split("\r\n")
        val path = lines.first().split(" ").getOrElse(1) { "/" }
        val headers = lines.drop(1).filter { it.contains(':') }
            .associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }

        // Exactly as many bytes as were declared, or HttpURLConnection sees a broken pipe rather than a reply.
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) break
            read += n
        }

        val first = requests.isEmpty()
        requests.add(Recorded(path, headers, body.decodeToString()))

        // A server that answers far too late, which is what a transport timeout is for.
        if (delayMillis > 0) Thread.sleep(delayMillis)

        val status = (if (first) null else statusAfterFirst)
            ?: if (path.endsWith("/consent")) consentStatus else eventStatus
        connection.getOutputStream().apply {
            write(
                (
                    "HTTP/1.1 $status ${reason(status)}\r\n" +
                        "Content-Type: application/json\r\n" +
                        "Content-Length: 2\r\n" +
                        "Connection: close\r\n\r\n{}"
                    ).toByteArray()
            )
            flush()
        }
    }

    private fun reason(status: Int): String = when (status) {
        200 -> "OK"
        201 -> "Created"
        202 -> "Accepted"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        else -> "Internal Server Error"
    }
}
