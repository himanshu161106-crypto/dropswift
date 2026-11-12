package app.dropswift

import android.content.Context
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Lets an iPhone or laptop take the files with just a browser.
 * They join the sender's Wi-Fi Direct network (same QR), then open http://<ip>:PORT/<secret>/
 * Only someone who knows the secret (= the network password) can see or download anything.
 */
class WebShare(
    private val ctx: Context,
    private val items: List<Transfer.Item>,
    private val secret: String
) : Closeable {

    val bytes = AtomicLong(0)
    val active = AtomicInteger(0)
    val finished = AtomicInteger(0)
    @Volatile var lastClient: String = ""

    private var server: ServerSocket? = null

    fun start(track: (Closeable) -> Unit) {
        val s = ServerSocket()
        s.reuseAddress = true
        s.bind(InetSocketAddress(PORT))
        server = s
        thread(name = "ds-web") {
            while (true) {
                val c = try {
                    s.accept()
                } catch (e: IOException) {
                    return@thread
                }
                track(c)
                thread(name = "ds-web-client") { handle(c) }
            }
        }
    }

    override fun close() {
        try {
            server?.close()
        } catch (_: Exception) {
        }
    }

    private fun readLine(inp: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = inp.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            if (sb.length > 8000) throw IOException("header too long")
            sb.append(b.toChar())
        }
    }

    private fun handle(c: Socket) {
        try {
            c.soTimeout = 30_000
            c.sendBufferSize = Transfer.SOCKET_BUFFER
            val inp = BufferedInputStream(c.getInputStream())
            val out = BufferedOutputStream(c.getOutputStream(), 256 * 1024)
            val request = readLine(inp) ?: return
            var range: String? = null
            var agent = ""
            while (true) {
                val h = readLine(inp) ?: break
                if (h.isEmpty()) break
                val lower = h.lowercase(Locale.US)
                if (lower.startsWith("range:")) range = h.substring(6).trim()
                if (lower.startsWith("user-agent:")) agent = h.substring(11).trim()
            }
            val parts = request.split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            val path = parts[1].substringBefore('?')
            val head = method == "HEAD"
            if (method != "GET" && !head) {
                simple(out, 405, "Method not allowed")
                return
            }
            val prefix = "/$secret/"
            if (path == "/$secret") {
                out.write("HTTP/1.1 302 Found\r\nLocation: $prefix\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                out.flush()
                return
            }
            if (!path.startsWith(prefix)) {
                simple(out, 404, "Not found")
                return
            }
            val rest = path.substring(prefix.length)
            if (rest.isEmpty()) {
                page(out, head)
                return
            }
            if (rest.startsWith("f/")) {
                val index = rest.substring(2).substringBefore('/').toIntOrNull()
                if (index == null || index < 0 || index >= items.size) {
                    simple(out, 404, "Not found")
                    return
                }
                lastClient = deviceFrom(agent)
                sendFile(out, items[index], range, head)
                return
            }
            simple(out, 404, "Not found")
        } catch (_: Exception) {
        } finally {
            try {
                c.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun deviceFrom(agent: String): String = when {
        agent.contains("iPhone") -> "iPhone"
        agent.contains("iPad") -> "iPad"
        agent.contains("Windows") -> "Windows laptop"
        agent.contains("Macintosh") -> "Mac"
        agent.contains("Android") -> "Android browser"
        else -> "Browser"
    }

    private fun simple(out: OutputStream, code: Int, text: String) {
        val body = text.toByteArray()
        out.write(
            ("HTTP/1.1 $code $text\r\nContent-Type: text/plain; charset=utf-8\r\n" +
                "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray()
        )
        out.write(body)
        out.flush()
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun page(out: OutputStream, head: Boolean) {
        val sb = StringBuilder()
        sb.append("<!doctype html><html><head><meta charset='utf-8'>")
        sb.append("<meta name='viewport' content='width=device-width,initial-scale=1'><title>DropSwift</title><style>")
        sb.append("body{margin:0;padding:20px;background:#0b0f19;color:#eef4fb;font:16px system-ui,-apple-system,sans-serif}")
        sb.append("h1{color:#00e68a;font-size:26px;margin:0 0 4px}p{color:#9aa9bd;font-size:14px}")
        sb.append("a.f{display:block;background:#151c2e;border:1px solid #293650;border-radius:12px;padding:14px;margin:8px 0;color:#eef4fb;text-decoration:none}")
        sb.append("a.f b{display:block;overflow-wrap:anywhere}a.f span{color:#9aa9bd;font-size:13px}")
        sb.append("</style></head><body><h1>DropSwift</h1>")
        sb.append("<p>File pe tap karo, download shuru ho jayega. iPhone: download ke baad Files app &rarr; Downloads.</p>")
        for ((i, item) in items.withIndex()) {
            val link = "f/$i/" + URLEncoder.encode(item.name, "UTF-8").replace("+", "%20")
            sb.append("<a class='f' href='").append(link).append("' download><b>")
                .append(esc(item.name)).append("</b><span>").append(size(item.size)).append("</span></a>")
        }
        sb.append("<p>Sender phone pe DropSwift khula rakho jab tak download chal raha hai.</p></body></html>")
        val body = sb.toString().toByteArray(Charsets.UTF_8)
        out.write(
            ("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nCache-Control: no-store\r\n" +
                "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray()
        )
        if (!head) out.write(body)
        out.flush()
    }

    private fun sendFile(out: OutputStream, item: Transfer.Item, rangeHeader: String?, head: Boolean) {
        val pfd = ctx.contentResolver.openFileDescriptor(item.uri, "r") ?: throw IOException("cannot open")
        pfd.use {
            val total = if (pfd.statSize > 0) pfd.statSize else item.size
            var start = 0L
            var end = total - 1
            var partial = false
            if (rangeHeader != null && rangeHeader.startsWith("bytes=") && total > 0) {
                val spec = rangeHeader.substring(6).substringBefore(',').trim()
                val a = spec.substringBefore('-').trim()
                val b = spec.substringAfter('-', "").trim()
                if (a.isNotEmpty()) {
                    start = a.toLongOrNull() ?: 0
                    if (b.isNotEmpty()) end = minOf(b.toLongOrNull() ?: end, total - 1)
                } else if (b.isNotEmpty()) {
                    start = maxOf(0, total - (b.toLongOrNull() ?: 0))
                }
                if (start >= total || start > end) {
                    out.write(
                        "HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$total\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()
                    )
                    out.flush()
                    return
                }
                partial = true
            }
            val length = end - start + 1
            val type = ctx.contentResolver.getType(item.uri) ?: "application/octet-stream"
            val ascii = item.name.replace(Regex("[^A-Za-z0-9._ -]"), "_")
            val utf = URLEncoder.encode(item.name, "UTF-8").replace("+", "%20")
            val headers = StringBuilder()
            headers.append(if (partial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
            headers.append("Content-Type: $type\r\n")
            headers.append("Content-Length: $length\r\n")
            headers.append("Accept-Ranges: bytes\r\n")
            if (partial) headers.append("Content-Range: bytes $start-$end/$total\r\n")
            headers.append("Content-Disposition: attachment; filename=\"$ascii\"; filename*=UTF-8''$utf\r\n")
            headers.append("Connection: close\r\n\r\n")
            out.write(headers.toString().toByteArray())
            if (head) {
                out.flush()
                return
            }
            active.incrementAndGet()
            try {
                FileInputStream(pfd.fileDescriptor).use { fis ->
                    val ch = fis.channel
                    ch.position(start)
                    val buf = ByteArray(1 shl 20)
                    var left = length
                    while (left > 0) {
                        val n = fis.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                        if (n < 0) break
                        out.write(buf, 0, n)
                        bytes.addAndGet(n.toLong())
                        left -= n
                    }
                    out.flush()
                    if (left == 0L && start + length == total) finished.incrementAndGet()
                }
            } finally {
                active.decrementAndGet()
            }
        }
    }

    private fun size(b: Long): String {
        if (b < 1024) return "$b B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var v = b / 1024.0
        var i = 0
        while (v >= 1024 && i < units.size - 1) {
            v /= 1024
            i++
        }
        return String.format(Locale.US, "%.2f %s", v, units[i])
    }

    companion object {
        const val PORT = 47880

        /** Address of this phone on its own Wi-Fi Direct network (usually 192.168.49.1). */
        fun groupAddress(): String {
            try {
                for (ni in NetworkInterface.getNetworkInterfaces()) {
                    if (!ni.name.startsWith("p2p")) continue
                    for (a in ni.inetAddresses) {
                        if (a is Inet4Address && !a.isLoopbackAddress) return a.hostAddress ?: continue
                    }
                }
            } catch (_: Exception) {
            }
            return "192.168.49.1"
        }
    }
}
