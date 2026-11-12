package app.dropswift

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Live numbers the screen reads while a transfer runs. */
class Progress {
    val done = AtomicLong(0)
    val files = AtomicInteger(0)
    @Volatile var total: Long = 0
    @Volatile var file: String = ""
    @Volatile var streams: Int = 1
    /** "" while moving data, "verify" while the receiver checks SHA-256. */
    @Volatile var phase: String = ""
    val verifyDone = AtomicLong(0)
    val verified = AtomicInteger(0)
    /** Time spent waiting on the Wi-Fi link and on phone storage (summed over all connections). */
    val netNanos = AtomicLong(0)
    val diskNanos = AtomicLong(0)
    /** Files saved on this phone in this transfer: name and where it is. */
    val saved: MutableList<Pair<String, Uri>> = java.util.Collections.synchronizedList(ArrayList())
}

/**
 * Nearby protocol, version 3. The SENDER owns the Wi-Fi Direct group and listens on PORT.
 * Every receiver opens STREAMS connections and starts each with:
 *   int magic, UTF secret, long receiverId, int streamIndex, int streamCount, UTF deviceName
 * The sender then writes on connection 0: int magic, int fileCount, long totalBytes, per file (UTF name, long size).
 * After that every connection carries records, in any order:
 *   1 = block     : int fileIndex, long offset, int len, len bytes
 *   2 = file end  : int fileIndex, long fileLength, 32 bytes SHA-256 of the whole file
 *   3 = no more records on this connection
 * The receiver checks every file's SHA-256 and answers on connection 0 with one byte:
 *   1 = all files arrived and match, 2 = at least one file did not match.
 */
object Transfer {
    const val PORT = 47800
    const val STREAMS = 4
    const val SOCKET_BUFFER = 2 shl 20
    private const val MAGIC = 0x44535733
    private const val BLOCK = 1 shl 20
    private const val HASH = 32

    private const val REC_BLOCK = 1
    private const val REC_FILE_END = 2
    private const val REC_STREAM_END = 3

    private const val ACK_OK = 1
    private const val ACK_BAD = 2

    class Item(val uri: Uri, val name: String, val size: Long)

    /** Everything was handed to the link, but the receiver's "got it" never came back. */
    class NotConfirmed(why: String) : IOException("not confirmed: $why")

    /** A file arrived but its SHA-256 did not match the sender's. */
    class Corrupt(what: String) : IOException("integrity failed: $what")

    private class Rec(val buf: ByteArray?) {
        var kind = REC_BLOCK
        var file = 0
        var offset = 0L
        var len = 0
        var hash: ByteArray? = null
    }

    private val END = Rec(null)

