package app.dropswift

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Last 20 transfers, kept on the phone only. */
object History {
    private const val PREFS = "history"
    private const val KEY = "items"
    private const val MAX = 20

    class Entry(
        val time: Long,
        val incoming: Boolean,
        val who: String,
        val files: Int,
        val bytes: Long,
        val mbps: Double,
        val ok: Boolean,
        val note: String,
        val uri: String
    )

    fun add(ctx: Context, e: Entry) {
        try {
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val old = JSONArray(prefs.getString(KEY, "[]"))
            val out = JSONArray()
            out.put(
                JSONObject()
                    .put("t", e.time).put("in", e.incoming).put("who", e.who).put("n", e.files)
                    .put("b", e.bytes).put("s", e.mbps).put("ok", e.ok).put("note", e.note).put("u", e.uri)
            )
            for (i in 0 until minOf(old.length(), MAX - 1)) out.put(old.get(i))
            prefs.edit().putString(KEY, out.toString()).apply()
        } catch (_: Exception) {
        }
    }

    fun list(ctx: Context): List<Entry> {
        val out = ArrayList<Entry>()
        try {
            val arr = JSONArray(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    Entry(
                        o.optLong("t"), o.optBoolean("in"), o.optString("who"), o.optInt("n"),
                        o.optLong("b"), o.optDouble("s"), o.optBoolean("ok"), o.optString("note"), o.optString("u")
                    )
                )
            }
        } catch (_: Exception) {
        }
        return out
    }

    fun clear(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }
}
