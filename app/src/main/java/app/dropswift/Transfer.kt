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
import java.util.concurrent.atomic.AtomicLong

/** Live numbers the screen reads while a transfer runs. */
class Progress {
    val done = AtomicLong(0)
    @Volatile var total: Long = 0
    @Volatile var file: String = ""
    @Volatile var files: Int = 0
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
        val buf = ByteArray(BLOCK)
        for (item in items) {
            p.file = item.name
            out.writeUTF(item.name)
            out.writeLong(item.size)
            val ins = ctx.contentResolver.openInputStream(item.uri)
                ?: throw IOException("Cannot open ${item.name}")
            ins.use { stream ->
                while (true) {
                    val n = stream.read(buf)
                    if (n < 0) break
                    if (n == 0) continue
                    out.writeInt(n)
                    out.write(buf, 0, n)
                    p.done.addAndGet(n.toLong())
                }
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
        val buf = ByteArray(BLOCK)
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
                    while (true) {
                        val n = inp.readInt()
                        if (n == 0) break
                        if (n < 0 || n > BLOCK) throw IOException("Bad data block")
                        inp.readFully(buf, 0, n)
                        stream.write(buf, 0, n)
                        p.done.addAndGet(n.toLong())
                    }
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
