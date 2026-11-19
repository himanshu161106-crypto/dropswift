package app.dropswift

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
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
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.Deflater
import java.util.zip.Inflater
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
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
    /** Bytes the other side already had (earlier try, duplicate, earlier batch): not sent again. */
    @Volatile var resumedBytes: Long = 0
    /** Bytes that actually went over the link (after compression and encryption). */
    val wireBytes = AtomicLong(0)
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
        /** SHA-256 checked and the file is visible (or it was already on the phone). */
        var verified = false
        /** Was already on the phone (duplicate or earlier batch); not received now. */
        var skipped = false
    }

    val files = names.map { RFile(it) }
    var duplicatesChecked = false

    fun matches(id: Long, n: List<String>, s: List<Long>) = id == transferId && n == names && s == sizes

    /** Rough count of bytes already on this phone. */
    fun haveBytes(block: Int): Long {
        var sum = 0L
        for ((i, f) in files.withIndex()) {
            synchronized(f) {
                sum += if (f.verified) sizes[i]
                else minOf(f.blocks.cardinality().toLong() * block, sizes[i])
            }
        }
        return sum
    }

    fun savedList(): List<Pair<String, Uri>> =
        files.filter { it.verified && !it.skipped && it.uri != null }.map { Pair(it.name, it.uri!!) }

    /** Deletes every file that was not finished and checked, so no half files are left behind. */
    fun discardUnfinished(ctx: Context) {
        for (f in files) {
            synchronized(f) {
                closeFile(f)
                val uri = f.uri
                if (!f.verified && uri != null) {
                    Storage.remove(ctx, uri)
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
    /** Everything received in this session ("name|size" -> file), so later batches skip it. */
    val received: MutableMap<String, Uri> = java.util.Collections.synchronizedMap(HashMap())
    @Volatile var senderName: String = ""
}

/**
 * Nearby protocol, version 5. The SENDER owns the link and listens on PORT.
 * Every receiver opens STREAMS connections and starts each with:
 *   int magic, UTF secret, long receiverId, int streamIndex, int streamCount, UTF deviceName
 * The sender writes on connection 0: int magic, long transferId, UTF senderName, int fileCount, long totalBytes,
 *   per file (UTF relativePath, long size).
 * The receiver answers on connection 0 with what it already has, per file one byte:
 *   0 = nothing, 1 = has it (complete, or a duplicate it keeps), 2 = partly: int length + bitmap of 1 MB blocks.
 * Then every connection carries records, in any order:
 *   1 = block     : int fileIndex, long offset, int rawLen, byte flags, int wireLen, wireLen bytes
 *                   flags: 1 = deflate-compressed, 2 = AES-256-GCM encrypted (compress first, then encrypt)
 *   2 = file end  : int fileIndex, long fileLength, 32 bytes SHA-256 of the whole file
 *   3 = no more records on this connection
 * The receiver checks every new file's SHA-256 and answers on connection 0 with one byte:
 *   1 = all files arrived and match, 2 = at least one did not match (only those are sent again next try).
 * Encryption key = SHA-256("DropSwift5:" + secret + ":" + transferId), nonce = fileIndex (4 bytes) + offset (8 bytes).
 */
object Transfer {
    const val PORT = 47800
    const val STREAMS = 4
    const val SOCKET_BUFFER = 2 shl 20
    private const val MAGIC = 0x44535735
    const val BLOCK = 1 shl 20
    private const val HASH = 32
    private const val WIRE_MAX = BLOCK + BLOCK / 8 + 256

    private const val REC_BLOCK = 1
    private const val REC_FILE_END = 2
    private const val REC_STREAM_END = 3

    private const val FLAG_DEFLATE = 1
    private const val FLAG_AES = 2

    private const val HAVE_NONE = 0
    private const val HAVE_ALL = 1
    private const val HAVE_SOME = 2

    private const val ACK_OK = 1
    private const val ACK_BAD = 2

    /** Answer to "this file is already on the phone". */
    const val DUP_SKIP = 0
    const val DUP_REPLACE = 1
    const val DUP_KEEP_BOTH = 2

    class Item(val uri: Uri, val name: String, val size: Long)

    class SendOptions(
        val transferId: Long,
        val senderName: String,
        val secret: String,
        val compress: Boolean,
        val encrypt: Boolean
    )

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

    /** Formats that are already packed; compressing them only wastes time. */
    private val PACKED = setOf(
        "jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "avif", "mp4", "mkv", "mov", "avi", "webm", "3gp",
        "mp3", "aac", "m4a", "ogg", "opus", "flac", "zip", "rar", "7z", "gz", "xz", "bz2", "apk", "apks", "xapk",
        "jar", "docx", "xlsx", "pptx", "odt", "epub"
    )

    fun worthCompressing(name: String) = name.substringAfterLast('.', "").lowercase(Locale.US) !in PACKED

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

    private fun key(secret: String, transferId: Long): SecretKeySpec {
        val k = MessageDigest.getInstance("SHA-256").digest("DropSwift5:$secret:$transferId".toByteArray(Charsets.UTF_8))
        return SecretKeySpec(k, "AES")
    }

    private fun nonce(file: Int, offset: Long): GCMParameterSpec {
        val n = ByteBuffer.allocate(12).putInt(file).putLong(offset).array()
        return GCMParameterSpec(128, n)
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

    /**
     * [onPlan] is told how many bytes will really be sent, once the receiver has said what it already has
     * (0 means the receiver already has everything, e.g. it is just checking for a new batch).
     */
    fun send(
        ctx: Context, sockets: List<Socket>, items: List<Item>, opt: SendOptions, p: Progress,
        onPlan: (Long) -> Unit = {}
    ) {
        val n = sockets.size
        p.streams = n
        p.total = items.sumOf { it.size }
        val outs = sockets.map { DataOutputStream(BufferedOutputStream(it.getOutputStream(), 64 * 1024)) }
        val control = DataInputStream(BufferedInputStream(sockets[0].getInputStream()))
        val first = outs[0]
        first.writeInt(MAGIC)
        first.writeLong(opt.transferId)
        first.writeUTF(opt.senderName.take(60))
        first.writeInt(items.size)
        first.writeLong(p.total)
        for (item in items) {
            first.writeUTF(item.name)
            first.writeLong(item.size)
        }
        first.flush()

        // What does the receiver already have?
        sockets[0].soTimeout = 90_000
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
        onPlan(maxOf(0L, p.total - skipped))

        val compressFile = BooleanArray(items.size) { opt.compress && worthCompressing(items[it].name) }
        val aesKey = if (opt.encrypt) key(opt.secret, opt.transferId) else null

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
        // Each one compresses / encrypts its own blocks, so that work also runs in parallel.
        val senders = outs.map { out ->
            thread(start = false, name = "ds-net") {
                val deflater = Deflater(1)
                val cbuf = ByteArray(WIRE_MAX)
                val ebuf = ByteArray(WIRE_MAX + 32)
                val cipher = if (aesKey != null) Cipher.getInstance("AES/GCM/NoPadding") else null
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
                            continue
                        }
                        var wire = r.buf!!
                        var wireLen = r.len
                        var flags = 0
                        if (compressFile[r.file]) {
                            deflater.reset()
                            deflater.setInput(r.buf, 0, r.len)
                            deflater.finish()
                            val c = deflater.deflate(cbuf)
                            // Only worth it when it saves at least 5%.
                            if (deflater.finished() && c < r.len - r.len / 20) {
                                wire = cbuf
                                wireLen = c
                                flags = flags or FLAG_DEFLATE
                            }
                        }
                        if (cipher != null) {
                            cipher.init(Cipher.ENCRYPT_MODE, aesKey, nonce(r.file, r.offset))
                            wireLen = cipher.doFinal(wire, 0, wireLen, ebuf, 0)
                            wire = ebuf
                            flags = flags or FLAG_AES
                        }
                        val t0 = System.nanoTime()
                        out.writeByte(REC_BLOCK)
                        out.writeInt(r.file)
                        out.writeLong(r.offset)
                        out.writeInt(r.len)
                        out.writeByte(flags)
                        out.writeInt(wireLen)
                        out.write(wire, 0, wireLen)
                        p.netNanos.addAndGet(System.nanoTime() - t0)
                        p.wireBytes.addAndGet(wireLen.toLong())
                        p.done.addAndGet(r.len.toLong())
                        free.put(r)
                    }
                } catch (_: InterruptedException) {
                } catch (e: Throwable) {
                    fail(e)
                } finally {
                    deflater.end()
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

    /**
     * Receives one batch. Returns how many files were newly received (0 = nothing new).
     * [askDuplicates] is called (on this thread) with names already on the phone; it returns a DUP_ answer.
     * [onNewData] is called once it is clear that real data is coming.
     */
    fun receive(
        ctx: Context, sockets: List<Socket>, p: Progress, store: ResumeStore, secret: String,
        askDuplicates: (List<String>) -> Int, onNewData: () -> Unit
    ): Int {
        p.streams = sockets.size
        p.phase = ""
        p.verifyDone.set(0)
        val ins = sockets.map { DataInputStream(BufferedInputStream(it.getInputStream(), 64 * 1024)) }
        val control = ins[0]
        if (control.readInt() != MAGIC) throw IOException("Other phone has a different DropSwift version. Update both")
        val transferId = control.readLong()
        store.senderName = control.readUTF()
        val count = control.readInt()
        if (count < 0 || count > 100_000) throw IOException("Bad file count")
        p.total = control.readLong()
        val names = ArrayList<String>(count)
        val sizes = ArrayList<Long>(count)
        for (i in 0 until count) {
            names.add(Storage.cleanPath(control.readUTF()))
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

        // Files already received earlier in this session (an earlier batch): skip quietly.
        // Files already on the phone from before: ask once what to do.
        if (!state.duplicatesChecked) {
            state.duplicatesChecked = true
            val dups = ArrayList<Pair<Int, Uri>>()
            for ((i, f) in files.withIndex()) {
                if (f.verified || f.uri != null) continue
                val earlier = store.received["${names[i]}|${sizes[i]}"]
                if (earlier != null) {
                    f.uri = earlier
                    f.verified = true
                    f.skipped = true
                    continue
                }
                Storage.findExisting(ctx, names[i], sizes[i])?.let { dups.add(Pair(i, it)) }
            }
            if (dups.isNotEmpty()) {
                when (askDuplicates(dups.map { names[it.first] })) {
                    DUP_SKIP -> for ((i, uri) in dups) {
                        files[i].uri = uri
                        files[i].verified = true
                        files[i].skipped = true
                    }
                    DUP_REPLACE -> for ((_, uri) in dups) Storage.remove(ctx, uri)
                    else -> {}
                }
            }
        }

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
        p.verified.set(0)
        if (files.any { !it.verified }) onNewData()

        val aesKey = key(secret, transferId)
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
                        uri = Storage.create(ctx, f.name)
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
            if (f.blocks.nextClearBit(0) >= needed) {
                f.complete = true
                ResumeState.closeFile(f)
                p.files.incrementAndGet()
            }
        }

        val workers = ins.map { inp ->
            thread(name = "ds-net") {
                try {
                    val buf = ByteArray(BLOCK)
                    val wbuf = ByteArray(WIRE_MAX + 32)
                    val dbuf = ByteArray(WIRE_MAX + 32)
                    val inflater = Inflater()
                    var cipher: Cipher? = null
                    try {
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
                            val flags = inp.readUnsignedByte()
                            val wireLen = inp.readInt()
                            if (fi < 0 || fi >= files.size || offset < 0 || offset % BLOCK != 0L || len <= 0 ||
                                len > BLOCK || wireLen <= 0 || wireLen > WIRE_MAX + 32
                            ) {
                                throw IOException("Bad data block")
                            }
                            inp.readFully(wbuf, 0, wireLen)
                            val t1 = System.nanoTime()
                            p.netNanos.addAndGet(t1 - t0)
                            p.wireBytes.addAndGet(wireLen.toLong())

                            var data = wbuf
                            var dataLen = wireLen
                            if (flags and FLAG_AES != 0) {
                                val c = cipher ?: Cipher.getInstance("AES/GCM/NoPadding").also { cipher = it }
                                c.init(Cipher.DECRYPT_MODE, aesKey, nonce(fi, offset))
                                dataLen = try {
                                    c.doFinal(data, 0, dataLen, dbuf, 0)
                                } catch (e: Exception) {
                                    throw IOException("integrity failed: encrypted block did not open")
                                }
                                data = dbuf
                            }
                            if (flags and FLAG_DEFLATE != 0) {
                                inflater.reset()
                                inflater.setInput(data, 0, dataLen)
                                val got = inflater.inflate(buf, 0, len)
                                if (got != len || !inflater.finished()) throw IOException("Bad compressed block")
                            } else {
                                if (dataLen != len) throw IOException("Bad data block")
                                System.arraycopy(data, 0, buf, 0, len)
                            }

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
                    } finally {
                        inflater.end()
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
        var newFiles = 0
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
                Storage.finish(ctx, uri)
                f.verified = true
                newFiles++
                p.verified.incrementAndGet()
            } else {
                // Throw this file away; on the next try only it is sent again.
                bad.add(f.name)
                synchronized(f) {
                    Storage.remove(ctx, uri)
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
        // Remember this batch so a later batch from the same sender skips it.
        for ((i, f) in files.withIndex()) {
            val u = f.uri
            if (f.verified && u != null) store.received["${names[i]}|${sizes[i]}"] = u
        }
        // Keep the link up until the sender has read the answer and hung up (at most 10 s).
        try {
            sockets[0].soTimeout = 10_000
            while (control.read() >= 0) {
            }
        } catch (_: Exception) {
        }
        store.state = null
        return newFiles
    }

    /** Kept for internet mode and browser upload. */
    fun cleanName(raw: String): String = Storage.cleanPath(raw)

    fun newDownload(ctx: Context, name: String): Uri = Storage.create(ctx, name)
}
