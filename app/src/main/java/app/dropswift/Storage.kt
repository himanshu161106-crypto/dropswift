package app.dropswift

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import java.io.IOException
import java.util.Locale

/**
 * Where received files go. Default: Download/DropSwift (MediaStore).
 * Or a folder the user picked (Storage Access Framework).
 * Names may contain folders ("Trip/Day 1/a.jpg") for folder transfers.
 */
object Storage {
    private const val PREFS = "storage"
    private const val KEY_TREE = "tree"

    fun tree(ctx: Context): Uri? {
        val s = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TREE, null) ?: return null
        val uri = Uri.parse(s)
        // The permission can be taken back by the user; fall back to Downloads then.
        val ok = ctx.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }
        return if (ok) uri else null
    }

    fun setTree(ctx: Context, uri: Uri?) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (uri == null) {
            prefs.edit().remove(KEY_TREE).apply()
            return
        }
        try {
            ctx.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: Exception) {
        }
        prefs.edit().putString(KEY_TREE, uri.toString()).apply()
    }

    fun label(ctx: Context): String {
        val t = tree(ctx) ?: return "Download/DropSwift"
        return try {
            val id = DocumentsContract.getTreeDocumentId(t)
            "Chuna hua folder: " + id.substringAfter(':').ifEmpty { id }
        } catch (_: Exception) {
            "Chuna hua folder"
        }
    }

    /** Makes a relative path safe: no "..", no empty parts, at most 8 folders deep. */
    fun cleanPath(raw: String): String {
        val parts = raw.replace('\\', '/').split('/')
            .map { it.replace("\u0000", "").trim() }
            .filter { it.isNotEmpty() && it != "." && it != ".." }
            .map { it.take(120) }
        if (parts.isEmpty()) return "file"
        val dirs = parts.dropLast(1).takeLast(8)
        return (dirs + parts.last()).joinToString("/")
    }

    private fun split(rel: String): Pair<String, String> {
        val i = rel.lastIndexOf('/')
        return if (i < 0) Pair("", rel) else Pair(rel.substring(0, i), rel.substring(i + 1))
    }

    private fun mimeOf(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.US)
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    /** Creates an empty file for writing. Hidden (pending) in Downloads until [finish]. */
    fun create(ctx: Context, relName: String): Uri {
        val rel = cleanPath(relName)
        val (dir, name) = split(rel)
        val t = tree(ctx)
        if (t != null) {
            val parent = dirDoc(ctx, t, dir, true) ?: throw IOException("Cannot create folder $dir")
            return DocumentsContract.createDocument(ctx.contentResolver, parent, mimeOf(name), name)
                ?: throw IOException("Cannot create $name in chosen folder")
        }
        val v = ContentValues()
        v.put(MediaStore.Downloads.DISPLAY_NAME, name)
        v.put(
            MediaStore.Downloads.RELATIVE_PATH,
            Environment.DIRECTORY_DOWNLOADS + "/DropSwift" + (if (dir.isEmpty()) "" else "/$dir")
        )
        v.put(MediaStore.Downloads.IS_PENDING, 1)
        return ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)
            ?: throw IOException("Cannot create $name in Downloads")
    }

    /** Makes a finished file visible to other apps. */
    fun finish(ctx: Context, uri: Uri) {
        if (uri.authority == MediaStore.AUTHORITY) {
            val v = ContentValues()
            v.put(MediaStore.Downloads.IS_PENDING, 0)
            ctx.contentResolver.update(uri, v, null, null)
        }
    }

    fun remove(ctx: Context, uri: Uri) {
        try {
            if (uri.authority == MediaStore.AUTHORITY) {
                ctx.contentResolver.delete(uri, null, null)
            } else {
                DocumentsContract.deleteDocument(ctx.contentResolver, uri)
            }
        } catch (_: Exception) {
        }
    }

    /** A finished file with the same name, folder and size, if one is already saved. */
    fun findExisting(ctx: Context, relName: String, size: Long): Uri? {
        val rel = cleanPath(relName)
        val (dir, name) = split(rel)
        return try {
            val t = tree(ctx)
            if (t != null) {
                val parent = dirDoc(ctx, t, dir, false) ?: return null
                childOf(ctx, t, parent, name, size, false)
            } else {
                val path = Environment.DIRECTORY_DOWNLOADS + "/DropSwift" + (if (dir.isEmpty()) "" else "/$dir") + "/"
                ctx.contentResolver.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Downloads._ID),
                    "${MediaStore.Downloads.RELATIVE_PATH}=? AND ${MediaStore.Downloads.DISPLAY_NAME}=? AND ${MediaStore.Downloads.SIZE}=?",
                    arrayOf(path, name, size.toString()),
                    null
                )?.use { c ->
                    if (c.moveToFirst()) Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0).toString())
                    else null
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Finds (and if asked, creates) a sub-folder under the chosen tree. Returns its document uri. */
    private fun dirDoc(ctx: Context, tree: Uri, dir: String, make: Boolean): Uri? {
        var cur = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        if (dir.isEmpty()) return cur
        for (part in dir.split('/')) {
            val found = childOf(ctx, tree, cur, part, -1, true)
            cur = found ?: if (make) {
                DocumentsContract.createDocument(ctx.contentResolver, cur, DocumentsContract.Document.MIME_TYPE_DIR, part)
                    ?: return null
            } else {
                return null
            }
        }
        return cur
    }

    private fun childOf(ctx: Context, tree: Uri, parent: Uri, name: String, size: Long, wantDir: Boolean): Uri? {
        val parentId = DocumentsContract.getDocumentId(parent)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        ctx.contentResolver.query(
            children,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE
            ),
            null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                if (c.getString(1) != name) continue
                val isDir = c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR
                if (isDir != wantDir) continue
                if (!wantDir && size >= 0 && c.getLong(3) != size) continue
                return DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))
            }
        }
        return null
    }
}
