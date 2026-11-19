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
import java.util.BitSet
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
    /** Bytes the other side already had from an earlier try (not sent again). */
    @Volatile var resumedBytes: Long = 0
    /** Time spent waiting on the Wi-Fi link and on phone storage (summed over all connections). */
    val netNanos = AtomicLong(0)
    val diskNanos = AtomicLong(0)
    /** How long the data itself took to move, without the receiver's checking time. */
    @Volatile var dataNanos: Long = 0
    /** Files saved on this phone in this transfer: name and where it is. */
    val saved: MutableList<Pair<String, Uri>> = java.util.Collections.synchronizedList(ArrayList())
}

/**
 * What a receiver already has of one transfer, kept between tries so a broken link
 * or a pause continues from where it stopped instead of starting again.
 */
class ResumeState(val transferId: Long, val names: List<String>, val sizes: List<Long>) {

    class RFile(val name: String) {
        var uri: Uri? = null
        var pfd: ParcelFileDescriptor? = null
        var channel: FileChannel? = null
        /** Which 1 MB blocks are safely written. */
        val blocks = BitSet()
        var expected = -1L
        var hash: ByteArray? = null
        /** All data is here (not yet checked). */
        var complete = false
        /** SHA-256 checked and the file is visible in Downloads. */
        var verified = false
    }

    val files = names.map { RFile(it) }

    fun matches(id: Long, n: List<String>, s: List<Long>) = id == transferId && n == names && s == sizes

    /** Rough count of bytes already on this phone. */
    fun haveBytes(block: Int): Long {
        var sum = 0L
        for ((i, f) in files.withIndex()) {
            synchronized(f) {
                sum += if (f.complete) maxOf(f.expected, 0L) else minOf(f.blocks.cardinality().toLong() * block, sizes[i])
            }
        }
        return sum
    }

    fun savedList(): List<Pair<String, Uri>> =
        files.filter { it.verified && it.uri != null }.map { Pair(it.name, it.uri!!) }

    /** Deletes every file that was not finished and checked, so no half files are left behind. */
    fun discardUnfinished(ctx: Context) {
        for (f in files) {
            synchronized(f) {
                closeFile(f)
                val uri = f.uri
                if (!f.verified && uri != null) {
                    try {
                        ctx.contentResolver.delete(uri, null, null)
                    } catch (_: Exception) {
                    }
                    f.uri = null
                }
            }
        }
    }

    companion object {
        fun closeFile(f: RFile) {
            try {
                f.channel?.close()
            } catch (_: Exception) {
            }
            try {
                f.pfd?.close()
            } catch (_: Exception) {
            }
            f.channel = null
            f.pfd = null
        }
    }
}

/** Receiver keeps one of these for the whole session. */
class ResumeStore {
    @Volatile var state: ResumeState? = null
}

/**
 * Nearby protocol, version 4. The SENDER owns the link and listens on PORT.
 * Every receiver opens STREAMS connections and starts each with:
 *   int magic, UTF secret, long receiverId, int streamIndex, int streamCount, UTF deviceName
 * The sender writes on connection 0: int magic, long transferId, int fileCount, long totalBytes,
 *   per file (UTF name, long size).
 * The receiver answers on connection 0 with what it already has, per file one byte:
 *   0 = nothing, 1 = complete and checked, 2 = partly: int length + bitmap of 1 MB blocks it has.
 * Then every connection carries records, in any order:
 *   1 = block     : int fileIndex, long offset, int len, len bytes
 *   2 = file end  : int fileIndex, long fileLength, 32 bytes SHA-256 of the whole file
 *   3 = no more records on this connection
 * The receiver checks every new file's SHA-256 and answers on connection 0 with one byte:
 *   1 = all files arrived and match, 2 = at least one did not match (those will be sent again on the next try).
 */
object Transfer {
    const val PORT = 47800
    const val STREAMS = 4
    const val SOCKET_BUFFER = 2 shl 20
    private const val MAGIC = 0x44535734
    const val BLOCK = 1 shl 20
    private const val HASH = 32

    private const val REC_BLOCK = 1
    private const val REC_FILE_END = 2
    private const val REC_STREAM_END = 3

    private const val HAVE_NONE = 0
    private const val HAVE_ALL = 1
    private const val HAVE_SOME = 2

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

