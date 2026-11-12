package app.dropswift

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import java.util.concurrent.atomic.AtomicLong

/** Live numbers the screen reads while a transfer runs. */
class Progress {
    val done = AtomicLong(0)
    @Volatile var total: Long = 0
    @Volatile var file: String = ""
    @Volatile var files: Int = 0
    /** Time spent waiting on the Wi-Fi link and on phone storage, to see which one is the limit. */
    val netNanos = AtomicLong(0)
    val diskNanos = AtomicLong(0)
}

/**
 * Wire format (one TCP connection, sender -> receiver):
 *   int magic, int fileCount, long totalBytes
 *   per file: UTF name, long sizeHint, then blocks [int len][len bytes] ... ending with [int 0]
 * Receiver answers with a single byte 1 when everything is saved.
 */
object Transfer {
    const val PORT = 47800
    private const val MAGIC = 0x44535731
    private const val BLOCK = 1 shl 20
    const val SOCKET_BUFFER = 4 shl 20

    class Item(val uri: Uri, val name: String, val size: Long)

    private class Chunk(val buf: ByteArray, var len: Int)

    private const val QUEUE = 8
    private val END = Chunk(ByteArray(0), 0)

    private fun newPool(): ArrayBlockingQueue<Chunk> {
        val pool = ArrayBlockingQueue<Chunk>(QUEUE + 2)
        for (i in 0 until QUEUE + 2) pool.put(Chunk(ByteArray(BLOCK), 0))
        return pool
    }

    /** Fills the buffer completely unless the file ends first. Returns bytes read (0 at end). */
    private fun readFull(stream: java.io.InputStream, buf: ByteArray): Int {
        var got = 0
        while (got < buf.size) {
            val n = stream.read(buf, got, buf.size - got)
            if (n < 0) break
            got += n
        }
        return got
    }

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

    fun send(ctx: Context, socket: Socket, items: List<Item>, p: Progress) {
        val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), 64 * 1024))
        out.writeInt(MAGIC)
        out.writeInt(items.size)
        out.writeLong(p.total)
        val free = newPool()
        for (item in items) {
            p.file = item.name
            out.writeUTF(item.name)
            out.writeLong(item.size)
            val ins = ctx.contentResolver.openInputStream(item.uri)
                ?: throw IOException("Cannot open ${item.name}")
            ins.use { stream ->
                // One thread reads the file while this thread pushes to Wi-Fi, so neither waits for the other.
                val queue = ArrayBlockingQueue<Chunk>(QUEUE)
                val error = AtomicReference<Throwable?>(null)
                val reader = thread(name = "ds-read") {
                    try {
                        while (true) {
                            val c = free.take()
                            val t0 = System.nanoTime()
                            val n = try {
                                readFull(stream, c.buf)
                            } catch (e: Throwable) {
                                error.set(e)
                                0
                            }
                            p.diskNanos.addAndGet(System.nanoTime() - t0)
                            if (n <= 0) {
                                free.put(c)
                                queue.put(END)
                                break
                            }
                            c.len = n
                            queue.put(c)
                        }
                    } catch (_: InterruptedException) {
                    }
                }
                try {
                    while (true) {
                        val c = queue.take()
                        if (c === END) break
                        val t0 = System.nanoTime()
                        out.writeInt(c.len)
                        out.write(c.buf, 0, c.len)
                        p.netNanos.addAndGet(System.nanoTime() - t0)
                        p.done.addAndGet(c.len.toLong())
                        free.put(c)
                    }
                } finally {
                    if (reader.isAlive) {
                        reader.interrupt()
                        reader.join(3000)
                    }
                }
                error.get()?.let { throw IOException("Reading ${item.name}: ${it.message}") }
            }
            out.writeInt(0)
            p.files++
        }
        out.flush()
        socket.soTimeout = 120_000
        if (socket.getInputStream().read() != 1) throw IOException("Receiver did not confirm")
    }

    fun receive(ctx: Context, socket: Socket, p: Progress) {
        val inp = DataInputStream(BufferedInputStream(socket.getInputStream(), 64 * 1024))
        if (inp.readInt() != MAGIC) throw IOException("Not a DropSwift sender")
        val count = inp.readInt()
        if (count < 0 || count > 100_000) throw IOException("Bad file count")
        p.total = inp.readLong()
        val free = newPool()
        for (i in 0 until count) {
            val name = cleanName(inp.readUTF())
            inp.readLong()
            p.file = name
            val uri = newDownload(ctx, name)
            var ok = false
            try {
                val os = ctx.contentResolver.openOutputStream(uri)
                    ?: throw IOException("Cannot create $name")
                os.use { stream ->
                    // One thread saves to storage while this thread keeps pulling from Wi-Fi.
                    val queue = ArrayBlockingQueue<Chunk>(QUEUE)
                    val error = AtomicReference<Throwable?>(null)
                    val writer = thread(name = "ds-write") {
                        try {
                            while (true) {
                                val c = queue.take()
                                if (c === END) break
                                if (error.get() == null) {
                                    try {
                                        val t0 = System.nanoTime()
                                        stream.write(c.buf, 0, c.len)
                                        p.diskNanos.addAndGet(System.nanoTime() - t0)
                                    } catch (e: Throwable) {
                                        error.set(e)
                                    }
                                }
                                free.put(c)
                            }
                        } catch (_: InterruptedException) {
                        }
                    }
                    try {
                        while (true) {
                            error.get()?.let { throw IOException("Saving $name: ${it.message}") }
                            val c = free.take()
                            val t0 = System.nanoTime()
                            val n = inp.readInt()
                            if (n == 0) {
                                free.put(c)
                                break
                            }
                            if (n < 0 || n > BLOCK) throw IOException("Bad data block")
                            inp.readFully(c.buf, 0, n)
                            p.netNanos.addAndGet(System.nanoTime() - t0)
                            c.len = n
                            queue.put(c)
                            p.done.addAndGet(n.toLong())
                        }
                        queue.put(END)
                        writer.join()
                    } finally {
                        if (writer.isAlive) {
                            writer.interrupt()
                            writer.join(3000)
                        }
                    }
                    error.get()?.let { throw IOException("Saving $name: ${it.message}") }
                }
                ok = true
            } finally {
                if (ok) {
                    val v = ContentValues()
                    v.put(MediaStore.Downloads.IS_PENDING, 0)
                    ctx.contentResolver.update(uri, v, null, null)
                } else {
                    try {
                        ctx.contentResolver.delete(uri, null, null)
                    } catch (_: Exception) {
                    }
                }
            }
            p.files++
        }
        val os = socket.getOutputStream()
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