    fun describe(ctx: Context, uris: List<Uri>): List<Item> {
        val out = ArrayList<Item>()
        for ((i, uri) in uris.withIndex()) {
            var name = "file-${i + 1}"
            var size = 0L
            try {
                ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val si = c.getColumnIndex(OpenableColumns.SIZE)
                        if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni)
                        if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                    }
                }
            } catch (_: Exception) {
            }
            out.add(Item(uri, name, size))
        }
        return out
    }

    /** Fills the buffer completely unless the file ends first. Returns bytes read (0 at end). */
    private fun readFull(stream: InputStream, buf: ByteArray): Int {
        var got = 0
        while (got < buf.size) {
            val n = stream.read(buf, got, buf.size - got)
            if (n < 0) break
            got += n
        }
        return got
    }

    private fun closeQuietly(c: Closeable?) {
        try {
            c?.close()
        } catch (_: Exception) {
        }
    }

    // ------------------------------------------------------- receiver joins

    /** Receiver side: open all connections to the sender. */
    fun join(owner: InetAddress, port: Int, secret: String, device: String, track: (Closeable) -> Unit): List<Socket> {
        val id = SecureRandom().nextLong()
        val sockets = ArrayList<Socket>()
        for (i in 0 until STREAMS) {
            var s: Socket? = null
            var last: Exception? = null
            // The link can need a few seconds before the sender is reachable.
            val tries = if (i == 0) 40 else 5
            for (attempt in 0 until tries) {
                val c = Socket()
                try {
                    c.receiveBufferSize = SOCKET_BUFFER
                    c.connect(InetSocketAddress(owner, port), 1500)
                    s = c
                    break
                } catch (e: IOException) {
                    last = e
                    closeQuietly(c)
                    Thread.sleep(500)
                }
            }
            val sock = s ?: throw IOException("sender not reachable (${last?.message})")
            track(sock)
            val o = DataOutputStream(BufferedOutputStream(sock.getOutputStream(), 512))
            o.writeInt(MAGIC)
            o.writeUTF(secret)
            o.writeLong(id)
            o.writeInt(i)
            o.writeInt(STREAMS)
            o.writeUTF(device.take(60))
            o.flush()
            sockets.add(sock)
        }
        return sockets
    }

    private class Pending(val name: String, count: Int) {
        val sockets = arrayOfNulls<Socket>(count)
        var have = 0
        val since = System.currentTimeMillis()
    }

    /**
     * Sender side: accept receivers until the server socket is closed.
     * Each complete set of connections from one receiver is handed to [onReceiver].
     */
    fun serve(server: ServerSocket, secret: String, track: (Closeable) -> Unit, onReceiver: (String, List<Socket>) -> Unit) {
        val pending = HashMap<Long, Pending>()
        while (true) {
            val s = try {
                server.accept()
            } catch (e: IOException) {
                return
            }
            track(s)
            thread(name = "ds-hello") {
                try {
                    s.soTimeout = 10_000
                    // Unbuffered on purpose: nothing beyond the hello may be read here.
                    val inp = DataInputStream(s.getInputStream())
                    if (inp.readInt() != MAGIC) throw IOException("old or foreign app")
                    if (inp.readUTF() != secret) throw IOException("wrong secret")
                    val id = inp.readLong()
                    val index = inp.readInt()
                    val count = inp.readInt()
                    val name = inp.readUTF()
                    if (count < 1 || count > 16 || index < 0 || index >= count) throw IOException("bad hello")
                    s.soTimeout = 0
                    s.sendBufferSize = SOCKET_BUFFER
                    var ready: Pending? = null
                    synchronized(pending) {
                        // Forget half-made joins that never finished.
                        val now = System.currentTimeMillis()
                        pending.entries.removeAll { now - it.value.since > 30_000 }
                        val p = pending.getOrPut(id) { Pending(name, count) }
                        if (p.sockets.size == count && p.sockets[index] == null) {
                            p.sockets[index] = s
                            p.have++
                            if (p.have == count) {
                                pending.remove(id)
                                ready = p
                            }
                        } else {
                            closeQuietly(s)
                        }
                    }
                    ready?.let { r -> onReceiver(r.name, r.sockets.map { it!! }) }
                } catch (e: Exception) {
                    closeQuietly(s)
                }
            }
        }
    }

    // ------------------------------------------------------------------ send

    fun send(ctx: Context, sockets: List<Socket>, items: List<Item>, p: Progress) {
        val n = sockets.size
        p.streams = n
        p.total = items.sumOf { it.size }
        val outs = sockets.map { DataOutputStream(BufferedOutputStream(it.getOutputStream(), 64 * 1024)) }
        val first = outs[0]
        first.writeInt(MAGIC)
        first.writeInt(items.size)
        first.writeLong(p.total)
        for (item in items) {
            first.writeUTF(item.name)
            first.writeLong(item.size)
        }
        first.flush()

        val poolSize = n * 3 + 2
        val free = ArrayBlockingQueue<Rec>(poolSize)
        for (i in 0 until poolSize) free.put(Rec(ByteArray(BLOCK)))
        val queue = ArrayBlockingQueue<Rec>(poolSize + 8)
        val error = AtomicReference<Throwable?>(null)
        val workers = ArrayList<Thread>()

        fun fail(e: Throwable) {
            if (error.compareAndSet(null, e)) {
                for (s in sockets) closeQuietly(s)
                synchronized(workers) { for (w in workers) w.interrupt() }
            }
        }

        // One thread reads the files in order, cuts them into blocks and fingerprints them.
        val reader = thread(start = false, name = "ds-read") {
            try {
                for ((index, item) in items.withIndex()) {
                    p.file = item.name
                    val md = MessageDigest.getInstance("SHA-256")
                    val ins = ctx.contentResolver.openInputStream(item.uri)
                        ?: throw IOException("Cannot open ${item.name}")
                    var offset = 0L
                    ins.use { stream ->
                        while (true) {
                            val r = free.take()
                            val t0 = System.nanoTime()
                            val got = readFull(stream, r.buf!!)
                            if (got > 0) md.update(r.buf, 0, got)
                            p.diskNanos.addAndGet(System.nanoTime() - t0)
                            if (got <= 0) {
                                free.put(r)
                                break
                            }
                            r.kind = REC_BLOCK
                            r.file = index
                            r.offset = offset
                            r.len = got
                            offset += got
                            queue.put(r)
                        }
                    }
                    val end = Rec(null)
                    end.kind = REC_FILE_END
                    end.file = index
                    end.offset = offset
                    end.hash = md.digest()
                    queue.put(end)
                }
                queue.put(END)
            } catch (_: InterruptedException) {
            } catch (e: Throwable) {
                fail(e)
            }
        }

        // Several threads push blocks over their own connection at the same time.
        val senders = outs.map { out ->
            thread(start = false, name = "ds-net") {
                try {
                    while (true) {
                        val r = queue.take()
                        if (r === END) {
                            queue.put(END)
                            out.writeByte(REC_STREAM_END)
                            out.flush()
                            break
                        }
                        if (r.kind == REC_FILE_END) {
                            out.writeByte(REC_FILE_END)
                            out.writeInt(r.file)
                            out.writeLong(r.offset)
                            out.write(r.hash!!)
                            p.files.incrementAndGet()
                        } else {
                            val t0 = System.nanoTime()
                            out.writeByte(REC_BLOCK)
                            out.writeInt(r.file)
                            out.writeLong(r.offset)
                            out.writeInt(r.len)
                            out.write(r.buf!!, 0, r.len)
                            p.netNanos.addAndGet(System.nanoTime() - t0)
                            p.done.addAndGet(r.len.toLong())
                            free.put(r)
                        }
                    }
                } catch (_: InterruptedException) {
                } catch (e: Throwable) {
                    fail(e)
                }
            }
        }

        synchronized(workers) {
            workers.add(reader)
            workers.addAll(senders)
        }
        for (w in workers) w.start()
        for (w in workers) w.join()
        error.get()?.let { throw IOException(it.message ?: it.javaClass.simpleName) }

        // The receiver checks every file before answering; big files take a while to check.
        sockets[0].soTimeout = 15 * 60_000
        val ack = try {
            sockets[0].getInputStream().read()
        } catch (e: IOException) {
            throw NotConfirmed(e.message ?: e.javaClass.simpleName)
        }
        for (s in sockets) closeQuietly(s)
        if (ack == ACK_BAD) throw Corrupt("receiver found a mismatch")
        if (ack != ACK_OK) throw NotConfirmed("no answer")
    }

    // --------------------------------------------------------------- receive

    private class Incoming(val name: String) {
        var uri: Uri? = null
        var pfd: ParcelFileDescriptor? = null
        var channel: FileChannel? = null
        var received = 0L
        var expected = -1L
        var hash: ByteArray? = null
        var finished = false
    }

    fun receive(ctx: Context, sockets: List<Socket>, p: Progress) {
        p.streams = sockets.size
        val ins = sockets.map { DataInputStream(BufferedInputStream(it.getInputStream(), 64 * 1024)) }
        val control = ins[0]
        if (control.readInt() != MAGIC) throw IOException("Other phone has a different DropSwift version. Update both")
        val count = control.readInt()
        if (count < 0 || count > 100_000) throw IOException("Bad file count")
        p.total = control.readLong()
        val files = ArrayList<Incoming>(count)
        for (i in 0 until count) {
            files.add(Incoming(cleanName(control.readUTF())))
            control.readLong()
        }

        val error = AtomicReference<Throwable?>(null)

        fun fail(e: Throwable) {
            if (error.compareAndSet(null, e)) {
                for (s in sockets) closeQuietly(s)
            }
        }

        fun ensureOpen(f: Incoming) {
            synchronized(f) {
                if (f.channel == null && !f.finished) {
                    val uri = newDownload(ctx, f.name)
                    f.uri = uri
                    val pfd = ctx.contentResolver.openFileDescriptor(uri, "rw")
                        ?: throw IOException("Cannot create ${f.name}")
                    f.pfd = pfd
                    f.channel = FileOutputStream(pfd.fileDescriptor).channel
                }
            }
        }

        fun finishIfComplete(f: Incoming) {
            // Caller holds the lock on f.
            if (f.finished || f.expected < 0 || f.received != f.expected) return
            f.finished = true
            closeQuietly(f.channel)
            closeQuietly(f.pfd)
            f.channel = null
            f.pfd = null
            p.files.incrementAndGet()
        }

        val workers = ins.map { inp ->
            thread(name = "ds-net") {
                try {
                    val buf = ByteArray(BLOCK)
                    while (true) {
                        val t0 = System.nanoTime()
                        val kind = inp.readUnsignedByte()
                        if (kind == REC_STREAM_END) break
                        if (kind == REC_FILE_END) {
                            val fi = inp.readInt()
                            val length = inp.readLong()
                            val hash = ByteArray(HASH)
                            inp.readFully(hash)
                            if (fi < 0 || fi >= files.size || length < 0) throw IOException("Bad file record")
                            val f = files[fi]
                            ensureOpen(f)
                            synchronized(f) {
                                f.expected = length
                                f.hash = hash
                                finishIfComplete(f)
                            }
                            continue
                        }
                        if (kind != REC_BLOCK) throw IOException("Bad record")
                        val fi = inp.readInt()
                        val offset = inp.readLong()
                        val len = inp.readInt()
                        if (fi < 0 || fi >= files.size || offset < 0 || len <= 0 || len > BLOCK) {
                            throw IOException("Bad data block")
                        }
                        inp.readFully(buf, 0, len)
                        val t1 = System.nanoTime()
                        p.netNanos.addAndGet(t1 - t0)

                        val f = files[fi]
                        p.file = f.name
                        ensureOpen(f)
                        val ch = f.channel ?: throw IOException("File already closed: ${f.name}")
                        val bb = ByteBuffer.wrap(buf, 0, len)
                        var pos = offset
                        while (bb.hasRemaining()) pos += ch.write(bb, pos)
                        p.diskNanos.addAndGet(System.nanoTime() - t1)
                        p.done.addAndGet(len.toLong())
                        synchronized(f) {
                            f.received += len
                            finishIfComplete(f)
                        }
                    }
                } catch (e: Throwable) {
                    fail(e)
                }
            }
        }
        for (w in workers) w.join()

        fun dropUnsaved() {
            for (f in files) {
                closeQuietly(f.channel)
                closeQuietly(f.pfd)
                val uri = f.uri ?: continue
                if (p.saved.any { it.second == uri }) continue
                try {
                    ctx.contentResolver.delete(uri, null, null)
                } catch (_: Exception) {
                }
            }
        }

        var problem: Throwable? = error.get()
        if (problem == null && files.any { !it.finished }) problem = IOException("Some files did not arrive completely")
        if (problem != null) {
            dropUnsaved()
            throw IOException(problem.message ?: problem.javaClass.simpleName)
        }

        // Check every file against the sender's fingerprint by reading it back from storage.
        p.phase = "verify"
        val bad = ArrayList<String>()
        val buf = ByteArray(BLOCK)
        for (f in files) {
            val uri = f.uri ?: continue
            val md = MessageDigest.getInstance("SHA-256")
            val stream = ctx.contentResolver.openInputStream(uri) ?: throw IOException("Cannot re-open ${f.name}")
            stream.use {
                while (true) {
                    val got = it.read(buf)
                    if (got < 0) break
                    md.update(buf, 0, got)
                    p.verifyDone.addAndGet(got.toLong())
                }
            }
            if (md.digest().contentEquals(f.hash)) {
                val v = ContentValues()
                v.put(MediaStore.Downloads.IS_PENDING, 0)
                ctx.contentResolver.update(uri, v, null, null)
                p.saved.add(Pair(f.name, uri))
                p.verified.incrementAndGet()
            } else {
                bad.add(f.name)
            }
        }

        val os = sockets[0].getOutputStream()
        if (bad.isNotEmpty()) {
            dropUnsaved()
            try {
                os.write(ACK_BAD)
                os.flush()
            } catch (_: Exception) {
            }
            throw Corrupt(bad.joinToString(", ").take(200))
        }
        os.write(ACK_OK)
        os.flush()
        // Keep the link up until the sender has read the answer and hung up (at most 10 s).
        try {
            sockets[0].soTimeout = 10_000
            while (control.read() >= 0) {
            }
        } catch (_: Exception) {
        }
    }

    fun cleanName(raw: String): String {
        val n = raw.replace('/', '_').replace('\\', '_').replace("\u0000", "").trim()
        return if (n.isEmpty() || n == "." || n == "..") "file" else n.take(200)
    }

    fun newDownload(ctx: Context, name: String): Uri {
        val v = ContentValues()
        v.put(MediaStore.Downloads.DISPLAY_NAME, name)
        v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/DropSwift")
        v.put(MediaStore.Downloads.IS_PENDING, 1)
        return ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)
            ?: throw IOException("Cannot create $name in Downloads")
    }
}
