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
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
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
    /** Time spent waiting on the Wi-Fi link and on phone storage (summed over all connections). */
    val netNanos = AtomicLong(0)
    val diskNanos = AtomicLong(0)
}

/**
 * Wire format, version 2. The sender opens STREAMS connections to the receiver.
 * Every connection starts with: int magic, int streamIndex, int streamCount.
 * Connection 0 then carries the list: int fileCount, long totalBytes, per file (UTF name, long sizeHint).
 * After that every connection carries records, in any order:
 *   1 = block     : int fileIndex, long offset, int len, len bytes
 *   2 = file end  : int fileIndex, long fileLength
 *   3 = no more records on this connection
 * When every file is complete the receiver answers with one byte 1 on connection 0.
 */
object Transfer {
    const val PORT = 47800
    const val STREAMS = 4
    const val SOCKET_BUFFER = 2 shl 20
    private const val MAGIC = 0x44535732
    private const val BLOCK = 1 shl 20

    private const val REC_BLOCK = 1
    private const val REC_FILE_END = 2
    private const val REC_STREAM_END = 3

    class Item(val uri: Uri, val name: String, val size: Long)

    private class Rec(val buf: ByteArray?) {
        var kind = REC_BLOCK
        var file = 0
        var offset = 0L
        var len = 0
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

    // ------------------------------------------------------------------ send

    fun send(ctx: Context, sockets: List<Socket>, items: List<Item>, p: Progress) {
        val n = sockets.size
        p.streams = n
        val outs = ArrayList<DataOutputStream>()
        for ((i, s) in sockets.withIndex()) {
            val o = DataOutputStream(BufferedOutputStream(s.getOutputStream(), 64 * 1024))
            o.writeInt(MAGIC)
            o.writeInt(i)
            o.writeInt(n)
            outs.add(o)
        }
        val first = outs[0]
        first.writeInt(items.size)
        first.writeLong(p.total)
        for (item in items) {
            first.writeUTF(item.name)
            first.writeLong(item.size)
        }
        for (o in outs) o.flush()

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

        // One thread reads the files in order and cuts them into blocks.
        val reader = thread(start = false, name = "ds-read") {
            try {
                for ((index, item) in items.withIndex()) {
                    p.file = item.name
                    val ins = ctx.contentResolver.openInputStream(item.uri)
                        ?: throw IOException("Cannot open ${item.name}")
                    var offset = 0L
                    ins.use { stream ->
                        while (true) {
                            val r = free.take()
                            val t0 = System.nanoTime()
                            val got = readFull(stream, r.buf!!)
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

        sockets[0].soTimeout = 120_000
        if (sockets[0].getInputStream().read() != 1) throw IOException("Receiver did not confirm")
    }

    // --------------------------------------------------------------- receive

    private class Incoming(val name: String) {
        var uri: Uri? = null
        var pfd: ParcelFileDescriptor? = null
        var channel: FileChannel? = null
        var received = 0L
        var expected = -1L
        var finished = false
    }

    private class Stream(val socket: Socket, val inp: DataInputStream, val index: Int, val count: Int)

    private fun openStream(socket: Socket): Stream {
        val inp = DataInputStream(BufferedInputStream(socket.getInputStream(), 64 * 1024))
        if (inp.readInt() != MAGIC) throw IOException("Other phone has a different DropSwift version. Update both")
        val index = inp.readInt()
        val count = inp.readInt()
        if (count < 1 || count > 16 || index < 0 || index >= count) throw IOException("Bad connection header")
        return Stream(socket, inp, index, count)
    }

    fun receive(
        ctx: Context,
        server: ServerSocket,
        p: Progress,
        track: (Closeable) -> Unit,
        onStart: () -> Unit
    ) {
        val firstSocket = server.accept()
        track(firstSocket)
        onStart()
        val firstStream = openStream(firstSocket)
        val streamCount = firstStream.count
        val streams = ArrayList<Stream>()
        streams.add(firstStream)
        server.soTimeout = 20_000
        while (streams.size < streamCount) {
            val s = server.accept()
            track(s)
            streams.add(openStream(s))
        }
        p.streams = streamCount
        val control = streams.firstOrNull { it.index == 0 } ?: throw IOException("Missing first connection")

        val count = control.inp.readInt()
        if (count < 0 || count > 100_000) throw IOException("Bad file count")
        p.total = control.inp.readLong()
        val files = ArrayList<Incoming>(count)
        for (i in 0 until count) {
            files.add(Incoming(cleanName(control.inp.readUTF())))
            control.inp.readLong()
        }

        val error = AtomicReference<Throwable?>(null)

        fun fail(e: Throwable) {
            if (error.compareAndSet(null, e)) {
                for (s in streams) closeQuietly(s.socket)
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
            val v = ContentValues()
            v.put(MediaStore.Downloads.IS_PENDING, 0)
            ctx.contentResolver.update(f.uri!!, v, null, null)
            p.files.incrementAndGet()
        }

        val workers = streams.map { st ->
            thread(name = "ds-net") {
                try {
                    val buf = ByteArray(BLOCK)
                    val inp = st.inp
                    while (true) {
                        val t0 = System.nanoTime()
                        val kind = inp.readUnsignedByte()
                        if (kind == REC_STREAM_END) break
                        if (kind == REC_FILE_END) {
                            val fi = inp.readInt()
                            val length = inp.readLong()
                            if (fi < 0 || fi >= files.size || length < 0) throw IOException("Bad file record")
                            val f = files[fi]
                            ensureOpen(f)
                            synchronized(f) {
                                f.expected = length
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

        var problem: Throwable? = error.get()
        if (problem == null && files.any { !it.finished }) problem = IOException("Some files did not arrive completely")
        if (problem != null) {
            for (f in files) {
                if (f.finished) continue
                closeQuietly(f.channel)
                closeQuietly(f.pfd)
                val uri = f.uri
                if (uri != null) {
                    try {
                        ctx.contentResolver.delete(uri, null, null)
                    } catch (_: Exception) {
                    }
                }
            }
            throw IOException(problem.message ?: problem.javaClass.simpleName)
        }

        val os = control.socket.getOutputStream()
        os.write(1)
        os.flush()
    }

    private fun cleanName(raw: String): String {
        val n = raw.replace('/', '_').replace('\\', '_').replace("\u0000", "").trim()
        return if (n.isEmpty() || n == "." || n == "..") "file" else n.take(200)
    }

    private fun newDownload(ctx: Context, name: String): Uri {
        val v = ContentValues()
        v.put(MediaStore.Downloads.DISPLAY_NAME, name)
        v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/DropSwift")
        v.put(MediaStore.Downloads.IS_PENDING, 1)
        return ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)
            ?: throw IOException("Cannot create $name in Downloads")
    }
}
