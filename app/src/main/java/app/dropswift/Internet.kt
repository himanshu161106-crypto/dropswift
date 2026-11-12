package app.dropswift

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Base64
import org.json.JSONObject
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Internet mode. The file bytes travel directly phone to phone over an encrypted WebRTC data channel.
 * Two small public helpers are used only to set the link up:
 *   - a STUN server tells each phone its public address,
 *   - a public message board (ntfy.sh) carries the two short "how to reach me" notes, found by the share code.
 * No file data ever goes through those helpers.
 */
class InternetLink(private val ctx: Context, private val log: (String) -> Unit) : Closeable {

    private val lock = Any()
    private var factory: PeerConnectionFactory? = null
    private var pc: PeerConnection? = null
    private var dc: DataChannel? = null
    private var disposed = false
    @Volatile private var closed = false
    @Volatile private var failure: String? = null
    @Volatile private var board: Board? = null

    private val gathered = CountDownLatch(1)
    private val channelOpen = CountDownLatch(1)
    private val ackArrived = CountDownLatch(1)
    private val incoming = LinkedBlockingQueue<ByteArray>()

    // ------------------------------------------------------------ public API

    fun send(code: String, items: List<Transfer.Item>, p: Progress, onStart: () -> Unit) {
        try {
            val b = Board(code)
            board = b
            createPeer()
            val channel = pc!!.createDataChannel("dropswift", DataChannel.Init())
            attach(channel)
            val offer = createDescription(true)
            setDescription(true, offer)
            gathered.await(10, TimeUnit.SECONDS)
            b.post("O:" + pack(pc!!.localDescription.description))
            log("internet: my address note sent, waiting for the other phone")
            val answer = b.waitFor("A:", 15 * 60_000L)
            setDescription(false, SessionDescription(SessionDescription.Type.ANSWER, unpack(answer)))
            waitForChannel()
            onStart()
            sendFiles(items, p)
        } finally {
            release()
        }
    }

    fun receive(code: String, p: Progress, onStart: () -> Unit) {
        try {
            val b = Board(code)
            board = b
            val offer = b.waitFor("O:", 30 * 60_000L)
            log("internet: sender found, linking")
            createPeer()
            setDescription(false, SessionDescription(SessionDescription.Type.OFFER, unpack(offer)))
            val answer = createDescription(false)
            setDescription(true, answer)
            gathered.await(10, TimeUnit.SECONDS)
            b.post("A:" + pack(pc!!.localDescription.description))
            waitForChannel()
            onStart()
            receiveFiles(p)
        } finally {
            release()
        }
    }

    /** Called from another thread to cancel. Only signals; the worker thread frees everything itself. */
    override fun close() {
        closed = true
        board?.cancel()
        synchronized(lock) {
            if (!disposed) {
                try {
                    pc?.close()
                } catch (_: Throwable) {
                }
            }
        }
        incoming.offer(ByteArray(0))
    }

    private fun release() {
        board?.cancel()
        synchronized(lock) {
            if (disposed) return
            disposed = true
            try {
                dc?.unregisterObserver()
            } catch (_: Throwable) {
            }
            try {
                dc?.close()
            } catch (_: Throwable) {
            }
            try {
                dc?.dispose()
            } catch (_: Throwable) {
            }
            try {
                pc?.close()
            } catch (_: Throwable) {
            }
            try {
                pc?.dispose()
            } catch (_: Throwable) {
            }
            try {
                factory?.dispose()
            } catch (_: Throwable) {
            }
            dc = null
            pc = null
            factory = null
        }
    }

    // ----------------------------------------------------------- link set-up