    /** Receiver side: open all connections to the sender. [id] stays the same across retries. */
    fun join(
        owner: InetAddress, port: Int, secret: String, device: String, id: Long,
        track: (Closeable) -> Unit
    ): List<Socket> {
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
            sock.keepAlive = true
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
     * Each complete set of connections from one receiver is handed to [onReceiver] with its id.
     */
    fun serve(
        server: ServerSocket, secret: String, track: (Closeable) -> Unit,
        onReceiver: (Long, String, List<Socket>) -> Unit
    ) {
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
                    s.keepAlive = true
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
                    ready?.let { r -> onReceiver(id, r.name, r.sockets.map { it!! }) }
                } catch (e: Exception) {
                    closeQuietly(s)
                }
            }
        }
    }

    // ------------------------------------------------------------------ send

    fun send(ctx: Context, sockets: List<Socket>, items: List<Item>, transferId: Long, p: Progress) {
        val n = sockets.size
        p.streams = n
        p.total = items.sumOf { it.size }
        val outs = sockets.map { DataOutputStream(BufferedOutputStream(it.getOutputStream(), 64 * 1024)) }
        val control = DataInputStream(BufferedInputStream(sockets[0].getInputStream()))
        val first = outs[0]
        first.writeInt(MAGIC)
        first.writeLong(transferId)
        first.writeInt(items.size)
        first.writeLong(p.total)
        for (item in items) {
            first.writeUTF(item.name)
            first.writeLong(item.size)
        }
        first.flush()

        // What does the receiver already have?
        sockets[0].soTimeout = 60_000
        val haveAll = BooleanArray(items.size)
        val haveBlocks = arrayOfNulls<BitSet>(items.size)
        var skipped = 0L
        for (i in items.indices) {
            when (control.readUnsignedByte()) {
                HAVE_ALL -> {
                    haveAll[i] = true
                    skipped += items[i].size
                }
                HAVE_SOME -> {
                    val len = control.readInt()
                    if (len < 0 || len > 64 * 1024 * 1024) throw IOException("Bad resume record")
                    val bytes = ByteArray(len)
                    control.readFully(bytes)
                    val bits = BitSet.valueOf(bytes)
                    haveBlocks[i] = bits
                    skipped += minOf(bits.cardinality().toLong() * BLOCK, items[i].size)
                }
                HAVE_NONE -> {}
                else -> throw IOException("Bad resume record")
            }
        }
        sockets[0].soTimeout = 0
        p.resumedBytes = skipped
        p.done.set(skipped)

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
        // Blocks the receiver already has are read (for the fingerprint) but not sent.
        val reader = thread(start = false, name = "ds-read") {
            try {
                for ((index, item) in items.withIndex()) {
                    if (haveAll[index]) {
                        p.files.incrementAndGet()
                        continue
                    }
                    p.file = item.name
                    val skip = haveBlocks[index]
                    val md = MessageDigest.getInstance("SHA-256")
                    val ins = ctx.contentResolver.openInputStream(item.uri)
                        ?: throw IOException("Cannot open ${item.name}")
                    var offset = 0L
                    var blockNo = 0
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
                            if (skip != null && skip.get(blockNo)) {
                                free.put(r)
                            } else {
                                r.kind = REC_BLOCK
                                r.file = index
                                r.offset = offset
                                r.len = got
                                queue.put(r)
                            }
                            offset += got
                            blockNo++
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
        val started = System.nanoTime()
        for (w in workers) w.start()
        for (w in workers) w.join()
        p.dataNanos = System.nanoTime() - started
        error.get()?.let { throw IOException(it.message ?: it.javaClass.simpleName) }

        // The receiver checks every new file before answering; big files take a while to check.
        sockets[0].soTimeout = 15 * 60_000
        val ack = try {
            control.read()
        } catch (e: IOException) {
            throw NotConfirmed(e.message ?: e.javaClass.simpleName)
        }
        for (s in sockets) closeQuietly(s)
        if (ack == ACK_BAD) throw Corrupt("receiver found a mismatch")
        if (ack != ACK_OK) throw NotConfirmed("no answer")
    }

    // --------------------------------------------------------------- receive

    fun receive(ctx: Context, sockets: List<Socket>, p: Progress, store: ResumeStore) {
        p.streams = sockets.size
        p.phase = ""
        p.verifyDone.set(0)
        val ins = sockets.map { DataInputStream(BufferedInputStream(it.getInputStream(), 64 * 1024)) }
        val control = ins[0]
        if (control.readInt() != MAGIC) throw IOException("Other phone has a different DropSwift version. Update both")
        val transferId = control.readLong()
        val count = control.readInt()
        if (count < 0 || count > 100_000) throw IOException("Bad file count")
        p.total = control.readLong()
        val names = ArrayList<String>(count)
        val sizes = ArrayList<Long>(count)
        for (i in 0 until count) {
            names.add(cleanName(control.readUTF()))
            sizes.add(control.readLong())
        }

        // Same transfer as before? Then continue it; otherwise start fresh.
        var state = store.state
        if (state == null || !state.matches(transferId, names, sizes)) {
            state?.discardUnfinished(ctx)
            state = ResumeState(transferId, names, sizes)
            store.state = state
        }
        val files = state.files

        // Tell the sender what we already have.
        val reply = DataOutputStream(BufferedOutputStream(sockets[0].getOutputStream(), 64 * 1024))
        for (f in files) {
            synchronized(f) {
                when {
                    f.verified -> reply.writeByte(HAVE_ALL)
                    f.uri != null && !f.blocks.isEmpty -> {
                        val bytes = f.blocks.toByteArray()
                        reply.writeByte(HAVE_SOME)
                        reply.writeInt(bytes.size)
                        reply.write(bytes)
                    }
                    else -> reply.writeByte(HAVE_NONE)
                }
                // Data that was complete but not checked is checked again below after any new blocks.
                if (!f.verified) f.complete = false
            }
        }
        reply.flush()
        val already = state.haveBytes(BLOCK)
        p.resumedBytes = already
        p.done.set(already)
        p.files.set(files.count { it.verified })
        p.verified.set(files.count { it.verified })

        val error = AtomicReference<Throwable?>(null)

        fun fail(e: Throwable) {
            if (error.compareAndSet(null, e)) {
                for (s in sockets) closeQuietly(s)
            }
        }

        fun ensureOpen(f: ResumeState.RFile) {
            synchronized(f) {
                if (f.channel == null && !f.verified) {
                    var uri = f.uri
                    if (uri == null) {
                        uri = newDownload(ctx, f.name)
                        f.uri = uri
                    }
                    val pfd = ctx.contentResolver.openFileDescriptor(uri, "rw")
                        ?: throw IOException("Cannot create ${f.name}")
                    f.pfd = pfd
                    f.channel = FileOutputStream(pfd.fileDescriptor).channel
                }
            }
        }

        fun checkComplete(f: ResumeState.RFile) {
            // Caller holds the lock on f.
            if (f.complete || f.expected < 0) return
            val needed = ((f.expected + BLOCK - 1) / BLOCK).toInt()
            if (f.blocks.cardinality() >= needed && f.blocks.nextClearBit(0) >= needed) {
                f.complete = true
                ResumeState.closeFile(f)
                p.files.incrementAndGet()
            }
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
                                checkComplete(f)
                            }
                            continue
                        }
                        if (kind != REC_BLOCK) throw IOException("Bad record")
                        val fi = inp.readInt()
                        val offset = inp.readLong()
                        val len = inp.readInt()
                        if (fi < 0 || fi >= files.size || offset < 0 || offset % BLOCK != 0L || len <= 0 || len > BLOCK) {
                            throw IOException("Bad data block")
                        }
                        inp.readFully(buf, 0, len)
                        val t1 = System.nanoTime()
                        p.netNanos.addAndGet(t1 - t0)

                        val f = files[fi]
                        p.file = f.name
                        ensureOpen(f)
                        val ch = synchronized(f) { f.channel } ?: throw IOException("File already closed: ${f.name}")
                        val bb = ByteBuffer.wrap(buf, 0, len)
                        var pos = offset
                        while (bb.hasRemaining()) pos += ch.write(bb, pos)
                        p.diskNanos.addAndGet(System.nanoTime() - t1)
                        p.done.addAndGet(len.toLong())
                        synchronized(f) {
                            f.blocks.set((offset / BLOCK).toInt())
                            checkComplete(f)
                        }
                    }
                } catch (e: Throwable) {
                    fail(e)
                }
            }
        }
        for (w in workers) w.join()

        // Close open files but KEEP them: the next try continues from here.
        for (f in files) synchronized(f) { ResumeState.closeFile(f) }

        var problem: Throwable? = error.get()
        if (problem == null && files.any { !it.verified && !it.complete }) {
            problem = IOException("Some files did not arrive completely")
        }
        if (problem != null) throw IOException(problem.message ?: problem.javaClass.simpleName)

        // Check every new file against the sender's fingerprint by reading it back from storage.
        p.phase = "verify"
        val bad = ArrayList<String>()
        val buf = ByteArray(BLOCK)
        for (f in files) {
            if (f.verified) continue
            val uri = f.uri ?: continue
            val md = MessageDigest.getInstance("SHA-256")
            val stream = ctx.contentResolver.openInputStream(uri) ?: throw IOException("Cannot re-open ${f.name}")
            var length = 0L
            stream.use {
                while (true) {
                    val got = it.read(buf)
                    if (got < 0) break
                    md.update(buf, 0, got)
                    length += got
                    p.verifyDone.addAndGet(got.toLong())
                }
            }
            if (length == f.expected && md.digest().contentEquals(f.hash)) {
                val v = ContentValues()
                v.put(MediaStore.Downloads.IS_PENDING, 0)
                ctx.contentResolver.update(uri, v, null, null)
                f.verified = true
                p.verified.incrementAndGet()
            } else {
                // Throw this file away; on the next try only it is sent again.
                bad.add(f.name)
                synchronized(f) {
                    try {
                        ctx.contentResolver.delete(uri, null, null)
                    } catch (_: Exception) {
                    }
                    f.uri = null
                    f.blocks.clear()
                    f.complete = false
                    f.expected = -1
                }
            }
        }
        p.saved.clear()
        p.saved.addAll(state.savedList())

        val os = sockets[0].getOutputStream()
        if (bad.isNotEmpty()) {
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
        store.state = null
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
