package xyz.kinjalik.debloatpass

import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * A tiny HTTP/1.1 server on 127.0.0.1 for tests (shared by JVM unit tests and instrumented
 * tests, so no com.sun.net.httpserver / MockWebServer). One request per connection.
 */
class TinyHttpServer : Closeable {

    class Request(val method: String, val target: String, val headers: Map<String, String>) {
        val path: String get() = target.substringBefore('?')
        fun header(name: String): String? = headers[name.lowercase()]
    }

    class Response(
        val code: Int,
        val headers: List<Pair<String, String>> = emptyList(),
        val body: ByteArray = ByteArray(0),
        /** If set, written instead of [body] with no Content-Length (body ends at close). */
        val stream: ((OutputStream) -> Unit)? = null,
    )

    private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val routes = ConcurrentHashMap<String, (Request) -> Response>()
    val requests = CopyOnWriteArrayList<Request>()

    val port: Int get() = socket.localPort

    fun url(path: String) = "http://127.0.0.1:$port$path"

    fun route(path: String, handler: (Request) -> Response) {
        routes[path] = handler
    }

    init {
        thread(isDaemon = true, name = "TinyHttpServer") {
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (_: IOException) {
                    break
                }
                thread(isDaemon = true) { serve(client) }
            }
        }
    }

    private fun serve(client: Socket) = client.use { s ->
        try {
            val input = s.getInputStream()
            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(' ')
            if (parts.size < 2) return
            val headers = HashMap<String, String>()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val i = line.indexOf(':')
                if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
            }
            val req = Request(parts[0], parts[1], headers)
            requests += req
            val resp = routes[req.path]?.invoke(req) ?: Response(404, body = "not found".toByteArray())

            val out = BufferedOutputStream(s.getOutputStream())
            val head = StringBuilder("HTTP/1.1 ${resp.code} ${reason(resp.code)}\r\n")
            resp.headers.forEach { (k, v) -> head.append(k).append(": ").append(v).append("\r\n") }
            if (resp.stream == null) head.append("Content-Length: ").append(resp.body.size).append("\r\n")
            head.append("Connection: close\r\n\r\n")
            out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
            if (req.method != "HEAD") {
                val stream = resp.stream
                if (stream != null) stream(out) else out.write(resp.body)
            }
            out.flush()
        } catch (_: IOException) {
            // client went away (e.g. hit the size limit); nothing to do
        }
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().removeSuffix("\r")
            sb.append(c.toChar())
        }
    }

    private fun reason(code: Int) = when (code) {
        200 -> "OK"
        301 -> "Moved Permanently"
        302 -> "Found"
        307 -> "Temporary Redirect"
        308 -> "Permanent Redirect"
        404 -> "Not Found"
        else -> "Status"
    }

    override fun close() = socket.close()

    companion object {
        fun ok(body: ByteArray, type: String, vararg headers: Pair<String, String>) =
            Response(200, listOf("Content-Type" to type) + headers, body)

        fun redirect(code: Int, location: String) = Response(code, listOf("Location" to location))
    }
}
