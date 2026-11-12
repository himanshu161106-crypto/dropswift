package app.dropswift

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.google.zxing.BarcodeFormat
import com.google.zxing.integration.android.IntentIntegrator
import com.journeyapps.barcodescanner.BarcodeEncoder
import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.PrintWriter
import java.io.StringWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())

    private lateinit var statusView: TextView   // the bot's speech bubble
    private lateinit var botView: ImageView
    private lateinit var tipView: TextView
    private lateinit var detailView: TextView
    private lateinit var speedView: TextView
    private lateinit var linkView: TextView
    private lateinit var logView: TextView
    private lateinit var logToggle: TextView
    private lateinit var receivedBox: LinearLayout
    private lateinit var searchInput: EditText
    private lateinit var searchBox: LinearLayout
    private lateinit var btnSendSelected: Button
    private lateinit var qrView: ImageView
    private lateinit var bar: ProgressBar
    private lateinit var btnSend: Button
    private lateinit var btnReceive: Button
    private lateinit var btnCancel: Button
    private val logLines = ArrayList<String>()

    private var p2p: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null

    private var afterPermission: (() -> Unit)? = null
    private var pickedUris: List<Uri> = emptyList()
    private val selected = LinkedHashMap<Uri, String>()
    private var shareCode: String? = null

    @Volatile private var generation = 0
    @Volatile private var active = false
    @Volatile private var transferring = false
    private val openThings = ArrayList<Closeable>()
    private var wifiLock: WifiManager.WifiLock? = null

    private var progress = Progress()
    private var startedAt = 0L
    private var lastTickAt = 0L
    private var lastTickBytes = 0L

    private class Found(val uri: Uri, val name: String, val size: Long, val folder: String)

    // ---------------------------------------------------------------- screen

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        installCrashCatcher()
        buildScreen()
        p2p = getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        channel = p2p?.initialize(this, mainLooper, null)
        if (!showLastCrash()) {
            say("Namaste! Main Swifty hoon. File bhejni hai ya leni hai? Neeche button dabao. Ya file ka naam likh do, main dhoond ke de dunga.")
        }
    }

    /** If the app dies, keep the reason in a small file and show it on the next start. */
    private fun installCrashCatcher() {
        val file = File(filesDir, "last_crash.txt")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                file.writeText("CRASH in ${t.name}\n" + sw.toString().take(6000))
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(t, e)
        }
    }

    private fun showLastCrash(): Boolean {
        try {
            val file = File(filesDir, "last_crash.txt")
            if (file.exists()) {
                val textOfCrash = file.readText()
                file.delete()
                say(appFault("pichhli baar app achanak band ho gaya tha. Yeh app ki galti hai, aapke phone ki nahi. Neeche ka text app banane wale ko bhej do."))
                log(textOfCrash)
                showLog(true)
                return true
            }
        } catch (_: Throwable) {
        }
        return false
    }

    private fun log(line: String) {
        ui.post {
            logLines.add(line)
            while (logLines.size > 60) logLines.removeAt(0)
            logView.text = logLines.joinToString("\n")
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radiusDp: Int): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = dp(radiusDp).toFloat()
        return g
    }

    private fun text(size: Float, color: Int, bold: Boolean = false): TextView {
        val t = TextView(this)
        t.textSize = size
        t.setTextColor(color)
        t.gravity = Gravity.CENTER
        if (bold) t.typeface = Typeface.DEFAULT_BOLD
        return t
    }

    private fun button(label: String, bg: Int, fg: Int): Button {
        val b = Button(this)
        b.text = label
        b.isAllCaps = false
        b.textSize = 18f
        b.setTextColor(fg)
        b.background = rounded(bg, 14)
        return b
    }

    private fun buildScreen() {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.gravity = Gravity.CENTER_HORIZONTAL
        root.setPadding(dp(20), dp(36), dp(20), dp(24))
        root.setBackgroundColor(Color.parseColor("#0B0F19"))

        val wide = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )

        fun gap(h: Int): View {
            val v = View(this)
            v.layoutParams = LinearLayout.LayoutParams(1, dp(h))
            return v
        }

        val title = text(28f, Color.parseColor("#00E68A"), true)
        title.text = "DropSwift"
        root.addView(title, wide)
        root.addView(gap(14))

        // Swifty the bot + speech bubble
        val botRow = LinearLayout(this)
        botRow.orientation = LinearLayout.HORIZONTAL
        botRow.gravity = Gravity.CENTER_VERTICAL
        botView = ImageView(this)
        botView.setImageResource(R.drawable.bot)
        botRow.addView(botView, LinearLayout.LayoutParams(dp(64), dp(64)))
        statusView = TextView(this)
        statusView.textSize = 15f
        statusView.setTextColor(Color.WHITE)
        statusView.background = rounded(Color.parseColor("#1B2740"), 16)
        statusView.setPadding(dp(14), dp(12), dp(14), dp(12))
        val bubbleParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        bubbleParams.leftMargin = dp(10)
        botRow.addView(statusView, bubbleParams)
        root.addView(botRow, wide)
        try {
            val bob = ObjectAnimator.ofFloat(botView, "translationY", 0f, -dp(5).toFloat())
            bob.duration = 900
            bob.repeatMode = ValueAnimator.REVERSE
            bob.repeatCount = ValueAnimator.INFINITE
            bob.start()
        } catch (_: Throwable) {
        }

        tipView = text(13f, Color.parseColor("#FFCA69"))
        tipView.visibility = View.GONE
        tipView.setPadding(0, dp(8), 0, 0)
        root.addView(tipView, wide)
        root.addView(gap(18))

        btnSend = button("Send files", Color.parseColor("#00E68A"), Color.parseColor("#04130C"))
        btnSend.setOnClickListener { onSendClicked() }
        root.addView(btnSend, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(58)))
        root.addView(gap(12))

        btnReceive = button("Receive files", Color.parseColor("#263650"), Color.WHITE)
        btnReceive.setOnClickListener { onReceiveClicked() }
        root.addView(btnReceive, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(58)))
        root.addView(gap(14))

        // Find a file by name
        val searchRow = LinearLayout(this)
        searchRow.orientation = LinearLayout.HORIZONTAL
        searchRow.gravity = Gravity.CENTER_VERTICAL
        searchInput = EditText(this)
        searchInput.hint = "File ka naam likho..."
        searchInput.setHintTextColor(Color.parseColor("#71829B"))
        searchInput.setTextColor(Color.WHITE)
        searchInput.textSize = 15f
        searchInput.setSingleLine(true)
        searchInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        searchInput.imeOptions = EditorInfo.IME_ACTION_SEARCH
        searchInput.background = rounded(Color.parseColor("#151C2E"), 12)
        searchInput.setPadding(dp(14), dp(12), dp(14), dp(12))
        searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                onSearchClicked()
                true
            } else false
        }
        searchRow.addView(searchInput, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val btnSearch = button("Dhoondo", Color.parseColor("#06B6D4"), Color.parseColor("#04130C"))
        btnSearch.textSize = 15f
        btnSearch.setOnClickListener { onSearchClicked() }
        val searchBtnParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(48))
        searchBtnParams.leftMargin = dp(8)
        searchRow.addView(btnSearch, searchBtnParams)
        root.addView(searchRow, wide)

        searchBox = LinearLayout(this)
        searchBox.orientation = LinearLayout.VERTICAL
        root.addView(searchBox, wide)

        btnSendSelected = button("Send", Color.parseColor("#00E68A"), Color.parseColor("#04130C"))
        btnSendSelected.visibility = View.GONE
        btnSendSelected.setOnClickListener {
            if (!active && selected.isNotEmpty()) {
                pickedUris = ArrayList(selected.keys)
                chooseSendWay()
            }
        }
        val sendSelParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52))
        sendSelParams.topMargin = dp(8)
        root.addView(btnSendSelected, sendSelParams)
        root.addView(gap(14))

        linkView = text(13f, Color.parseColor("#FFCA69"))
        root.addView(linkView, wide)
        root.addView(gap(10))

        qrView = ImageView(this)
        qrView.visibility = View.GONE
        qrView.setBackgroundColor(Color.WHITE)
        qrView.setPadding(dp(10), dp(10), dp(10), dp(10))
        root.addView(qrView, LinearLayout.LayoutParams(dp(260), dp(260)))

        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        bar.max = 1000
        bar.visibility = View.GONE
        root.addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(14)))
        root.addView(gap(8))

        speedView = text(34f, Color.parseColor("#00E68A"), true)
        speedView.setOnClickListener { shareTheCode() }
        root.addView(speedView, wide)

        detailView = text(14f, Color.parseColor("#9AA9BD"))
        root.addView(detailView, wide)
        root.addView(gap(18))

        btnCancel = button("Cancel", Color.parseColor("#48232D"), Color.parseColor("#FFDCE0"))
        btnCancel.visibility = View.GONE
        btnCancel.setOnClickListener { stopEverything("Theek hai, rok diya.") }
        root.addView(btnCancel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(50)))

        receivedBox = LinearLayout(this)
        receivedBox.orientation = LinearLayout.VERTICAL
        root.addView(receivedBox, wide)

        root.addView(gap(18))
        logToggle = text(12f, Color.parseColor("#71829B"))
        logToggle.text = "Show details"
        logToggle.setPadding(dp(8), dp(8), dp(8), dp(8))
        logToggle.setOnClickListener { showLog(logView.visibility != View.VISIBLE) }
        root.addView(logToggle, wide)
        logView = TextView(this)
        logView.visibility = View.GONE
        logView.textSize = 11f
        logView.setTextColor(Color.parseColor("#71829B"))
        logView.setTextIsSelectable(true)
        root.addView(logView, wide)
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) {
            "?"
        }
        log("DropSwift build $version, Android ${Build.VERSION.RELEASE}, ${Build.MANUFACTURER} ${Build.MODEL}")

        val scroll = ScrollView(this)
        scroll.setBackgroundColor(Color.parseColor("#0B0F19"))
        scroll.isFillViewport = true
        scroll.addView(root)
        setContentView(scroll)
    }

    // ------------------------------------------------------------- the bot

    /** Swifty says something in the bubble. */
    private fun say(s: String) {
        ui.post { statusView.text = s }
        log("> $s")
    }

    private fun phoneFault(s: String) = "Phone ki dikkat: $s"
    private fun appFault(s: String) = "App ki dikkat: $s"
    private fun netFault(s: String) = "Network ki dikkat: $s"

    /** Turns a technical failure into plain words and says whose problem it is. */
    private fun diagnose(e: Throwable): String {
        val raw = (e.message ?: e.javaClass.simpleName)
        val m = raw.lowercase(Locale.US)
        return when {
            m.contains("cancel") -> "Theek hai, rok diya."
            m.contains("enospc") || m.contains("no space") ->
                phoneFault("phone ka storage bhar gaya. Thodi jagah khaali karke dobara lo.")
            m.contains("different dropswift version") ->
                appFault("dono phone pe alag-alag version hai. Dono pe naya DropSwift install karo.")
            m.contains("could not make a direct link") ->
                netFault("dono ke internet seedha link nahi banne de rahe. Aksar mobile data pe hota hai. Ek phone ko Wi-Fi pe laake dobara try karo.")
            m.contains("no internet") || m.contains("helper") || m.contains("unable to resolve host") ->
                phoneFault("internet nahi chal raha. Data ya Wi-Fi check karke dobara try karo.")
            m.contains("did not join in time") ->
                "Doosra phone juda hi nahi. Code sahi daala tha? Dobara try karo."
            m.contains("not reachable") ->
                netFault("doosre phone tak pahunch nahi paya. Dono phone paas laao aur dobara try karo.")
            e is EOFException || m.contains("reset") || m.contains("broken pipe") || m.contains("timed out") ||
                m.contains("abort") || m.contains("socket closed") || m.contains("link closed") ||
                m.contains("link went silent") || m.contains("did not arrive completely") ||
                m.contains("did not confirm") || m.contains("eof") ->
                netFault("beech mein link toot gaya. Phone door ho gaye, ya doosre phone pe app band ho gaya. Paas aake dobara bhejo.")
            e is SecurityException || m.contains("permission") || m.contains("cannot open") ->
                phoneFault("is file ko padhne ki ijazat nahi mili. File dobara chun ke bhejo.")
            m.contains("cannot create") ->
                phoneFault("file save nahi ho payi. Storage mein jagah aur permission check karo.")
            else -> appFault("kuch gadbad hui ($raw). Yeh app ki galti lag rahi hai. 'Show details' ka text app banane wale ko bhej do.")
        }
    }

    private fun showTip(s: String?) {
        if (s == null) {
            tipView.visibility = View.GONE
        } else {
            tipView.text = s
            tipView.visibility = View.VISIBLE
            log("tip: $s")
        }
    }

    /** Things about the phone itself that can hurt a transfer. */
    private fun phoneCheck(receiving: Boolean): String? {
        val tips = ArrayList<String>()
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (pm.isPowerSaveMode) tips.add("Battery saver on hai, isse speed kam ho sakti hai.")
        } catch (_: Throwable) {
        }
        if (receiving) {
            try {
                val free = StatFs(Environment.getExternalStorageDirectory().path).availableBytes
                if (free < 2L * 1024 * 1024 * 1024) tips.add("Phone mein sirf ${size(free)} jagah bachi hai.")
            } catch (_: Throwable) {
            }
        }
        return if (tips.isEmpty()) null else "Swifty ki salah: " + tips.joinToString(" ")
    }

    private fun showLog(show: Boolean) {
        logView.visibility = if (show) View.VISIBLE else View.GONE
        logToggle.text = if (show) "Hide details" else "Show details"
    }

    /** List of files that just arrived; tap one to open it. */
    private fun showReceived(items: List<Pair<String, Uri>>) {
        receivedBox.removeAllViews()
        if (items.isEmpty()) return
        val head = text(14f, Color.WHITE, true)
        head.text = "Aayi hui files (dabao toh khulegi)"
        head.setPadding(0, dp(14), 0, dp(6))
        receivedBox.addView(head)
        for ((name, uri) in items.take(200)) {
            val row = TextView(this)
            row.text = name
            row.textSize = 14f
            row.setTextColor(Color.parseColor("#C8D7E9"))
            row.background = rounded(Color.parseColor("#151C2E"), 10)
            row.setPadding(dp(12), dp(12), dp(12), dp(12))
            row.setOnClickListener { openFile(uri) }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(4)
            receivedBox.addView(row, lp)
        }
        if (items.size > 200) {
            val more = text(12f, Color.parseColor("#9AA9BD"))
            more.text = "aur ${items.size - 200} files Download/DropSwift mein hain"
            receivedBox.addView(more)
        }
    }

    private fun openFile(uri: Uri) {
        try {
            val i = Intent(Intent.ACTION_VIEW)
            i.setDataAndType(uri, contentResolver.getType(uri) ?: "*/*")
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(i)
        } catch (e: Exception) {
            say(phoneFault("is file ko kholne wala koi app phone mein nahi hai."))
        }
    }

    private fun setBusy(busy: Boolean) {
        btnSend.isEnabled = !busy
        btnReceive.isEnabled = !busy
        btnSend.alpha = if (busy) 0.4f else 1f
        btnReceive.alpha = if (busy) 0.4f else 1f
        btnCancel.visibility = if (busy) View.VISIBLE else View.GONE
    }

    // --------------------------------------------------- find a file by name

    private fun onSearchClicked() {
        if (active) return
        val q = searchInput.text.toString().trim()
        try {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(searchInput.windowToken, 0)
        } catch (_: Throwable) {
        }
        if (q.length < 2) {
            say("File ke naam ke kam se kam 2 akshar likho, phir Dhoondo dabao.")
            return
        }
        if (Build.VERSION.SDK_INT >= 30) {
            if (!Environment.isExternalStorageManager()) {
                say("File dhoondne ke liye mujhe phone ki files dekhne ki ijazat chahiye. Agli screen pe 'Allow access to all files' on karo, wapas aao, aur Dhoondo dobara dabao.")
                try {
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                    )
                } catch (e: Exception) {
                    try {
                        startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                    } catch (_: Exception) {
                        say(phoneFault("yeh phone files dekhne ki ijazat wali screen nahi khol raha. 'Send files' button se file chun lo."))
                    }
                }
                return
            }
        } else if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            afterPermission = { runSearch(q) }
            requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), REQ_PERMISSION)
            return
        }
        runSearch(q)
    }

    private fun runSearch(q: String) {
        say("\"$q\" dhoond raha hoon...")
        thread(name = "ds-search") {
            val found = ArrayList<Found>()
            var error: Throwable? = null
            try {
                val base = MediaStore.Files.getContentUri("external")
                contentResolver.query(
                    base,
                    arrayOf("_id", "_display_name", "_size", "relative_path"),
                    "_display_name LIKE ? AND _size > 0",
                    arrayOf("%$q%"),
                    "date_modified DESC"
                )?.use { c ->
                    while (c.moveToNext() && found.size < 60) {
                        val name = c.getString(1) ?: continue
                        found.add(
                            Found(
                                ContentUris.withAppendedId(base, c.getLong(0)),
                                name, c.getLong(2), c.getString(3) ?: ""
                            )
                        )
                    }
                }
            } catch (e: Throwable) {
                error = e
            }
            ui.post { showSearchResults(q, found, error) }
        }
    }

    private fun showSearchResults(q: String, found: List<Found>, error: Throwable?) {
        searchBox.removeAllViews()
        selected.clear()
        refreshSendSelected()
        if (error != null) {
            say(appFault("file dhoondte waqt gadbad hui (${error.message}). 'Send files' button se file chun lo."))
            return
        }
        if (found.isEmpty()) {
            say("\"$q\" naam ki koi file nahi mili. Naam ka chhota hissa likh ke dekho.")
            return
        }
        say("${found.size} file mili. Jo bhejni hai uspe dabao, phir neeche hara button dabao.")
        for (f in found) {
            val row = TextView(this)
            row.text = "${f.name}\n${size(f.size)}  -  ${f.folder}"
            row.textSize = 13f
            row.setTextColor(Color.parseColor("#C8D7E9"))
            row.background = rounded(Color.parseColor("#151C2E"), 10)
            row.setPadding(dp(12), dp(10), dp(12), dp(10))
            row.setOnClickListener {
                if (selected.containsKey(f.uri)) {
                    selected.remove(f.uri)
                    row.background = rounded(Color.parseColor("#151C2E"), 10)
                } else {
                    selected[f.uri] = f.name
                    row.background = rounded(Color.parseColor("#0F5C40"), 10)
                }
                refreshSendSelected()
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(4)
            searchBox.addView(row, lp)
        }
    }

    private fun refreshSendSelected() {
        if (selected.isEmpty()) {
            btnSendSelected.visibility = View.GONE
        } else {
            btnSendSelected.text = "Chuni hui ${selected.size} file bhejo"
            btnSendSelected.visibility = View.VISIBLE
        }
    }

    // ------------------------------------------------------- start of a flow

    private fun onSendClicked() {
        if (active) return
        AlertDialog.Builder(this)
            .setTitle("Kya bhejna hai?")
            .setItems(arrayOf("Photos aur videos", "Koi bhi file")) { _, which ->
                if (which == 0) pickMedia() else pickFiles()
            }
            .show()
    }

    private fun onReceiveClicked() {
        if (active) return
        AlertDialog.Builder(this)
            .setTitle("Bhejne wala phone kahan hai?")
            .setItems(
                arrayOf(
                    "Paas mein - sabse tez (5 GHz)",
                    "Thoda door, 20 m tak - dheema (2.4 GHz)",
                    "Bahut door - internet se"
                )
            ) { _, which ->
                when (which) {
                    0 -> ensureNearby { startReceiving(WifiP2pConfig.GROUP_OWNER_BAND_5GHZ) }
                    1 -> ensureNearby { startReceiving(WifiP2pConfig.GROUP_OWNER_BAND_2GHZ) }
                    else -> startInternetReceive()
                }
            }
            .show()
    }

    /** After files are chosen: nearby (QR) or far away (internet code). */
    private fun chooseSendWay() {
        if (active || pickedUris.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("Lene wala phone kahan hai?")
            .setItems(arrayOf("Paas mein - QR scan karo (sabse tez)", "Door hai - internet se, code daalo")) { _, which ->
                if (which == 0) ensureNearby { openScanner() } else askCode()
            }
            .show()
    }

    /** Wi-Fi on + permission, needed only for the nearby modes. */
    private fun ensureNearby(then: () -> Unit) {
        if (p2p == null || channel == null) {
            say(phoneFault("is phone mein Wi-Fi Direct nahi hai, toh paas wala mode nahi chalega. Internet wala mode use karo."))
            return
        }
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (!wifi.isWifiEnabled) {
            say(phoneFault("Wi-Fi band hai. Wi-Fi on karo, phir dobara button dabao."))
            try {
                startActivity(Intent(Settings.Panel.ACTION_WIFI))
            } catch (_: Exception) {
            }
            return
        }
        val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES
        else Manifest.permission.ACCESS_FINE_LOCATION
        if (checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) {
            afterPermission = then
            requestPermissions(arrayOf(perm), REQ_PERMISSION)
            return
        }
        then()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code != REQ_PERMISSION) return
        val next = afterPermission
        afterPermission = null
        if (results.isNotEmpty() && results.all { it == PackageManager.PERMISSION_GRANTED }) next?.invoke()
        else say(phoneFault("ijazat nahi mili. Bina ijazat ke yeh kaam nahi ho sakta. Dobara button dabao aur Allow karo."))
    }

    private fun pickMedia() {
        log("opening photo picker")
        val i: Intent
        if (Build.VERSION.SDK_INT >= 33) {
            i = Intent(MediaStore.ACTION_PICK_IMAGES)
            i.putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, MediaStore.getPickImagesMaxLimit())
        } else {
            i = Intent(Intent.ACTION_OPEN_DOCUMENT)
            i.addCategory(Intent.CATEGORY_OPENABLE)
            i.type = "*/*"
            i.putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
            i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        try {
            startActivityForResult(i, REQ_PICK)
        } catch (e: Exception) {
            pickFiles()
        }
    }

    private fun pickFiles() {
        log("opening file picker")
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "*/*"
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        try {
            startActivityForResult(i, REQ_PICK)
        } catch (e: Exception) {
            say(phoneFault("file chunne wali screen nahi khul rahi (${e.message})."))
        }
    }

    private fun openScanner() {
        say("${pickedUris.size} file chuni. Ab lene wale phone ka QR scan karo.")
        val scan = IntentIntegrator(this)
        scan.setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
        scan.setPrompt("Lene wale phone ka QR scan karo")
        scan.setBeepEnabled(false)
        scan.setOrientationLocked(true)
        try {
            scan.initiateScan()
            log("scanner opened")
        } catch (e: Exception) {
            say(appFault("scanner nahi khula (${e.message})."))
        }
    }

    private fun askCode() {
        val input = EditText(this)
        input.hint = "jaise: abcd efgh"
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        input.setSingleLine(true)
        val pad = dp(20)
        val holder = LinearLayout(this)
        holder.setPadding(pad, dp(8), pad, 0)
        holder.addView(
            input,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )
        AlertDialog.Builder(this)
            .setTitle("Lene wale phone ka code daalo")
            .setView(holder)
            .setPositiveButton("Bhejo") { _, _ ->
                val code = input.text.toString().lowercase(Locale.US).filter { it in 'a'..'z' || it in '0'..'9' }
                if (code.length < 6) say("Code galat lag raha hai. Lene wale phone pe jo code dikh raha hai wahi daalo.")
                else startInternetSend(code)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        log("back from screen $requestCode, result $resultCode")
        if (requestCode == REQ_PICK) {
            if (resultCode != RESULT_OK || data == null) {
                say("Koi file nahi chuni. Jab ready ho, dobara button dabao.")
                return
            }
            val list = ArrayList<Uri>()
            val clip = data.clipData
            if (clip != null) {
                for (i in 0 until clip.itemCount) list.add(clip.getItemAt(i).uri)
            } else {
                data.data?.let { list.add(it) }
            }
            if (list.isEmpty()) return
            pickedUris = list
            say("${list.size} file chuni. Ab batao lene wala phone kahan hai.")
            chooseSendWay()
            return
        }
        val result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        if (result != null) {
            val contents = result.contents
            if (contents == null) say("QR scan nahi hua. Camera ko lene wale phone ke QR ke saamne rakho aur dobara try karo.")
            else startSending(contents)
            return
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    private fun randomText(n: Int): String {
        val rnd = SecureRandom()
        val letters = "abcdefghjkmnpqrstuvwxyz23456789"
        val sb = StringBuilder()
        for (i in 0 until n) sb.append(letters[rnd.nextInt(letters.length)])
        return sb.toString()
    }

    // ------------------------------------------------------ nearby: receiver

    private fun startReceiving(band: Int) {
        val gen = beginSession()
        showTip(phoneCheck(true))
        say("Seedha link bana raha hoon...")
        val ssid = "DIRECT-DS-" + randomText(5)
        val pass = randomText(12)
        leaveGroup {
            if (gen == generation) createGroup(gen, ssid, pass, band)
        }
    }

    @SuppressLint("MissingPermission")
    private fun createGroup(gen: Int, ssid: String, pass: String, band: Int) {
        val m = p2p
        val c = channel
        if (m == null || c == null) return
        val cfg = WifiP2pConfig.Builder()
            .setNetworkName(ssid)
            .setPassphrase(pass)
            .setGroupOperatingBand(band)
            .enablePersistentMode(false)
            .build()
        m.createGroup(c, cfg, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                if (gen != generation) return
                log("direct link created, band request $band")
                onGroupReady(gen, ssid, pass)
            }

            override fun onFailure(reason: Int) {
                if (gen != generation) return
                log("create link failed, band request $band, code $reason")
                if (band != WifiP2pConfig.GROUP_OWNER_BAND_AUTO) {
                    // Some phones refuse a forced band; let the phone choose.
                    ui.postDelayed({
                        if (gen == generation) createGroup(gen, ssid, pass, WifiP2pConfig.GROUP_OWNER_BAND_AUTO)
                    }, 400)
                } else {
                    stopEverything(phoneFault("phone seedha link nahi bana pa raha (code $reason). Wi-Fi ek baar band karke on karo, phir dobara try karo."))
                }
            }
        })
    }

    private fun onGroupReady(gen: Int, ssid: String, pass: String) {
        try {
            val bmp = BarcodeEncoder().encodeBitmap(
                "$QR_PREFIX\n$ssid\n$pass\n${Transfer.PORT}", BarcodeFormat.QR_CODE, 700, 700
            )
            qrView.setImageBitmap(bmp)
            qrView.visibility = View.VISIBLE
        } catch (e: Exception) {
            stopEverything(appFault("QR nahi ban paya (${e.message})."))
            return
        }
        say("Ready! Doosre phone pe Send files dabao aur yeh QR scan karo.")
        showLinkBand(gen, 0)

        thread(name = "ds-receive") {
            var error: Throwable? = null
            try {
                val server = ServerSocket()
                track(server)
                server.reuseAddress = true
                server.receiveBufferSize = Transfer.SOCKET_BUFFER
                server.bind(InetSocketAddress(Transfer.PORT))
                Transfer.receive(this, server, progress, { c -> track(c) }) {
                    ui.post { if (gen == generation) onTransferStarted("File aa rahi hai...") }
                }
            } catch (e: Throwable) {
                error = e
            }
            ui.post { if (gen == generation) onTransferEnded(error, "Download/DropSwift mein save ho gayi") }
        }
    }

    // -------------------------------------------------------- nearby: sender

    @SuppressLint("MissingPermission")
    private fun startSending(qr: String) {
        val parts = qr.trim().split("\n")
        if (parts.size < 4 || parts[0] != QR_PREFIX) {
            say("Yeh DropSwift ka QR nahi hai. Lene wale phone pe Receive files dabake jo QR aata hai woh scan karo.")
            return
        }
        val ssid = parts[1]
        val pass = parts[2]
        val port = parts[3].trim().toIntOrNull() ?: Transfer.PORT
        val m = p2p
        val c = channel
        if (m == null || c == null) return
        val uris = pickedUris
        val gen = beginSession()
        showTip(phoneCheck(false))
        say("Doosre phone se jud raha hoon...")

        leaveGroup {
            if (gen != generation) return@leaveGroup
            val cfg = try {
                WifiP2pConfig.Builder().setNetworkName(ssid).setPassphrase(pass).build()
            } catch (e: Exception) {
                stopEverything("QR theek se nahi padha gaya. Dobara scan karo.")
                return@leaveGroup
            }
            m.connect(c, cfg, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    if (gen != generation) return
                    log("join request accepted, waiting for link")
                    waitForOwner(gen, SystemClock.elapsedRealtime() + 40_000) { owner ->
                        if (gen != generation) return@waitForOwner
                        if (owner == null) {
                            stopEverything(netFault("doosre phone se jud nahi paya. Dono phone paas rakho aur dobara try karo."))
                        } else {
                            showLinkBand(gen, 0)
                            runSender(gen, owner, port, uris)
                        }
                    }
                }

                override fun onFailure(reason: Int) {
                    if (gen != generation) return
                    stopEverything(phoneFault("phone judne nahi de raha (code $reason). Wi-Fi ek baar band karke on karo, phir dobara try karo."))
                }
            })
        }
    }

    private fun waitForOwner(gen: Int, deadline: Long, cb: (InetAddress?) -> Unit) {
        val m = p2p
        val c = channel
        if (m == null || c == null || gen != generation) {
            cb(null)
            return
        }
        m.requestConnectionInfo(c) { info ->
            val owner = info?.groupOwnerAddress
            if (info != null && info.groupFormed && owner != null) {
                cb(owner)
            } else if (SystemClock.elapsedRealtime() > deadline) {
                cb(null)
            } else {
                ui.postDelayed({ waitForOwner(gen, deadline, cb) }, 500)
            }
        }
    }

    private fun runSender(gen: Int, owner: InetAddress, port: Int, uris: List<Uri>) {
        say("Jud gaya. Shuru kar raha hoon...")
        thread(name = "ds-send") {
            var error: Throwable? = null
            try {
                val items = Transfer.describe(this, uris)
                progress.total = items.sumOf { it.size }
                var socket: Socket? = null
                var last: Exception? = null
                for (attempt in 0 until 40) {
                    if (gen != generation) return@thread
                    val s = Socket()
                    try {
                        s.sendBufferSize = Transfer.SOCKET_BUFFER
                        s.connect(InetSocketAddress(owner, port), 1500)
                        socket = s
                        break
                    } catch (e: IOException) {
                        last = e
                        try {
                            s.close()
                        } catch (_: Exception) {
                        }
                        Thread.sleep(500)
                    }
                }
                val s = socket ?: throw IOException("receiver not reachable (${last?.message})")
                track(s)
                val sockets = ArrayList<Socket>()
                sockets.add(s)
                // Extra connections so several blocks travel at the same time.
                for (extra in 1 until Transfer.STREAMS) {
                    val more = Socket()
                    track(more)
                    more.sendBufferSize = Transfer.SOCKET_BUFFER
                    more.connect(InetSocketAddress(owner, port), 5000)
                    sockets.add(more)
                }
                ui.post { if (gen == generation) onTransferStarted("File ja rahi hai...") }
                Transfer.send(this, sockets, items, progress)
            } catch (e: Throwable) {
                error = e
            }
            ui.post { if (gen == generation) onTransferEnded(error, "Pahunch gayi") }
        }
    }

    // --------------------------------------------------------- internet mode

    private fun startInternetReceive() {
        val gen = beginSession()
        showTip(phoneCheck(true))
        val code = randomText(8)
        shareCode = code
        speedView.text = code.substring(0, 4) + " " + code.substring(4)
        linkView.text = "Internet mode. Code pe dabao toh share ho jayega."
        say("Yeh code bhejne wale ko batao. Woh Send files mein 'Door hai - internet se' chun ke yeh code daalega. Is screen ko khula rakho.")
        val link = InternetLink(applicationContext) { line -> log(line) }
        track(link)
        thread(name = "ds-net-receive") {
            var error: Throwable? = null
            try {
                link.receive(code, progress) {
                    ui.post { if (gen == generation) onTransferStarted("Internet se file aa rahi hai...") }
                }
            } catch (e: Throwable) {
                error = e
            }
            ui.post { if (gen == generation) onTransferEnded(error, "Download/DropSwift mein save ho gayi") }
        }
    }

    private fun startInternetSend(code: String) {
        val uris = pickedUris
        if (uris.isEmpty()) return
        val gen = beginSession()
        showTip(phoneCheck(false))
        linkView.text = "Internet mode"
        say("Internet se doosre phone ko dhoond raha hoon... Dono phone pe internet on rakho.")
        val link = InternetLink(applicationContext) { line -> log(line) }
        track(link)
        thread(name = "ds-net-send") {
            var error: Throwable? = null
            try {
                val items = Transfer.describe(this, uris)
                progress.total = items.sumOf { it.size }
                link.send(code, items, progress) {
                    ui.post { if (gen == generation) onTransferStarted("Internet se file ja rahi hai...") }
                }
            } catch (e: Throwable) {
                error = e
            }
            ui.post { if (gen == generation) onTransferEnded(error, "Pahunch gayi") }
        }
    }

    private fun shareTheCode() {
        val code = shareCode ?: return
        if (transferring) return
        try {
            val i = Intent(Intent.ACTION_SEND)
            i.type = "text/plain"
            i.putExtra(Intent.EXTRA_TEXT, "DropSwift code: $code")
            startActivity(Intent.createChooser(i, "Code share karo"))
        } catch (_: Exception) {
        }
    }

    // ------------------------------------------------------- shared plumbing

    private fun beginSession(): Int {
        generation++
        active = true
        transferring = false
        progress = Progress()
        shareCode = null
        speedView.text = ""
        detailView.text = ""
        linkView.text = ""
        receivedBox.removeAllViews()
        searchBox.removeAllViews()
        selected.clear()
        refreshSendSelected()
        showTip(null)
        bar.visibility = View.GONE
        qrView.visibility = View.GONE
        setBusy(true)
        return generation
    }

    private fun track(c: Closeable) {
        synchronized(openThings) { openThings.add(c) }
    }

    private fun closeAll() {
        val copy: List<Closeable>
        synchronized(openThings) {
            copy = ArrayList(openThings)
            openThings.clear()
        }
        // Closing a socket can touch the network, so keep it off the screen thread.
        thread(name = "ds-close") {
            for (c in copy) {
                try {
                    c.close()
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun leaveGroup(then: () -> Unit) {
        val m = p2p
        val c = channel
        if (m == null || c == null) {
            then()
            return
        }
        try {
            m.removeGroup(c, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    ui.postDelayed({ then() }, 700)
                }

                override fun onFailure(reason: Int) {
                    then()
                }
            })
        } catch (_: Throwable) {
            then()
        }
    }

    /** Shows whether the direct link came up on 5 GHz (fast) or 2.4 GHz (slow). */
    @SuppressLint("MissingPermission")
    private fun showLinkBand(gen: Int, tries: Int) {
        val m = p2p
        val c = channel
        if (m == null || c == null || gen != generation) return
        m.requestGroupInfo(c) { group ->
            if (gen != generation) return@requestGroupInfo
            val f = group?.frequency ?: 0
            if (f > 0) {
                val band = when {
                    f >= 5925 -> "6 GHz"
                    f >= 4900 -> "5 GHz"
                    else -> "2.4 GHz (slow band)"
                }
                var line = "Link: $band, $f MHz"
                try {
                    val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                    val info = wifi.connectionInfo
                    if (info != null && info.networkId != -1 && info.frequency > 0 && info.frequency != f) {
                        line += "\nRouter: ${info.frequency} MHz"
                        showTip("Swifty ki salah: phone router se bhi juda hai aur woh alag channel pe hai, isse speed aadhi ho sakti hai. Router se disconnect karke bhejo.")
                    }
                } catch (_: Exception) {
                }
                linkView.text = line
                log(line.replace("\n", " | "))
            } else if (tries < 20) {
                ui.postDelayed({ showLinkBand(gen, tries + 1) }, 700)
            }
        }
    }

    private fun onTransferStarted(word: String) {
        transferring = true
        try {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val lock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "dropswift")
            lock.setReferenceCounted(false)
            lock.acquire()
            wifiLock = lock
        } catch (_: Exception) {
        }
        qrView.visibility = View.GONE
        bar.visibility = View.VISIBLE
        bar.progress = 0
        say(word)
        startedAt = SystemClock.elapsedRealtime()
        lastTickAt = startedAt
        lastTickBytes = 0
        val gen = generation
        ui.postDelayed(object : Runnable {
            override fun run() {
                if (gen != generation || !transferring) return
                tick()
                ui.postDelayed(this, 300)
            }
        }, 300)
    }

    private fun tick() {
        val now = SystemClock.elapsedRealtime()
        val done = progress.done.get()
        val total = progress.total
        val dt = (now - lastTickAt) / 1000.0
        if (dt > 0) {
            val mbps = (done - lastTickBytes) / 1048576.0 / dt
            speedView.text = String.format(Locale.US, "%.1f MB/s", mbps)
        }
        lastTickAt = now
        lastTickBytes = done
        if (total > 0) bar.progress = ((done * 1000) / total).toInt().coerceIn(0, 1000)
        detailView.text = "${progress.file}\n${size(done)} / ${size(total)}"
    }

    private fun onTransferEnded(error: Throwable?, okWord: String) {
        val wasTransferring = transferring
        transferring = false
        val secs = if (wasTransferring) (SystemClock.elapsedRealtime() - startedAt) / 1000.0 else 0.0
        val done = progress.done.get()
        if (error == null) {
            bar.progress = 1000
            val avg = if (secs > 0) done / 1048576.0 / secs else 0.0
            speedView.text = String.format(Locale.US, "%.1f MB/s", avg)
            detailView.text = String.format(
                Locale.US, "%d file, %s, %.1f second mein (upar average speed)\n%s",
                progress.files.get(), size(done), secs, okWord
            )
            say("Ho gaya! Sab files $okWord.")
            showReceived(ArrayList(progress.saved))
            log(String.format(
                Locale.US, "result: %.1f MB/s avg, %s in %.1f s | %d connections | per connection: waited on Wi-Fi %.1f s, on storage %.1f s",
                avg, size(done), secs, progress.streams,
                progress.netNanos.get() / 1e9 / progress.streams, progress.diskNanos.get() / 1e9 / progress.streams
            ))
        } else {
            log("failed: ${error.javaClass.simpleName}: ${error.message}")
            speedView.text = ""
            say(diagnose(error))
        }
        // Give the last bytes a moment to leave before the link is dropped.
        val gen = generation
        ui.postDelayed({ if (gen == generation) finishSession() }, 1500)
    }

    private fun finishSession() {
        generation++
        active = false
        transferring = false
        shareCode = null
        try {
            wifiLock?.release()
        } catch (_: Exception) {
        }
        wifiLock = null
        closeAll()
        leaveGroup { }
        qrView.visibility = View.GONE
        setBusy(false)
    }

    private fun stopEverything(message: String) {
        finishSession()
        bar.visibility = View.GONE
        speedView.text = ""
        linkView.text = ""
        say(message)
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

    override fun onDestroy() {
        generation++
        closeAll()
        leaveGroup { }
        super.onDestroy()
    }

    companion object {
        private const val REQ_PERMISSION = 11
        private const val REQ_PICK = 12
        private const val QR_PREFIX = "DSWIFT1"
    }
}