    private fun createPeer() {
        synchronized(initLock) {
            if (!initialised) {
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(ctx.applicationContext)
                        .createInitializationOptions()
                )
                initialised = true
            }
        }
        val f = PeerConnectionFactory.builder().createPeerConnectionFactory()
        val servers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478").createIceServer()
        )
        val cfg = PeerConnection.RTCConfiguration(servers)
        cfg.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        val peer = f.createPeerConnection(cfg, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                log("internet: link state $state")
                if (state == PeerConnection.IceConnectionState.FAILED) {
                    fail("Could not make a direct link. One of the two networks blocks it")
                } else if (state == PeerConnection.IceConnectionState.CLOSED) {
                    fail("Link closed")
                }
            }

            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
                if (state == PeerConnection.IceGatheringState.COMPLETE) gathered.countDown()
            }

            override fun onIceCandidate(candidate: IceCandidate?) {}
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(channel: DataChannel?) {
                if (channel != null) attach(channel)
            }

            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
        }) ?: throw IOException("Could not start the internet link")
        synchronized(lock) {
            factory = f
            pc = peer
        }
    }

    private fun attach(channel: DataChannel) {
        synchronized(lock) { dc = channel }
        channel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previous: Long) {}
            override fun onStateChange() {
                val state = try {
                    channel.state()
                } catch (_: Throwable) {
                    null
                }
                if (state == DataChannel.State.OPEN) channelOpen.countDown()
                if (state == DataChannel.State.CLOSED) fail("Link closed")
            }

            override fun onMessage(buffer: DataChannel.Buffer?) {
                if (buffer == null) return
                val bytes = ByteArray(buffer.data.remaining())
                buffer.data.get(bytes)
                if (bytes.isNotEmpty() && bytes[0] == MSG_ACK) ackArrived.countDown()
                else incoming.offer(bytes)
            }
        })
        if (channel.state() == DataChannel.State.OPEN) channelOpen.countDown()
    }

    private fun fail(reason: String) {
        if (failure == null) failure = reason
        incoming.offer(ByteArray(0))
    }

    private fun check() {
        if (closed) throw IOException("Cancelled")
        failure?.let { throw IOException(it) }
    }

    private fun waitForChannel() {
        val deadline = System.currentTimeMillis() + 60_000
        while (!channelOpen.await(300, TimeUnit.MILLISECONDS)) {
            check()
            if (System.currentTimeMillis() > deadline) {
                throw IOException("Could not make a direct link. One of the two networks blocks it")
            }
        }
        log("internet: direct link is open")
    }

    private fun createDescription(offer: Boolean): SessionDescription {
        val latch = CountDownLatch(1)
        var result: SessionDescription? = null
        var error: String? = null
        val observer = object : SdpObserver {
            override fun onCreateSuccess(description: SessionDescription?) {
                result = description
                latch.countDown()
            }

            override fun onSetSuccess() {}
            override fun onCreateFailure(reason: String?) {
                error = reason
                latch.countDown()
            }

            override fun onSetFailure(reason: String?) {}
        }
        if (offer) pc!!.createOffer(observer, MediaConstraints()) else pc!!.createAnswer(observer, MediaConstraints())
        if (!latch.await(20, TimeUnit.SECONDS)) throw IOException("Link set-up timed out")
        return result ?: throw IOException("Link set-up failed: $error")
    }

    private fun setDescription(local: Boolean, description: SessionDescription) {
        val latch = CountDownLatch(1)
        var error: String? = null
        var ok = false
        val observer = object : SdpObserver {
            override fun onCreateSuccess(d: SessionDescription?) {}
            override fun onSetSuccess() {
                ok = true
                latch.countDown()
            }

            override fun onCreateFailure(reason: String?) {}
            override fun onSetFailure(reason: String?) {
                error = reason
                latch.countDown()
            }
        }
        if (local) pc!!.setLocalDescription(observer, description) else pc!!.setRemoteDescription(observer, description)
        if (!latch.await(20, TimeUnit.SECONDS)) throw IOException("Link set-up timed out")
        if (!ok) throw IOException("Link set-up failed: $error")
    }

    // --------------------------------------------------------- file transfer

    private fun sendMessage(type: Byte, payload: ByteArray, offset: Int, length: Int) {
        val channel = dc ?: throw IOException("Link closed")
        // Do not let unsent data pile up in memory: wait while the link is busy.
        while (channel.bufferedAmount() > MAX_BUFFERED) {
            check()
            Thread.sleep(4)
        }
        check()
        val bb = ByteBuffer.allocate(1 + length)
        bb.put(type)
        bb.put(payload, offset, length)
        bb.flip()
        if (!channel.send(DataChannel.Buffer(bb, true))) throw IOException("Link closed while sending")
    }

    private fun sendFiles(items: List<Transfer.Item>, p: Progress) {
        val hello = ByteBuffer.allocate(12)
        hello.putInt(items.size)
        hello.putLong(p.total)
        sendMessage(MSG_HELLO, hello.array(), 0, 12)
        val buf = ByteArray(CHUNK)
        for (item in items) {
            p.file = item.name
            val name = item.name.toByteArray(Charsets.UTF_8)
            sendMessage(MSG_FILE, name, 0, name.size)
            val ins = ctx.contentResolver.openInputStream(item.uri)
                ?: throw IOException("Cannot open ${item.name}")
            ins.use { stream ->
                while (true) {
                    val n = stream.read(buf)
                    if (n < 0) break
                    if (n == 0) continue
                    sendMessage(MSG_DATA, buf, 0, n)
                    p.done.addAndGet(n.toLong())
                }
            }
            sendMessage(MSG_FILE_END, buf, 0, 0)
            p.files.incrementAndGet()
        }
        sendMessage(MSG_DONE, buf, 0, 0)
        val deadline = System.currentTimeMillis() + 180_000
        while (!ackArrived.await(300, TimeUnit.MILLISECONDS)) {
            check()
            if (System.currentTimeMillis() > deadline) throw IOException("Receiver did not confirm")
        }
    }

    private fun receiveFiles(p: Progress) {
        var out: OutputStream? = null
        var uri: Uri? = null
        var name = ""
        var finished = false
        try {
            while (true) {
                val m = incoming.poll(90, TimeUnit.SECONDS) ?: throw IOException("Link went silent")
                if (m.isEmpty()) {
                    check()
                    throw IOException("Link closed")
                }
                when (m[0]) {
                    MSG_HELLO -> {
                        if (m.size >= 13) p.total = ByteBuffer.wrap(m).getLong(5)
                    }
                    MSG_FILE -> {
                        name = Transfer.cleanName(String(m, 1, m.size - 1, Charsets.UTF_8))
                        p.file = name
                        val u = Transfer.newDownload(ctx, name)
                        uri = u
                        out = ctx.contentResolver.openOutputStream(u) ?: throw IOException("Cannot create $name")
                    }
                    MSG_DATA -> {
                        val o = out ?: throw IOException("Data without a file")
                        o.write(m, 1, m.size - 1)
                        p.done.addAndGet((m.size - 1).toLong())
                    }
                    MSG_FILE_END -> {
                        out?.close()
                        out = null
                        val u = uri
                        if (u != null) {
                            val v = ContentValues()
                            v.put(MediaStore.Downloads.IS_PENDING, 0)
                            ctx.contentResolver.update(u, v, null, null)
                            p.saved.add(Pair(name, u))
                            p.files.incrementAndGet()
                        }
                        uri = null
                    }
                    MSG_DONE -> {
                        finished = true
                        val channel = dc ?: throw IOException("Link closed")
                        channel.send(DataChannel.Buffer(ByteBuffer.wrap(byteArrayOf(MSG_ACK)), true))
                        // Let the confirmation leave before the link is torn down.
                        Thread.sleep(800)
                        return
                    }
                }
            }
        } finally {
            try {
                out?.close()
            } catch (_: Exception) {
            }
            val u = uri
            if (!finished && u != null) {
                try {
                    ctx.contentResolver.delete(u, null, null)
                } catch (_: Exception) {
                }
            }
        }
    }

    // ------------------------------------------------- the public note board

    private inner class Board(code: String) {
        private val base = "https://ntfy.sh/dropswift-link-$code"
        private val seen = HashSet<String>()
        @Volatile private var current: HttpURLConnection? = null
        @Volatile private var cancelled = false

        fun cancel() {
            cancelled = true
            val c = current
            if (c != null) {
                // disconnect() can block on the network, so keep it off the caller's thread.
                Thread {
                    try {
                        c.disconnect()
                    } catch (_: Throwable) {
                    }
                }.start()
            }
        }

        fun post(text: String) {
            if (text.length > 3900) throw IOException("Link note too long (${text.length})")
            var last: Exception? = null
            for (attempt in 0 until 4) {
                check()
                try {
                    val c = URL(base).openConnection() as HttpURLConnection
                    c.requestMethod = "POST"
                    c.connectTimeout = 15_000
                    c.readTimeout = 15_000
                    c.doOutput = true
                    c.setRequestProperty("Content-Type", "text/plain")
                    c.outputStream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                    val status = c.responseCode
                    c.disconnect()
                    if (status in 200..299) return
                    last = IOException("helper answered $status")
                } catch (e: Exception) {
                    last = e
                }
                Thread.sleep(1500)
            }
            throw IOException("No internet, or the link helper is unreachable (${last?.message})")
        }

        fun waitFor(prefix: String, timeoutMs: Long): String {
            val deadline = System.currentTimeMillis() + timeoutMs
            var problems = 0
            while (System.currentTimeMillis() < deadline) {
                check()
                if (cancelled) throw IOException("Cancelled")
                try {
                    val c = URL("$base/json?since=30m").openConnection() as HttpURLConnection
                    current = c
                    c.connectTimeout = 15_000
                    c.readTimeout = 100_000
                    val status = c.responseCode
                    if (status !in 200..299) throw IOException("helper answered $status")
                    BufferedReader(InputStreamReader(c.inputStream, Charsets.UTF_8)).use { reader ->
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isBlank()) continue
                            val o = try {
                                JSONObject(line)
                            } catch (_: Exception) {
                                continue
                            }
                            problems = 0
                            if (o.optString("event") != "message") continue
                            if (!seen.add(o.optString("id"))) continue
                            val text = o.optString("message")
                            if (text.startsWith(prefix)) return text.substring(prefix.length)
                        }
                    }
                } catch (e: Exception) {
                    if (cancelled || closed) throw IOException("Cancelled")
                    problems++
                    if (problems == 3) log("internet: waiting, helper not reachable (${e.message})")
                    if (problems > 40) throw IOException("No internet, or the link helper is unreachable")
                } finally {
                    current = null
                }
                Thread.sleep(3000)
            }
            throw IOException("The other phone did not join in time")
        }
    }

    companion object {
        private val initLock = Any()
        private var initialised = false

        private const val CHUNK = 60 * 1024
        private const val MAX_BUFFERED = 4L * 1024 * 1024

        private const val MSG_HELLO: Byte = 0
        private const val MSG_FILE: Byte = 1
        private const val MSG_DATA: Byte = 2
        private const val MSG_FILE_END: Byte = 3
        private const val MSG_DONE: Byte = 4
        private const val MSG_ACK: Byte = 9

        /** Shrinks the "how to reach me" note so it fits in one small message. */
        fun pack(text: String): String {
            val input = text.toByteArray(Charsets.UTF_8)
            val deflater = Deflater(Deflater.BEST_COMPRESSION)
            deflater.setInput(input)
            deflater.finish()
            val out = ByteArrayOutputStream()
            val buf = ByteArray(4096)
            while (!deflater.finished()) out.write(buf, 0, deflater.deflate(buf))
            deflater.end()
            return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP or Base64.URL_SAFE)
        }

        fun unpack(text: String): String {
            val inflater = Inflater()
            inflater.setInput(Base64.decode(text, Base64.NO_WRAP or Base64.URL_SAFE))
            val out = ByteArrayOutputStream()
            val buf = ByteArray(4096)
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                out.write(buf, 0, n)
            }
            inflater.end()
            return String(out.toByteArray(), Charsets.UTF_8)
        }
    }
}
