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
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.BatteryManager
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.HapticFeedbackConstants
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
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())

    private lateinit var statusView: TextView   // Swifty's speech bubble
    private lateinit var botView: ImageView
    private lateinit var tipView: TextView
    private lateinit var detailView: TextView
    private lateinit var speedView: TextView
    private lateinit var linkView: TextView
    private lateinit var logView: TextView
    private lateinit var logToggle: TextView
    private lateinit var receivedBox: LinearLayout
    private lateinit var askInput: EditText
    private lateinit var searchBox: LinearLayout
    private lateinit var btnSendSelected: Button
    private lateinit var qrView: ImageView
    private lateinit var altView: TextView
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
    private var wakeLock: PowerManager.WakeLock? = null

    // Receiver: what we already have, so a broken link or a pause continues where it stopped.
    private val resume = ResumeStore()
    @Volatile private var paused = false
    private val attemptThings = ArrayList<Closeable>()
    private var receiverId = 0L
    private var hostTransferId = 0L
    private var emaSpeed = 0.0
    private var lastNotifyAt = 0L
    private var heatTicks = 0
    private var heatTold = false
    private var historyWho = ""
    private var historyIn = true
    private lateinit var btnPause: Button
    private lateinit var doneBox: LinearLayout
    private lateinit var qualityView: TextView
    private lateinit var btnAddMore: Button

    // Queue: the files being shared now; a new id each time files are added.
    private class HostBatch(val items: List<Transfer.Item>, val id: Long)
    @Volatile private var batch = HostBatch(emptyList(), 0L)
    private var pickedItems: List<Transfer.Item>? = null
    private var addingMore = false
    private var autoMode = false
    private var hostBand = 0
    private var autoSwitched = false
    private val peerBreaks = AtomicInteger(0)
    @Volatile private var serversUp = false
    private var linkFreq = 0
    private var joinSsid = ""
    private var joinPass = ""
    private var lanJoin = false

    private val settings by lazy { getSharedPreferences("settings", Context.MODE_PRIVATE) }
    private fun flag(k: String) = settings.getBoolean(k, false)
    private fun setFlag(k: String, v: Boolean) = settings.edit().putBoolean(k, v).apply()

    private var progress = Progress()
    private var startedAt = 0L
    private var lastTickAt = 0L
    private var lastTickBytes = 0L

    // Sender ("host") state: one link, many receivers.
    private class Peer(val id: Long, val name: String) {
        @Volatile var p = Progress()
        @Volatile var status = "jud gaya"
        @Volatile var done = false
        @Volatile var ok = false
        @Volatile var attempt = 0
    }

    private val peers: MutableList<Peer> = Collections.synchronizedList(ArrayList())
    @Volatile private var hosting = false
    private var hostItems: List<Transfer.Item> = emptyList()
    private var hostSsid = ""
    private var hostPass = ""
    private var showingUrlQr = false
    private var hostLan = false
    private var hostReceiveOnly = false
    private val webSaved: MutableList<Pair<String, Uri>> = Collections.synchronizedList(ArrayList())
    private var hostAddress = ""
    private var lanQr = ""
    private var web: WebShare? = null

    private class Found(val uri: Uri, val name: String, val size: Long, val folder: String)

    // ---------------------------------------------------------------- screen

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        installCrashCatcher()
        buildScreen()
        p2p = getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        channel = p2p?.initialize(this, mainLooper, null)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            // So the transfer can show in the notification bar while the screen is off.
            try {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFY)
            } catch (_: Exception) {
            }
        }
        if (!showLastCrash()) {
            say("Namaste! Main Swifty hoon. File bhejni hai ya leni hai? Button dabao, ya neeche mujhse likh ke bolo, jaise \"video bhejo\" ya \"file leni hai\".")
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
            while (logLines.size > 80) logLines.removeAt(0)
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

        // Talk to Swifty / find a file by name
        val askRow = LinearLayout(this)
        askRow.orientation = LinearLayout.HORIZONTAL
        askRow.gravity = Gravity.CENTER_VERTICAL
        askInput = EditText(this)
        askInput.hint = "Swifty se bolo, ya file ka naam..."
        askInput.setHintTextColor(Color.parseColor("#71829B"))
        askInput.setTextColor(Color.WHITE)
        askInput.textSize = 15f
        askInput.setSingleLine(true)
        askInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        askInput.imeOptions = EditorInfo.IME_ACTION_SEND
        askInput.background = rounded(Color.parseColor("#151C2E"), 12)
        askInput.setPadding(dp(14), dp(12), dp(14), dp(12))
        askInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                onAsk()
                true
            } else false
        }
        askRow.addView(askInput, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val btnAsk = button("Bolo", Color.parseColor("#06B6D4"), Color.parseColor("#04130C"))
        btnAsk.textSize = 15f
        btnAsk.setOnClickListener { onAsk() }
        val askBtnParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(48))
        askBtnParams.leftMargin = dp(8)
        askRow.addView(btnAsk, askBtnParams)
        root.addView(askRow, wide)

        searchBox = LinearLayout(this)
        searchBox.orientation = LinearLayout.VERTICAL
        root.addView(searchBox, wide)

        btnSendSelected = button("Send", Color.parseColor("#00E68A"), Color.parseColor("#04130C"))
        btnSendSelected.visibility = View.GONE
        btnSendSelected.setOnClickListener {
            if (!active && selected.isNotEmpty()) {
                pickedItems = null
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

        altView = text(14f, Color.parseColor("#06B6D4"), true)
        altView.visibility = View.GONE
        altView.setPadding(dp(8), dp(10), dp(8), dp(10))
        altView.setOnClickListener { toggleBrowserQr() }
        root.addView(altView, wide)

        btnAddMore = button("+ Aur file jodo", Color.parseColor("#263650"), Color.WHITE)
        btnAddMore.textSize = 15f
        btnAddMore.visibility = View.GONE
        btnAddMore.setOnClickListener {
            if (hosting && !hostReceiveOnly) {
                addingMore = true
                showPicker()
            }
        }
        val addParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46))
        addParams.topMargin = dp(6)
        root.addView(btnAddMore, addParams)

        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        bar.max = 1000
        bar.visibility = View.GONE
        root.addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(14)))
        root.addView(gap(8))

        speedView = text(34f, Color.parseColor("#00E68A"), true)
        speedView.setOnClickListener { shareTheCode() }
        root.addView(speedView, wide)

        qualityView = text(13f, Color.parseColor("#9AA9BD"), true)
        root.addView(qualityView, wide)

        detailView = text(14f, Color.parseColor("#9AA9BD"))
        root.addView(detailView, wide)
        root.addView(gap(18))

        btnPause = button("Pause", Color.parseColor("#3A3320"), Color.parseColor("#FFE7A8"))
        btnPause.visibility = View.GONE
        btnPause.setOnClickListener { togglePause() }
        val pauseParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(50))
        pauseParams.bottomMargin = dp(8)
        root.addView(btnPause, pauseParams)

        btnCancel = button("Cancel", Color.parseColor("#48232D"), Color.parseColor("#FFDCE0"))
        btnCancel.visibility = View.GONE
        btnCancel.setOnClickListener { stopEverything(if (hosting) "Band kar diya." else "Theek hai, rok diya.") }
        root.addView(btnCancel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(50)))

        doneBox = LinearLayout(this)
        doneBox.orientation = LinearLayout.HORIZONTAL
        doneBox.visibility = View.GONE
        val doneParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52))
        doneParams.topMargin = dp(10)
        root.addView(doneBox, doneParams)

        receivedBox = LinearLayout(this)
        receivedBox.orientation = LinearLayout.VERTICAL
        root.addView(receivedBox, wide)

        root.addView(gap(18))
        logToggle = text(12f, Color.parseColor("#71829B"))
        logToggle.text = "Show details"
        logToggle.setPadding(dp(8), dp(8), dp(8), dp(8))
        logToggle.setOnClickListener { showLog(logView.visibility != View.VISIBLE) }
        val historyLink = text(13f, Color.parseColor("#06B6D4"), true)
        historyLink.text = "History (pichhle transfer)"
        historyLink.setPadding(dp(8), dp(8), dp(8), dp(4))
        historyLink.setOnClickListener { showHistory() }
        root.addView(historyLink, wide)
        val settingsLink = text(13f, Color.parseColor("#06B6D4"), true)
        settingsLink.text = "Settings"
        settingsLink.setPadding(dp(8), dp(4), dp(8), dp(4))
        settingsLink.setOnClickListener { showSettings() }
        root.addView(settingsLink, wide)
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
            m.contains("integrity failed") ->
                netFault("file raste mein kharab ho gayi (SHA-256 match nahi hua), isliye maine use hata diya. Dobara bhejo.")
            m.contains("not confirmed") ->
                netFault("saara data bhej diya, par aakhri second mein link toot gaya, isliye doosre phone se 'mil gaya' ki khabar nahi aayi. Doosre phone pe Download/DropSwift check karo.")
            m.contains("enospc") || m.contains("no space") ->
                phoneFault("phone ka storage bhar gaya. Thodi jagah khaali karke dobara lo.")
            m.contains("different dropswift version") || m.contains("old or foreign") ->
                appFault("dono phone pe alag-alag version hai. Dono pe naya DropSwift install karo.")
            m.contains("could not make a direct link") ->
                netFault("dono ke internet seedha link nahi banne de rahe. Aksar mobile data pe hota hai. Ek phone ko Wi-Fi pe laake dobara try karo.")
            m.contains("no internet") || m.contains("helper") || m.contains("unable to resolve host") ->
                phoneFault("internet nahi chal raha. Data ya Wi-Fi check karke dobara try karo.")
            m.contains("did not join in time") ->
                "Doosra phone juda hi nahi. Code sahi daala tha? Dobara try karo."
            m.contains("lan sender not found") ->
                netFault("bhejne wala phone is network pe nahi mila. Dono phone ek hi hotspot ya Wi-Fi pe hone chahiye. Lene wale phone ko pehle bhejne wale ke hotspot se jodo, phir QR scan karo.")
            m.contains("not reachable") ->
                netFault("bhejne wale phone tak pahunch nahi paya. Dono phone paas laao, bhejne wale pe QR khula rakho, phir dobara scan karo.")
            m.contains("address already in use") || m.contains("eaddrinuse") ->
                appFault("pichla transfer abhi poori tarah band nahi hua. 5 second ruk ke dobara try karo.")
            e is EOFException || m.contains("reset") || m.contains("broken pipe") || m.contains("timed out") ||
                m.contains("abort") || m.contains("socket closed") || m.contains("link closed") ||
                m.contains("link went silent") || m.contains("did not arrive completely") || m.contains("did not confirm") || m.contains("eof") ->
                netFault("beech mein link toot gaya. Phone door ho gaye, ya doosre phone pe app band ho gaya. Paas aake dobara bhejo.")
            e is SecurityException || m.contains("permission") || m.contains("cannot open") ->
                phoneFault("is file ko padhne ki ijazat nahi mili. File dobara chun ke bhejo.")
            m.contains("cannot create") || m.contains("cannot re-open") ->
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
        batteryTip()?.let { tips.add(it) }
        if (receiving) {
            try {
                val free = StatFs(Environment.getExternalStorageDirectory().path).availableBytes
                if (free < 2L * 1024 * 1024 * 1024) tips.add("Phone mein sirf ${size(free)} jagah bachi hai.")
            } catch (_: Throwable) {
            }
        }
        return if (tips.isEmpty()) null else "Swifty ki salah: " + tips.joinToString(" ")
    }

    /** Battery low (and not charging) or phone hot. */
    private fun batteryTip(): String? {
        return try {
            val bi = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
            val level = bi.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = bi.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            val plugged = bi.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
            val temp = bi.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0
            val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
            val out = ArrayList<String>()
            if (pct in 0..19 && !plugged) out.add("Battery $pct% hai, lamba transfer ho toh charger laga lo.")
            if (temp >= 42.0) out.add(String.format(Locale.US, "Phone garam hai (%.0f°C), isse speed kam ho sakti hai.", temp))
            if (out.isEmpty()) null else out.joinToString(" ")
        } catch (_: Throwable) {
            null
        }
    }

    /** Understands short Hindi/Hinglish/English requests typed to Swifty. Works offline. */
    private fun onAsk() {
        val raw = askInput.text.toString().trim()
        try {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(askInput.windowToken, 0)
        } catch (_: Throwable) {
        }
        if (raw.isEmpty()) {
            say("Kuch likho, jaise \"video bhejo\", \"file leni hai\", \"door bhejna hai\", ya kisi file ka naam.")
            return
        }
        log("you: $raw")
        val t = " " + raw.lowercase(Locale.US).replace(Regex("[^a-z0-9\\u0900-\\u097f ]"), " ") + " "
        fun has(vararg words: String) = words.any { t.contains(" $it") }

        val wantsHelp = has("help", "madad", "kaise", "kese", "kya kar", "samjha", "how")
        val wantsSpeed = has("speed", "slow", "dheem", "dhim", "tez", "fast")
        val wantsFar = has("door", "dur ", "internet", "online", "far")
        val wantsApple = has("iphone", "ipad", "laptop", "computer", "pc ", "mac ", "windows")
        val wantsWhere = has("kahan", "kaha ", "where", "folder") && has("file", "save", "gayi", "gaya", "aayi")
        val wantsReceive = has("receive", "lena", "leni", "le lo", "lelo", "mangw", "aa rahi", "aane", "lunga", "lungi")
        val wantsSend = has("bhej", "send", "share", "de do", "dedo", "dena", "transfer")
        val wantsGoogle = has("google", "search karo", "net pe dekh", "internet pe dekh")
        val wantsSafe = has("safe", "secure", "suraksh", "hack", "privacy", "chori")
        val wantsHowMany = has("kitne phone", "kitne log", "how many", "ek saath kitne")
        val wantsNoNet = has("bina internet", "without internet", "net chahiye", "internet chahiye", "data lagega", "data lagta")
        val isQuestion = raw.contains("?") || has(
            "kya", "kyun", "kyu", "kyon", "kaise", "kese", "kab", "kaun", "kon", "kitna", "kitni", "kitne",
            "what", "why", "how", "when", "which", "who", "matlab", "meaning"
        )

        when {
            active -> say("Abhi ek kaam chal raha hai. Khatam hone do, ya Cancel/Band karo dabao.")
            wantsGoogle -> openGoogle(raw.replace(Regex("(?i)google( pe| par| se)?|search karo|dhoondo|dhundo"), " ").trim())
            wantsSafe -> say("Paas wale mode mein file seedhi phone se phone jaati hai, kisi server pe nahi. Har baar naya random password wala link banta hai, sirf QR scan karne wala jud sakta hai, aur har file SHA-256 se jaanchi jaati hai.")
            wantsHowMany -> say("Ek QR ko 3 ya zyada phone scan kar sakte hain. Par speed un sab mein bant jaati hai, toh jitne zyada phone, har ek ko utni kam speed.")
            wantsNoNet -> say("Paas wale mode mein internet ya data bilkul nahi lagta, bas dono phone ka Wi-Fi on hona chahiye. Internet sirf 'Bahut door' wale mode mein lagta hai.")
            wantsWhere -> say("Aayi hui files phone ke Download/DropSwift folder mein hoti hain. Ho jaane ke baad list mein naam pe dabao toh seedha khul jayegi.")
            wantsApple -> say("iPhone ya laptop pe app nahi chahiye. Is phone pe Send files dabao, file chuno, 'Paas mein' chuno. Phir QR ke neeche 'iPhone ya laptop' dabao, main steps bataunga.")
            wantsSpeed -> say("Speed ke liye: 1) dono phone paas rakho, 2) dono ko ghar ke Wi-Fi router se disconnect karo, 3) battery saver band karo, 4) jo phone tez hai usse bhejo. Ek saath 3 phone lenge toh speed teeno mein bantegi.")
            wantsHelp && !wantsSend && !wantsReceive -> say("Bhejne ke liye: Send files dabao, file chuno, QR dikhega. Lene wala: Receive files dabake woh QR scan kare, file aa jayegi. Ek QR ko 3 ya zyada phone scan kar sakte hain.")
            wantsReceive && !wantsSend -> {
                if (wantsFar) {
                    say("Theek hai, internet se lete hain.")
                    startInternetReceive()
                } else {
                    say("Theek hai! Bhejne wale phone ka QR scan karo.")
                    ensureNearby { openScanner() }
                }
            }
            wantsSend -> {
                val name = fileWords(t)
                if (name.length >= 2) {
                    findFiles(name)
                } else {
                    onSendClicked()
                }
            }
            isQuestion && !wantsHelp -> offerGoogle(raw)
            else -> findFiles(raw)
        }
    }

    /** Swifty does not know the answer: offer to look it up on the web. */
    private fun offerGoogle(question: String) {
        say("Yeh mujhe pakka nahi pata. Google pe dhoond ke dikhaun?")
        AlertDialog.Builder(this)
            .setTitle("Google pe dhoondun?")
            .setMessage(question)
            .setPositiveButton("Haan, dhoondo") { _, _ -> openGoogle(question) }
            .setNegativeButton("Nahi", null)
            .show()
    }

    private fun openGoogle(question: String) {
        if (question.length < 2) {
            say("Kya dhoondna hai? Likho, jaise \"google wifi direct kya hai\".")
            return
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(question))))
            say("Google khol diya. Jawab dekh ke wapas aa jao.")
        } catch (e: Exception) {
            say(phoneFault("browser nahi khul raha. Internet aur browser app check karo."))
        }
    }

    /** During a transfer: speak up once if the speed falls far below what this link was giving. */
    private var peakSpeed = 0.0
    private var slowTicks = 0
    private var slowTold = false

    private fun watchSpeed(mbps: Double) {
        if (mbps > peakSpeed) peakSpeed = mbps
        if (peakSpeed < 5.0 || slowTold) return
        if (mbps < peakSpeed * 0.3) slowTicks++ else slowTicks = 0
        if (slowTicks >= 15) {
            slowTold = true
            showTip("Swifty: speed gir gayi hai (pehle ${peakSpeed.toInt()} MB/s thi). Dono phone paas laao aur beech mein kuch na ho.")
        }
    }

    /** Drops the command words so only the file name is left, e.g. "shaadi wali video bhejo" -> "shaadi". */
    private fun fileWords(t: String): String {
        val skip = setOf(
            "bhej", "bhejo", "bhejna", "bhejni", "bhejdo", "send", "share", "karo", "kar", "do", "de", "dedo", "dena",
            "file", "files", "wali", "wala", "wale", "ko", "ki", "ka", "ke", "mujhe", "muje", "hai", "he", "please", "plz",
            "yeh", "ye", "woh", "wo", "mera", "meri", "mere", "transfer", "is", "us", "abhi", "jaldi", "the", "a", "my",
            "video", "videos", "photo", "photos", "pic", "pics", "image", "song", "gaana", "gana", "pdf", "apk", "doc", "naam"
        )
        val kept = t.trim().split(Regex("\\s+")).filter { it.isNotEmpty() && it !in skip }
        return kept.joinToString(" ").trim()
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
        btnCancel.text = "Cancel"
    }

    // --------------------------------------------------- find a file by name

    private fun findFiles(q: String) {
        if (active) return
        if (q.length < 2) {
            say("File ke naam ke kam se kam 2 akshar likho.")
            return
        }
        if (Build.VERSION.SDK_INT >= 30) {
            if (!Environment.isExternalStorageManager()) {
                say("File dhoondne ke liye mujhe phone ki files dekhne ki ijazat chahiye. Agli screen pe 'Allow access to all files' on karo, wapas aao, aur dobara bolo.")
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
                // Every word must appear in the name, in any order.
                val words = q.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.take(5)
                val where = words.joinToString(" AND ") { "_display_name LIKE ?" } + " AND _size > 0"
                val args = words.map { "%$it%" }.toTypedArray()
                contentResolver.query(
                    base,
                    arrayOf("_id", "_display_name", "_size", "relative_path"),
                    where,
                    args,
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
        addingMore = false
        showPicker()
    }

    /** What kind of files? (also used by "+ Aur file jodo" while sharing) */
    private fun showPicker() {
        AlertDialog.Builder(this)
            .setTitle(if (addingMore) "Aur kya jodna hai?" else "Kya bhejna hai?")
            .setItems(
                arrayOf(
                    "Photos aur videos",
                    "Documents (PDF, Word, Excel, PPT, text)",
                    "Music / audio",
                    "Apps (APK files)",
                    "Koi bhi file",
                    "Poora folder"
                )
            ) { _, which ->
                when (which) {
                    0 -> pickMedia()
                    1 -> pickTyped(
                        arrayOf(
                            "application/pdf", "application/msword", "application/vnd.ms-excel", "application/vnd.ms-powerpoint",
                            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                            "application/vnd.oasis.opendocument.text", "application/rtf", "text/*"
                        )
                    )
                    2 -> pickTyped(arrayOf("audio/*"))
                    3 -> pickTyped(arrayOf("application/vnd.android.package-archive"))
                    4 -> pickFiles()
                    else -> pickFolder()
                }
            }
            .setOnCancelListener { addingMore = false }
            .show()
    }

    private fun pickTyped(mimes: Array<String>) {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "*/*"
        i.putExtra(Intent.EXTRA_MIME_TYPES, mimes)
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        try {
            startActivityForResult(i, REQ_PICK)
        } catch (e: Exception) {
            pickFiles()
        }
    }

    private fun pickFolder() {
        try {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_FOLDER)
        } catch (e: Exception) {
            say(phoneFault("folder chunne wali screen nahi khul rahi (${e.message})."))
        }
    }

    /** Every file inside a picked folder, with its path ("Trip/Day 1/a.jpg"). Runs on a worker thread. */
    private fun listFolder(tree: Uri): List<Transfer.Item> {
        val out = ArrayList<Transfer.Item>()
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        var rootName = rootId.substringAfterLast('/').substringAfter(':').ifEmpty { "Folder" }
        try {
            contentResolver.query(
                DocumentsContract.buildDocumentUriUsingTree(tree, rootId),
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null
            )?.use { c -> if (c.moveToFirst()) c.getString(0)?.let { rootName = it } }
        } catch (_: Exception) {
        }
        fun walk(docId: String, path: String, depth: Int) {
            if (depth > 8 || out.size >= 20000) return
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            contentResolver.query(
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
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    if (c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                        walk(id, "$path/$name", depth + 1)
                    } else {
                        out.add(Transfer.Item(DocumentsContract.buildDocumentUriUsingTree(tree, id), "$path/$name", c.getLong(3)))
                    }
                }
            }
        }
        walk(rootId, rootName, 0)
        return out
    }

    /** Queue: add files while already sharing. Phones already connected get only the new ones. */
    private fun appendItems(newItems: List<Transfer.Item>) {
        addingMore = false
        if (!hosting || newItems.isEmpty()) return
        val known = batch.items.map { it.uri }.toHashSet()
        val merged = batch.items + newItems.filter { it.uri !in known }
        setBatch(merged)
        web?.items = merged
        say("${merged.size - known.size} file aur jod di (kul ${merged.size}). Jude hue phone ko sirf nayi files jayengi, naye phone sab payenge.")
    }

    private fun setBatch(items: List<Transfer.Item>) {
        hostItems = items
        batch = HostBatch(items, SecureRandom().nextLong())
    }

    /** Files chosen for sending: a folder's list, or the picked files. Runs on a worker thread. */
    private fun chosenItems(uris: List<Uri>): List<Transfer.Item> = pickedItems ?: Transfer.describe(this, uris)

    private fun onReceiveClicked() {
        if (active) return
        val recents = recentSenders()
        val labels = ArrayList<String>()
        labels.add("Paas mein - uska QR scan karo")
        for (r in recents) labels.add("↻ ${r.getString("n")} se dobara (bina QR)")
        labels.add("iPhone ya laptop se lena (browser se)")
        labels.add("Bahut door - internet code se")
        AlertDialog.Builder(this)
            .setTitle("Bhejne wala phone kahan hai?")
            .setItems(labels.toTypedArray()) { _, which ->
                when {
                    which == 0 -> ensureNearby { openScanner() }
                    which <= recents.size -> {
                        val r = recents[which - 1]
                        say("${r.getString("n")} se jud raha hoon. Us phone pe Send files karke QR wali screen khuli honi chahiye.")
                        ensureNearby { joinNearby(r.getString("s"), r.getString("p")) }
                    }
                    which == recents.size + 1 -> ensureNearby { startHosting(WifiP2pConfig.GROUP_OWNER_BAND_5GHZ, true) }
                    else -> startInternetReceive()
                }
            }
            .show()
    }

    /** Phones this phone received from before; tap one to join it again without a QR. */
    private fun recentSenders(): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        try {
            val arr = JSONArray(settings.getString("recents", "[]"))
            for (i in 0 until arr.length()) out.add(arr.getJSONObject(i))
        } catch (_: Exception) {
        }
        return out
    }

    private fun rememberSender(ssid: String, pass: String, name: String) {
        if (ssid.isEmpty()) return
        val list = recentSenders().filter { it.optString("s") != ssid }.take(4)
        val arr = JSONArray()
        arr.put(JSONObject().put("s", ssid).put("p", pass).put("n", name.ifEmpty { "Pichhla phone" }).put("t", System.currentTimeMillis()))
        for (o in list) arr.put(o)
        settings.edit().putString("recents", arr.toString()).apply()
    }

    /** This phone's own link name and password; the same every time, so recent phones can join without a QR. */
    private fun myCreds(): Pair<String, String> {
        var ssid = settings.getString("my_ssid", null)
        var pass = settings.getString("my_pass", null)
        if (ssid == null || pass == null) {
            ssid = "DIRECT-DS-" + randomText(5)
            pass = randomText(12)
            settings.edit().putString("my_ssid", ssid).putString("my_pass", pass).apply()
        }
        return Pair(ssid, pass)
    }

    private fun autoBand(): Int {
        return try {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            if (wifi.is5GHzBandSupported) WifiP2pConfig.GROUP_OWNER_BAND_5GHZ else WifiP2pConfig.GROUP_OWNER_BAND_2GHZ
        } catch (_: Throwable) {
            WifiP2pConfig.GROUP_OWNER_BAND_AUTO
        }
    }

    /** Auto mode: if the 5 GHz link keeps breaking, move everyone to the longer-reaching 2.4 GHz link. */
    private fun autoSwitchTo24(gen: Int) {
        if (!autoMode || autoSwitched || !hosting || hostLan || hostBand != WifiP2pConfig.GROUP_OWNER_BAND_5GHZ) return
        autoSwitched = true
        hostBand = WifiP2pConfig.GROUP_OWNER_BAND_2GHZ
        say("Link baar-baar toot raha tha, isliye maine door tak chalne wala 2.4 GHz link bana diya. Jude hue phone apne-aap wapas judenge, jahan ruke the wahin se (speed kam hogi).")
        log("auto mode: switching link to 2.4 GHz")
        leaveGroup { if (gen == generation) createGroup(gen, hostSsid, hostPass, WifiP2pConfig.GROUP_OWNER_BAND_2GHZ) }
    }

    private fun showSettings() {
        val recents = recentSenders()
        fun onOff(b: Boolean) = if (b) "ON" else "OFF"
        val items = arrayOf(
            "Files kahan save hon: ${Storage.label(this)}",
            "Compression (kamzor link pe chhota karke bhejo): ${onOff(flag("compress"))}",
            "Encryption (extra suraksha): ${onOff(flag("encrypt"))}",
            "Silent mode (chhupa notification, bina vibration): ${onOff(flag("silent"))}",
            "Mera DropSwift password badlo",
            "Yaad rakhe phone bhool jao (${recents.size})"
        )
        AlertDialog.Builder(this)
            .setTitle("Settings")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> AlertDialog.Builder(this)
                        .setTitle("Files kahan save hon?")
                        .setItems(arrayOf("Download/DropSwift (default)", "Apna folder chuno")) { _, w ->
                            if (w == 0) {
                                Storage.setTree(this, null)
                                say("Ab files Download/DropSwift mein save hongi.")
                            } else {
                                try {
                                    startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_SAVE_TREE)
                                } catch (e: Exception) {
                                    say(phoneFault("folder chunne wali screen nahi khul rahi."))
                                }
                            }
                        }
                        .show()
                    1 -> {
                        setFlag("compress", !flag("compress"))
                        say(if (flag("compress")) "Compression ON. Text, documents jaisi files chhoti hoke jayengi. Photo/video pe asar nahi (woh pehle se dabi hoti hain)." else "Compression OFF.")
                    }
                    2 -> {
                        setFlag("encrypt", !flag("encrypt"))
                        say(if (flag("encrypt")) "Encryption ON. Har tukda AES-256 se band hoke jayega (paas wale aur hotspot mode mein). Link pehle se password se surakshit hai; yeh upar se ek aur parat hai." else "Encryption OFF. Wi-Fi link phir bhi password se surakshit hai.")
                    }
                    3 -> {
                        setFlag("silent", !flag("silent"))
                        say(if (flag("silent")) "Silent mode ON. Notification chhupa rahega aur vibration nahi hoga. Android ka niyam hai ki background transfer ke liye notification poori tarah hat nahi sakta." else "Silent mode OFF.")
                    }
                    4 -> AlertDialog.Builder(this)
                        .setTitle("Password badlun?")
                        .setMessage("Jin phone ne pehle tumhara QR scan kiya hai, woh ab bina QR nahi jud payenge. Naya QR scan karna padega.")
                        .setPositiveButton("Haan, badlo") { _, _ ->
                            settings.edit().remove("my_ssid").remove("my_pass").apply()
                            myCreds()
                            say("Naya password ban gaya. Purane jude phone ab bina QR nahi jud sakte.")
                        }
                        .setNegativeButton("Nahi", null)
                        .show()
                    else -> {
                        settings.edit().remove("recents").apply()
                        say("Yaad rakhe phone bhool gaya.")
                    }
                }
            }
            .show()
    }

    /** After files are chosen: show a QR nearby, or use an internet code. */
    private fun chooseSendWay() {
        if (active || pickedUris.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("Lene wale kahan hain?")
            .setItems(
                arrayOf(
                    "Auto - app khud chunega (QR dikhao)",
                    "Paas mein - sabse tez (5 GHz)",
                    "20 m tak - dheema, door tak (2.4 GHz)",
                    "Hotspot ya same Wi-Fi se",
                    "Bahut door - internet code se"
                )
            ) { _, which ->
                when (which) {
                    0 -> ensureNearby { startHosting(autoBand(), auto = true) }
                    1 -> ensureNearby { startHosting(WifiP2pConfig.GROUP_OWNER_BAND_5GHZ) }
                    2 -> ensureNearby { startHosting(WifiP2pConfig.GROUP_OWNER_BAND_2GHZ) }
                    3 -> startLanHosting()
                    else -> askCode()
                }
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
        val scan = IntentIntegrator(this)
        scan.setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
        scan.setPrompt("Bhejne wale phone ka QR scan karo")
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
        val holder = LinearLayout(this)
        holder.setPadding(dp(20), dp(8), dp(20), 0)
        holder.addView(
            input,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )
        say("Lene wale phone pe: Receive files → 'Bahut door - internet code se'. Jo code aaye woh yahan daalo.")
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
        if (requestCode == REQ_SAVE_TREE) {
            val t = data?.data
            if (resultCode == RESULT_OK && t != null) {
                Storage.setTree(this, t)
                say("Ab files yahan save hongi: ${Storage.label(this)}")
            }
            return
        }
        if (requestCode == REQ_FOLDER) {
            val t = data?.data
            if (resultCode != RESULT_OK || t == null) {
                addingMore = false
                say("Koi folder nahi chuna.")
                return
            }
            say("Folder ki files gin raha hoon...")
            thread(name = "ds-folder") {
                val items = try {
                    listFolder(t)
                } catch (e: Throwable) {
                    emptyList()
                }
                ui.post {
                    if (items.isEmpty()) {
                        addingMore = false
                        say("Is folder mein koi file nahi mili.")
                    } else if (addingMore) {
                        appendItems(items)
                    } else {
                        pickedItems = items
                        pickedUris = items.map { it.uri }
                        say("Folder mein ${items.size} file hain (${size(items.sumOf { it.size })}). Ab batao lene wale kahan hain.")
                        chooseSendWay()
                    }
                }
            }
            return
        }
        if (requestCode == REQ_PICK) {
            if (resultCode != RESULT_OK || data == null) {
                addingMore = false
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
            if (addingMore) {
                thread(name = "ds-describe") {
                    val items = Transfer.describe(this, list)
                    ui.post { appendItems(items) }
                }
                return
            }
            pickedItems = null
            pickedUris = list
            say("${list.size} file chuni. Ab batao lene wale kahan hain.")
            chooseSendWay()
            return
        }
        val result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        if (result != null) {
            val contents = result.contents
            if (contents == null) say("QR scan nahi hua. Camera ko bhejne wale phone ke QR ke saamne rakho aur dobara try karo.")
            else startJoining(contents)
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

    private fun wifiQr(ssid: String, pass: String) = "WIFI:T:WPA;S:$ssid;P:$pass;;"

    /** Reads a standard Wi-Fi QR ("WIFI:T:WPA;S:..;P:..;;"). Returns name and password. */
    private fun parseWifiQr(text: String): Pair<String, String>? {
        if (!text.startsWith("WIFI:")) return null
        var ssid: String? = null
        var pass: String? = null
        val body = text.substring(5)
        val fields = ArrayList<String>()
        val cur = StringBuilder()
        var i = 0
        while (i < body.length) {
            val ch = body[i]
            if (ch == '\\' && i + 1 < body.length) {
                cur.append(body[i + 1])
                i += 2
                continue
            }
            if (ch == ';') {
                fields.add(cur.toString())
                cur.setLength(0)
            } else {
                cur.append(ch)
            }
            i++
        }
        if (cur.isNotEmpty()) fields.add(cur.toString())
        for (f in fields) {
            if (f.startsWith("S:")) ssid = f.substring(2)
            if (f.startsWith("P:")) pass = f.substring(2)
        }
        val s = ssid ?: return null
        val p = pass ?: return null
        return Pair(s, p)
    }

    private fun showQr(content: String) {
        try {
            val bmp = BarcodeEncoder().encodeBitmap(content, BarcodeFormat.QR_CODE, 700, 700)
            qrView.setImageBitmap(bmp)
            qrView.visibility = View.VISIBLE
        } catch (e: Exception) {
            say(appFault("QR nahi ban paya (${e.message})."))
        }
    }

    // ---------------------------------------------- nearby: sender is the host

    private fun startHosting(band: Int, receiveOnly: Boolean = false, auto: Boolean = false) {
        val uris = if (receiveOnly) emptyList() else pickedUris
        if (!receiveOnly && uris.isEmpty()) return
        val gen = beginSession()
        hosting = true
        hostReceiveOnly = receiveOnly
        autoMode = auto
        hostBand = band
        showTip(phoneCheck(false))
        say("Link bana raha hoon...")
        thread(name = "ds-describe") {
            val items = if (receiveOnly) emptyList() else chosenItems(uris)
            ui.post {
                if (gen != generation) return@post
                setBatch(items)
                val (ssid, pass) = myCreds()
                val routerFreq = routerFrequency()
                // On the router's own 5 GHz channel the Wi-Fi chip does not have to keep hopping
                // between two channels, which can halve the speed.
                val sameChannel = band == WifiP2pConfig.GROUP_OWNER_BAND_5GHZ && routerFreq in 5000..5899
                if (sameChannel) log("phone is on a router at $routerFreq MHz, making the link on the same channel")
                leaveGroup {
                    if (gen == generation) createGroup(gen, ssid, pass, band, if (sameChannel) routerFreq else 0)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun createGroup(gen: Int, ssid: String, pass: String, band: Int, freq: Int = 0) {
        val m = p2p
        val c = channel
        if (m == null || c == null) return
        val b = WifiP2pConfig.Builder()
            .setNetworkName(ssid)
            .setPassphrase(pass)
            .enablePersistentMode(false)
        if (freq > 0) b.setGroupOperatingFrequency(freq) else b.setGroupOperatingBand(band)
        val cfg = b.build()
        m.createGroup(c, cfg, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                if (gen != generation) return
                log("direct link created, band request $band")
                onHostReady(gen, ssid, pass)
            }

            override fun onFailure(reason: Int) {
                if (gen != generation) return
                log("create link failed, band request $band, freq request $freq, code $reason")
                if (freq > 0) {
                    // Channel not allowed for a link on this phone; fall back to just the band.
                    ui.postDelayed({ if (gen == generation) createGroup(gen, ssid, pass, band) }, 400)
                } else if (band != WifiP2pConfig.GROUP_OWNER_BAND_AUTO) {
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

    private fun onHostReady(gen: Int, ssid: String, pass: String) {
        if (serversUp) {
            // Link made again (auto mode switched band): servers are still running.
            showLinkBand(gen, 0)
            return
        }
        hostSsid = ssid
        hostPass = pass
        showingUrlQr = false
        showQr(wifiQr(ssid, pass))
        altView.text = "iPhone ya laptop pe lena hai? Yahan dabao"
        altView.visibility = View.VISIBLE
        btnCancel.text = "Band karo"
        if (!hostReceiveOnly) btnAddMore.visibility = View.VISIBLE
        if (autoMode) log("auto mode: link on band $hostBand")
        if (hostReceiveOnly) {
            say("Ready! iPhone: 1) camera se yeh QR scan karke Wi-Fi Join karo. 2) Neeche 'iPhone ya laptop' dabao aur doosra QR scan karo. 3) Safari ke page pe 'Is phone ko file bhejo' se file chuno.")
        } else {
            say("Ready! Lene wale phone pe DropSwift → Receive → yeh QR scan karo. 3 ya zyada phone bhi ek-ek karke scan kar sakte hain.")
        }
        showLinkBand(gen, 0)
        startServers(gen, pass)
    }

    /** Addresses of this phone on its hotspot or on the Wi-Fi it has joined. */
    private fun lanAddresses(): List<String> {
        val out = ArrayList<String>()
        try {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback) continue
                val n = ni.name.lowercase(Locale.US)
                if (n.startsWith("p2p") || n.startsWith("rmnet") || n.startsWith("ccmni") ||
                    n.startsWith("dummy") || n.startsWith("tun")
                ) continue
                for (a in ni.inetAddresses) {
                    if (a is Inet4Address && a.isSiteLocalAddress) a.hostAddress?.let { out.add(it) }
                }
            }
        } catch (_: Throwable) {
        }
        return out
    }

    /** Sender side for hotspot / same Wi-Fi: no Wi-Fi Direct link, the network already exists. */
    private fun startLanHosting() {
        val uris = pickedUris
        if (uris.isEmpty()) return
        val ips = lanAddresses()
        if (ips.isEmpty()) {
            say(phoneFault("yeh phone kisi Wi-Fi pe nahi hai aur hotspot bhi band hai. Phone ka hotspot on karo (hotspot settings mein band 5 GHz chuno), ya dono phone ek hi Wi-Fi se jodo. Phir dobara try karo."))
            return
        }
        val gen = beginSession()
        hosting = true
        hostLan = true
        showTip(phoneCheck(false))
        say("Tayyar kar raha hoon...")
        thread(name = "ds-describe") {
            val items = chosenItems(uris)
            ui.post {
                if (gen != generation) return@post
                setBatch(items)
                val secret = randomText(12)
                hostPass = secret
                hostSsid = ""
                hostAddress = ips[0]
                showingUrlQr = false
                lanQr = "DSLAN1\n${ips.joinToString(",")}\n${Transfer.PORT}\n$secret"
                showQr(lanQr)
                altView.text = "Laptop ya iPhone (isi network pe)? Yahan dabao"
                altView.visibility = View.VISIBLE
                btnCancel.text = "Band karo"
                btnAddMore.visibility = View.VISIBLE
                linkView.text = "Hotspot / same Wi-Fi mode: ${ips.joinToString(", ")}"
                log("lan host on ${ips.joinToString(",")}")
                say("Ready! Lene wala phone pehle isi hotspot ya Wi-Fi se juda ho. Phir uspe DropSwift → Receive → 'Paas mein' → yeh QR scan karo.")
                startServers(gen, secret)
            }
        }
    }

    private fun startServers(gen: Int, pass: String) {
        serversUp = true
        holdWifi()
        startHostTicker(gen)

        thread(name = "ds-host") {
            try {
                val server = ServerSocket()
                track(server)
                server.reuseAddress = true
                server.receiveBufferSize = Transfer.SOCKET_BUFFER
                server.bind(InetSocketAddress(Transfer.PORT))
                Transfer.serve(server, pass, { c -> track(c) }) { id, name, sockets ->
                    if (gen == generation) servePeer(gen, id, name, sockets)
                }
            } catch (e: Throwable) {
                log("host failed: ${e.message}")
                ui.post { if (gen == generation) stopEverything(diagnose(e)) }
            }
        }
        try {
            val w = WebShare(applicationContext, hostItems, pass) { name, uri ->
                webSaved.add(Pair(name, uri))
                log("browser upload saved: $name")
                ui.post {
                    if (gen == generation) {
                        showReceived(synchronized(webSaved) { ArrayList(webSaved) })
                        say("$name aa gayi (browser se). Download/DropSwift mein save hai.")
                    }
                }
            }
            w.start { c -> track(c) }
            track(w)
            web = w
        } catch (e: Throwable) {
            log("browser share failed: ${e.message}")
        }
    }

    /** Runs on its own thread for each receiver that joins (or joins again after a break, or checks for new files). */
    private fun servePeer(gen: Int, id: Long, name: String, sockets: List<Socket>) {
        val peer = synchronized(peers) {
            peers.firstOrNull { it.id == id } ?: Peer(id, name).also { peers.add(it) }
        }
        val b = batch
        val opt = Transfer.SendOptions(
            b.id, "${Build.MANUFACTURER} ${Build.MODEL}", hostPass, flag("compress"), flag("encrypt")
        )
        val p = Progress()
        val my = synchronized(peer) {
            peer.attempt++
            peer.attempt
        }
        val wasOk = peer.ok
        var real = false
        val t0 = SystemClock.elapsedRealtime()
        var ok = false
        var status = ""
        try {
            Transfer.send(this, sockets, b.items, opt, p) { newBytes ->
                // A phone that already has everything is only checking for new files: stay quiet.
                if (newBytes > 0 || !wasOk) {
                    real = true
                    synchronized(peer) {
                        if (peer.attempt == my) {
                            peer.p = p
                            peer.done = false
                            peer.ok = false
                            peer.status = "bhej raha hoon"
                        }
                    }
                    val again = my > 1
                    log("receiver ${if (again) "again" else "joined"}: $name, ${size(newBytes)} to send")
                    ui.post {
                        if (gen == generation) say(
                            if (wasOk) "$name ko nayi files bhej raha hoon..."
                            else if (again) "$name wapas juda. Jahan ruka tha wahin se bhej raha hoon..."
                            else "$name jud gaya, bhej raha hoon... Aur phone bhi scan kar sakte hain."
                        )
                    }
                }
            }
            ok = true
            status = "pahunch gayi, 100% match"
        } catch (e: Throwable) {
            status = diagnose(e)
            if (real) log("receiver $name stopped: ${e.javaClass.simpleName}: ${e.message}")
        }
        if (!real) return
        // A newer try from the same phone has taken over; let that one report.
        if (peer.attempt != my) return
        peer.ok = ok
        peer.status = status
        peer.done = true
        if (!ok && peerBreaks.incrementAndGet() >= 2) ui.post { if (gen == generation) autoSwitchTo24(gen) }
        val secs = (SystemClock.elapsedRealtime() - t0) / 1000.0
        val dataSecs = p.dataNanos / 1e9
        val sent = p.done.get() - p.resumedBytes
        val avg = if (dataSecs > 0) sent / 1048576.0 / dataSecs else 0.0
        log(String.format(
            Locale.US, "result %s: %s | %.1f MB/s while sending, %s sent (%s on the wire) in %.1f s, %s already there, %.1f s total with check | per connection: Wi-Fi %.1f s, storage %.1f s",
            name, if (ok) "ok" else "stopped", avg, size(sent), size(p.wireBytes.get()), dataSecs, size(p.resumedBytes), secs,
            p.netNanos.get() / 1e9 / p.streams, p.diskNanos.get() / 1e9 / p.streams
        ))
        History.add(
            applicationContext,
            History.Entry(System.currentTimeMillis(), false, name, b.items.size, p.total, avg, ok, status.take(120), "")
        )
        ui.post { if (gen == generation) onPeerFinished() }
    }

    private fun onPeerFinished() {
        val list = synchronized(peers) { ArrayList(peers) }
        if (list.isEmpty() || list.any { !it.done }) return
        val ok = list.count { it.ok }
        if (ok == list.size) {
            say("Ho gaya! $ok phone ko bhej diya, sab mein 100% match. Aur phone ho toh QR scan karwao, warna Band karo dabao.")
        } else {
            val bad = list.first { !it.ok }
            say("${ok}/${list.size} phone ko pahunchi. ${bad.name} ruka hua hai: ${bad.status} Woh phone apne-aap wapas judne ki koshish karega; QR khula rakho.")
        }
    }

    private fun toggleBrowserQr() {
        if (!hosting) return
        showingUrlQr = !showingUrlQr
        val url = "http://${if (hostLan) hostAddress else WebShare.groupAddress()}:${WebShare.PORT}/$hostPass/"
        if (hostLan) {
            if (showingUrlQr) {
                showQr(url)
                altView.text = "Wapas DropSwift wala QR dikhao"
                say("Laptop ya iPhone isi hotspot/Wi-Fi pe ho toh yeh QR scan karo, ya browser mein $url kholo.")
            } else {
                showQr(lanQr)
                altView.text = "Laptop ya iPhone (isi network pe)? Yahan dabao"
                say("Lene wala phone isi network pe ho, phir DropSwift se yeh QR scan kare.")
            }
            return
        }
        if (showingUrlQr) {
            showQr(url)
            altView.text = "Wapas Wi-Fi wala QR dikhao"
            say("iPhone: 1) Pehle 'wapas' dabake Wi-Fi wala QR camera se scan karo, Join dabao. 2) Phir yeh QR scan karo, Safari khulega, file pe tap karo.\nLaptop: Wi-Fi list mein \"$hostSsid\" chuno, password \"$hostPass\", phir browser mein $url kholo.")
        } else {
            showQr(wifiQr(hostSsid, hostPass))
            altView.text = "iPhone ya laptop pe lena hai? Yahan dabao"
            say("Yeh QR DropSwift wale phone aur iPhone camera dono scan kar sakte hain.")
        }
    }

    private fun startHostTicker(gen: Int) {
        startedAt = SystemClock.elapsedRealtime()
        lastTickAt = startedAt
        lastTickBytes = 0
        ui.postDelayed(object : Runnable {
            override fun run() {
                if (gen != generation || !hosting) return
                hostTick()
                ui.postDelayed(this, 400)
            }
        }, 400)
    }

    private fun hostTick() {
        val list = synchronized(peers) { ArrayList(peers) }
        val w = web
        val now = SystemClock.elapsedRealtime()
        val moved = list.sumOf { it.p.done.get() } + (w?.bytes?.get() ?: 0L) + (w?.upBytes?.get() ?: 0L)
        val dt = (now - lastTickAt) / 1000.0
        if (dt > 0 && (list.any { !it.done } || (w?.active?.get() ?: 0) > 0 || (w?.upActive?.get() ?: 0) > 0)) {
            val inst = maxOf(0.0, (moved - lastTickBytes) / 1048576.0 / dt)
            emaSpeed = if (emaSpeed <= 0) inst else emaSpeed * 0.8 + inst * 0.2
            speedView.text = String.format(Locale.US, "%.1f MB/s", inst)
        }
        lastTickAt = now
        lastTickBytes = moved
        val lines = ArrayList<String>()
        for (peer in list) {
            val total = peer.p.total
            val pct = if (total > 0) peer.p.done.get() * 100 / total else 0
            lines.add(
                if (peer.done) "${peer.name}: ${if (peer.ok) "pahunch gayi ✓ 100% Original Verified" else "ruka hua (wapas jud sakta hai)"}"
                else if (total > 0 && pct >= 100) "${peer.name}: woh jaanch kar raha hai..."
                else "${peer.name}: $pct%"
            )
        }
        if (w != null && (w.active.get() > 0 || w.finished.get() > 0)) {
            lines.add("${w.lastClient.ifEmpty { "Browser" }}: ${w.active.get()} download chal rahe, ${w.finished.get()} poore, ${size(w.bytes.get())}")
        }
        if (w != null && (w.upActive.get() > 0 || w.uploaded.get() > 0)) {
            lines.add("Browser se aayi: ${w.uploaded.get()} file, ${size(w.upBytes.get())}")
        }
        detailView.text = if (lines.isEmpty()) "Abhi koi juda nahi" else lines.joinToString("\n")
        val running = list.filter { !it.done }
        if (running.isNotEmpty()) {
            val total = running.sumOf { it.p.total }
            val done = running.sumOf { it.p.done.get() }
            bar.visibility = View.VISIBLE
            if (total > 0) bar.progress = ((done * 1000) / total).toInt().coerceIn(0, 1000)
            val left = total - done
            val eta = if (emaSpeed > 0.3 && left > 0) etaText(left / 1048576.0 / emaSpeed) else ""
            if (eta.isNotEmpty()) detailView.text = detailView.text.toString() + "\nBaaki: $eta"
            val pct = if (total > 0) (done * 100 / total).toInt() else 0
            if (emaSpeed > 0) showQuality(emaSpeed / running.size)
            notifyMaybe("Bhej raha hoon: ${running.size} phone, $pct%" + (if (eta.isNotEmpty()) ", $eta baaki" else ""), pct)
        } else {
            notifyMaybe("QR tayyar hai, lene wale ka intezaar", -1)
        }
    }

    /** Strong / Medium / Weak. Signal strength when on a hotspot or router; otherwise judged from speed. */
    private fun showQuality(speed: Double) {
        var rssi = 0
        if (lanJoin || hostLan) {
            try {
                val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                rssi = wifi.connectionInfo?.rssi ?: 0
            } catch (_: Throwable) {
            }
        }
        val level: Int
        val how: String
        if (rssi in -100..-1) {
            level = if (rssi >= -60) 2 else if (rssi >= -70) 1 else 0
            how = "$rssi dBm"
        } else {
            val fast = linkFreq >= 4900 || (linkFreq == 0 && !hostLan)
            level = if (speed >= (if (fast) 40.0 else 5.0)) 2 else if (speed >= (if (fast) 10.0 else 2.0)) 1 else 0
            how = "speed se andaza"
        }
        val label = arrayOf("Weak", "Medium", "Strong")[level]
        val color = arrayOf("#FF7A7A", "#FFCA69", "#00E68A")[level]
        qualityView.text = "Link: $label ($how)"
        qualityView.setTextColor(Color.parseColor(color))
    }

    private fun notifyMaybe(text: String, pct: Int) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastNotifyAt < 1000) return
        lastNotifyAt = now
        TransferService.update(applicationContext, text, pct)
    }

    private fun etaText(secs: Double): String {
        val s = secs.toLong()
        return when {
            s < 1 -> ""
            s < 60 -> "$s sec"
            s < 3600 -> "${s / 60} min ${s % 60} sec"
            else -> "${s / 3600} ghante ${(s % 3600) / 60} min"
        }
    }

    // ---------------------------------------------- nearby: receiver joins

    @SuppressLint("MissingPermission")
    private fun startJoining(qr: String) {
        val text = qr.trim()
        if (text.startsWith("DSWIFT1")) {
            say(appFault("bhejne wale phone pe purana DropSwift hai. Dono phone pe naya version install karo."))
            return
        }
        if (text.startsWith("DSLAN1")) {
            joinLan(text)
            return
        }
        val wifi = parseWifiQr(text)
        if (wifi == null || !wifi.first.startsWith("DIRECT-DS-")) {
            say("Yeh DropSwift ka QR nahi hai. Bhejne wale phone pe Send files → file chuno → 'Paas mein'. Jo QR aata hai woh scan karo.")
            return
        }
        joinNearby(wifi.first, wifi.second)
    }

    /** Joins a sender's Wi-Fi Direct link (from a QR or from the recent list). */
    @SuppressLint("MissingPermission")
    private fun joinNearby(ssid: String, pass: String) {
        val m = p2p
        val c = channel
        if (m == null || c == null) return
        val gen = beginSession()
        joinSsid = ssid
        joinPass = pass
        showTip(phoneCheck(true))
        say("Bhejne wale phone se jud raha hoon...")

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
                            stopEverything(netFault("bhejne wale phone se jud nahi paya. Dono phone paas rakho, uska QR khula rakho, aur dobara scan karo."))
                        } else {
                            showLinkBand(gen, 0)
                            runJoin(gen, owner, ssid, pass)
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

    /** Receiver side for hotspot / same Wi-Fi. */
    private fun joinLan(text: String) {
        val parts = text.split("\n")
        if (parts.size < 4) {
            say("QR theek se nahi padha gaya. Dobara scan karo.")
            return
        }
        val ips = parts[1].split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val port = parts[2].trim().toIntOrNull() ?: Transfer.PORT
        val secret = parts[3].trim()
        val gen = beginSession()
        lanJoin = true
        historyWho = "Hotspot / same Wi-Fi"
        showTip(phoneCheck(true))
        linkView.text = "Hotspot / same Wi-Fi mode"
        say("Bhejne wale phone ko network pe dhoond raha hoon...")
        receiveLoop(gen, port, secret, null, "lan sender not found") { probeLan(gen, ips, port) }
    }

    /** Finds the sender on the hotspot / Wi-Fi. Runs on a worker thread. */
    private fun probeLan(gen: Int, ips: List<String>, port: Int): InetAddress? {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < deadline && gen == generation) {
            for (ip in ips) {
                val probe = Socket()
                try {
                    probe.connect(InetSocketAddress(ip, port), 1200)
                    try {
                        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                        val info = wifi.connectionInfo
                        val line = "Network: ${info.frequency} MHz, link ${info.linkSpeed} Mbps"
                        log(line)
                        ui.post { if (gen == generation) linkView.text = line }
                    } catch (_: Throwable) {
                    }
                    return InetAddress.getByName(ip)
                } catch (_: Exception) {
                } finally {
                    try {
                        probe.close()
                    } catch (_: Exception) {
                    }
                }
            }
            Thread.sleep(500)
        }
        return null
    }

    /** Joins the sender's Wi-Fi Direct link again if it dropped. Runs on a worker thread. */
    @SuppressLint("MissingPermission")
    private fun p2pOwnerBlocking(gen: Int, ssid: String, pass: String): InetAddress? {
        val latch = CountDownLatch(1)
        var result: InetAddress? = null
        ui.post {
            val m = p2p
            val c = channel
            if (m == null || c == null || gen != generation) {
                latch.countDown()
                return@post
            }
            m.requestConnectionInfo(c) { info ->
                val owner = info?.groupOwnerAddress
                if (info != null && info.groupFormed && owner != null) {
                    result = owner
                    latch.countDown()
                } else {
                    try {
                        val cfg = WifiP2pConfig.Builder().setNetworkName(ssid).setPassphrase(pass).build()
                        m.connect(c, cfg, object : WifiP2pManager.ActionListener {
                            override fun onSuccess() {
                                waitForOwner(gen, SystemClock.elapsedRealtime() + 30_000) { o ->
                                    result = o
                                    latch.countDown()
                                }
                            }

                            override fun onFailure(reason: Int) {
                                log("rejoin link failed, code $reason")
                                latch.countDown()
                            }
                        })
                    } catch (_: Throwable) {
                        latch.countDown()
                    }
                }
            }
        }
        latch.await(40, TimeUnit.SECONDS)
        return result
    }

    private fun trackAttempt(c: Closeable) {
        synchronized(attemptThings) { attemptThings.add(c) }
    }

    /** Closes only the current try's connections (used by Pause and after a failed try). */
    private fun closeAttempt() {
        val copy: List<Closeable>
        synchronized(attemptThings) {
            copy = ArrayList(attemptThings)
            attemptThings.clear()
        }
        thread(name = "ds-close-try") {
            for (c in copy) {
                try {
                    c.close()
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun retryable(e: Throwable): Boolean {
        val m = (e.message ?: "").lowercase(Locale.US)
        if (e is SecurityException) return false
        return !(m.contains("cancel") || m.contains("different dropswift version") || m.contains("old or foreign") ||
            m.contains("enospc") || m.contains("no space") || m.contains("cannot create") || m.contains("permission"))
    }

    private fun togglePause() {
        if (!active || hosting) return
        if (!paused) {
            paused = true
            btnPause.text = "Resume"
            say("Pause kar diya. Jo aa chuka hai woh safe hai. Resume dabao toh wahin se shuru hoga.")
            closeAttempt()
        } else {
            paused = false
            btnPause.text = "Pause"
            say("Resume kar raha hoon...")
        }
    }

    /** Asks what to do with files already on the phone. Called on a worker thread; waits up to 25 s, then skips. */
    private fun askDuplicates(names: List<String>): Int {
        val latch = CountDownLatch(1)
        var answer = Transfer.DUP_SKIP
        ui.post {
            val shown = names.take(5).joinToString("\n") + (if (names.size > 5) "\n...aur ${names.size - 5}" else "")
            say("${names.size} file pehle se phone mein hain. Skip karun ya replace?")
            try {
                AlertDialog.Builder(this)
                    .setTitle("${names.size} file pehle se hain")
                    .setMessage("Same naam aur size:\n$shown\n\n25 second mein na chuna toh skip kar dunga.")
                    .setPositiveButton("Skip karo") { _, _ ->
                        answer = Transfer.DUP_SKIP
                        latch.countDown()
                    }
                    .setNegativeButton("Replace karo") { _, _ ->
                        answer = Transfer.DUP_REPLACE
                        latch.countDown()
                    }
                    .setNeutralButton("Dono rakho") { _, _ ->
                        answer = Transfer.DUP_KEEP_BOTH
                        latch.countDown()
                    }
                    .setOnCancelListener { latch.countDown() }
                    .show()
            } catch (_: Throwable) {
                latch.countDown()
            }
        }
        latch.await(25, TimeUnit.SECONDS)
        return answer
    }

    /**
     * Receiver: join, receive, and if the link breaks, join again and continue where it stopped.
     * After a batch it keeps checking for files the sender adds (queue) until the sender stops.
     * A sender that is never found fails straight away.
     */
    private fun receiveLoop(
        gen: Int, port: Int, secret: String, firstOwner: InetAddress?, notFound: String,
        reacquire: () -> InetAddress?
    ) {
        val device = "${Build.MANUFACTURER} ${Build.MODEL}"
        receiverId = SecureRandom().nextLong()
        historyIn = true
        thread(name = "ds-join") {
            var owner = firstOwner
            var tries = 0
            var started = false
            var waiting = false
            var batches = 0
            var pollFails = 0
            var endedQuietly = false
            var error: Throwable? = null
            while (gen == generation) {
                try {
                    val o = owner ?: reacquire() ?: throw java.io.IOException(notFound)
                    owner = o
                    val sockets = Transfer.join(o, port, secret, device, receiverId) { c ->
                        track(c)
                        trackAttempt(c)
                    }
                    pollFails = 0
                    if (!started) {
                        started = true
                        ui.post {
                            if (gen == generation) {
                                onTransferStarted("File aa rahi hai...")
                                btnPause.visibility = View.VISIBLE
                            }
                        }
                    } else if (!waiting && tries > 0) {
                        ui.post { if (gen == generation) say("Wapas jud gaya. Jahan ruka tha wahin se le raha hoon...") }
                    }
                    val n = Transfer.receive(
                        this, sockets, progress, resume, secret, { names -> askDuplicates(names) }
                    ) {
                        if (waiting) {
                            waiting = false
                            ui.post {
                                if (gen == generation) {
                                    onTransferStarted("Nayi files aa rahi hain...")
                                    doneBox.visibility = View.GONE
                                    btnPause.visibility = View.VISIBLE
                                }
                            }
                        }
                    }
                    tries = 0
                    if (n > 0 || batches == 0) {
                        batches++
                        ui.post { if (gen == generation) onBatchDone("Download/DropSwift mein save ho gayi", true) }
                    }
                    waiting = true
                    Thread.sleep(4000)
                } catch (e: Throwable) {
                    closeAttempt()
                    if (gen != generation) return@thread
                    if (paused) {
                        while (paused && gen == generation) Thread.sleep(300)
                        if (gen != generation) return@thread
                        tries = 0
                        continue
                    }
                    if (waiting) {
                        // Just checking for new files and the sender is gone: it has stopped sharing.
                        pollFails++
                        if (pollFails >= 4) {
                            endedQuietly = true
                            break
                        }
                        Thread.sleep(3000)
                        owner = null
                        continue
                    }
                    if (!started || !retryable(e) || tries >= MAX_RETRY) {
                        error = e
                        break
                    }
                    tries++
                    log("try $tries failed: ${e.javaClass.simpleName}: ${e.message}")
                    val msg = if (e is Transfer.Corrupt)
                        "Kuch file raste mein kharab hui. Sirf woh file dobara mangwa raha hoon... ($tries/$MAX_RETRY)"
                    else
                        "Link toot gaya. 3 second mein dobara jod raha hoon, jo aa chuka hai woh dobara nahi aayega... ($tries/$MAX_RETRY)"
                    ui.post { if (gen == generation) say(msg) }
                    Thread.sleep(3000)
                    owner = null
                }
            }
            if (endedQuietly) {
                ui.post { if (gen == generation) stopEverything("Bhejne wale ne share band kar diya. Jo files aayi thi woh sab save hain.") }
            } else {
                ui.post { if (gen == generation) onTransferEnded(error, "Download/DropSwift mein save ho gayi") }
            }
        }
    }

    private fun runJoin(gen: Int, owner: InetAddress, ssid: String, pass: String) {
        say("Jud gaya. File ka intezaar...")
        historyWho = "Paas wala phone"
        receiveLoop(gen, Transfer.PORT, pass, owner, "sender not reachable") { p2pOwnerBlocking(gen, ssid, pass) }
    }

    // --------------------------------------------------------- internet mode

    private fun startInternetReceive() {
        if (active) return
        val gen = beginSession()
        historyWho = "Internet"
        historyIn = true
        showTip(phoneCheck(true))
        val code = randomText(8)
        shareCode = code
        speedView.text = code.substring(0, 4) + " " + code.substring(4)
        linkView.text = "Internet mode. Code pe dabao toh share ho jayega."
        say("Yeh code bhejne wale ko batao. Woh Send files → file chun ke 'Bahut door - internet code se' chunega aur yeh code daalega. Is screen ko khula rakho.")
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
        historyWho = "Internet"
        historyIn = false
        showTip(phoneCheck(false))
        linkView.text = "Internet mode"
        say("Internet se doosre phone ko dhoond raha hoon... Dono phone pe internet on rakho.")
        val link = InternetLink(applicationContext) { line -> log(line) }
        track(link)
        thread(name = "ds-net-send") {
            var error: Throwable? = null
            try {
                val items = chosenItems(uris)
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
        hosting = false
        hostLan = false
        hostReceiveOnly = false
        webSaved.clear()
        peers.clear()
        web = null
        progress = Progress()
        shareCode = null
        speedView.text = ""
        detailView.text = ""
        linkView.text = ""
        altView.visibility = View.GONE
        receivedBox.removeAllViews()
        searchBox.removeAllViews()
        selected.clear()
        refreshSendSelected()
        showTip(null)
        bar.visibility = View.GONE
        qrView.visibility = View.GONE
        doneBox.visibility = View.GONE
        doneBox.removeAllViews()
        paused = false
        btnPause.text = "Pause"
        btnPause.visibility = View.GONE
        emaSpeed = 0.0
        heatTicks = 0
        heatTold = false
        historyWho = ""
        autoMode = false
        autoSwitched = false
        peerBreaks.set(0)
        serversUp = false
        linkFreq = 0
        joinSsid = ""
        joinPass = ""
        lanJoin = false
        addingMore = false
        qualityView.text = ""
        btnAddMore.visibility = View.GONE
        resume.received.clear()
        resume.senderName = ""
        setBusy(true)
        TransferService.silent = flag("silent")
        TransferService.start(applicationContext, "DropSwift tayyar ho raha hai")
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
                linkFreq = f
                val band = when {
                    f >= 5925 -> "6 GHz"
                    f >= 4900 -> "5 GHz"
                    else -> "2.4 GHz (slow band)"
                }
                var line = "Link: $band, $f MHz" + (if (group?.isGroupOwner == true) " (this phone made the link)" else "")
                try {
                    val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                    val info = wifi.connectionInfo
                    log("router check: networkId ${info?.networkId}, freq ${info?.frequency}, speed ${info?.linkSpeed} Mbps")
                    if (info != null && info.frequency > 0 && info.frequency != f) {
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

    /** Frequency of the router this phone is connected to, or 0 if none. */
    private fun routerFrequency(): Int {
        return try {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val f = wifi.connectionInfo?.frequency ?: 0
            if (f > 0) f else 0
        } catch (_: Throwable) {
            0
        }
    }

    private fun holdWifi() {
        if (wakeLock == null) {
            try {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dropswift:transfer")
                wl.setReferenceCounted(false)
                wl.acquire(6 * 60 * 60 * 1000L)
                wakeLock = wl
            } catch (_: Exception) {
            }
        }
        if (wifiLock != null) return
        try {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val lock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "dropswift")
            lock.setReferenceCounted(false)
            lock.acquire()
            wifiLock = lock
        } catch (_: Exception) {
        }
    }

    private fun onTransferStarted(word: String) {
        transferring = true
        holdWifi()
        qrView.visibility = View.GONE
        bar.visibility = View.VISIBLE
        bar.progress = 0
        say(word)
        startedAt = SystemClock.elapsedRealtime()
        lastTickAt = startedAt
        lastTickBytes = 0
        peakSpeed = 0.0
        slowTicks = 0
        slowTold = false
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
        val total = progress.total
        if (progress.phase == "verify") {
            val v = progress.verifyDone.get()
            speedView.text = "Jaanch..."
            if (total > 0) bar.progress = ((v * 1000) / total).toInt().coerceIn(0, 1000)
            detailView.text = "File 100% sahi hai ya nahi, SHA-256 se jaanch rahe hain\n${size(v)} / ${size(total)}"
            notifyMaybe("Jaanch rahe hain (SHA-256)...", if (total > 0) (v * 100 / total).toInt() else -1)
            return
        }
        val now = SystemClock.elapsedRealtime()
        val done = progress.done.get()
        val dt = (now - lastTickAt) / 1000.0
        if (dt > 0) {
            val mbps = maxOf(0.0, (done - lastTickBytes) / 1048576.0 / dt)
            speedView.text = if (paused) "Ruka hua" else String.format(Locale.US, "%.1f MB/s", mbps)
            if (!paused) {
                emaSpeed = if (emaSpeed <= 0) mbps else emaSpeed * 0.8 + mbps * 0.2
                watchSpeed(mbps)
                showQuality(emaSpeed)
            }
        }
        lastTickAt = now
        lastTickBytes = done
        if (total > 0) bar.progress = ((done * 1000) / total).toInt().coerceIn(0, 1000)
        val pct = if (total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else 0
        val left = total - done
        val eta = if (!paused && emaSpeed > 0.3 && left > 0) etaText(left / 1048576.0 / emaSpeed) else ""
        var line = "${progress.file}\n${size(done)} / ${size(total)}  ·  $pct%"
        if (eta.isNotEmpty()) line += "  ·  $eta baaki"
        if (progress.resumedBytes > 0) line += "\n(${size(progress.resumedBytes)} pehle se tha, dobara nahi aaya)"
        detailView.text = line
        notifyMaybe(
            (if (paused) "Ruka hua: " else if (historyIn) "Aa rahi hai: " else "Ja rahi hai: ") + "$pct%" +
                (if (eta.isNotEmpty()) ", $eta baaki" else ""),
            pct
        )
        heatTicks++
        if (heatTicks % 100 == 0 && !heatTold) {
            batteryTip()?.let {
                heatTold = true
                showTip("Swifty: $it")
            }
        }
    }

    private fun onTransferEnded(error: Throwable?, okWord: String) {
        if (error == null) {
            onBatchDone(okWord, false)
        } else {
            transferring = false
            log("failed: ${error.javaClass.simpleName}: ${error.message}")
            speedView.text = ""
            qualityView.text = ""
            val why = diagnose(error)
            say(why)
            if (!why.startsWith("Theek hai, rok diya")) {
                History.add(
                    applicationContext,
                    History.Entry(
                        System.currentTimeMillis(), historyIn, historyWho.ifEmpty { "Phone" },
                        progress.files.get(), progress.done.get(), 0.0, false, why.take(120), ""
                    )
                )
            }
        }
        // Give the last bytes a moment to leave before the link is dropped.
        val gen = generation
        ui.postDelayed({ if (gen == generation) finishSession() }, 1500)
    }

    /** One batch arrived (or was sent) completely. [keepOpen]: the receiver stays to get files added later. */
    private fun onBatchDone(okWord: String, keepOpen: Boolean) {
        val wasTransferring = transferring
        transferring = false
        val secs = if (wasTransferring) (SystemClock.elapsedRealtime() - startedAt) / 1000.0 else 0.0
        val done = progress.done.get()
        val sentNow = maxOf(0L, done - progress.resumedBytes)
        bar.progress = 1000
        val avg = if (secs > 0) sentNow / 1048576.0 / secs else 0.0
        speedView.text = String.format(Locale.US, "%.1f MB/s", avg)
        qualityView.text = ""
        val checked = progress.verified.get()
        val integrity = if (checked > 0) "✓ 100% Original Verified (SHA-256) – $checked file" else okWord
        val skippedNote = if (progress.resumedBytes > 0) "\n(${size(progress.resumedBytes)} pehle se tha, dobara nahi aaya)" else ""
        detailView.text = String.format(
            Locale.US, "%d file, %s, %.1f second mein (upar average speed, jaanch ka time mila ke)\n%s",
            progress.files.get(), size(done), secs, integrity
        ) + skippedNote
        val from = resume.senderName
        if (from.isNotEmpty() && historyIn) historyWho = from
        say(
            (if (checked > 0) "Ho gaya! $integrity. $okWord." else "Ho gaya! $okWord.") +
                (if (keepOpen) " Bhejne wala aur file jodega toh apne-aap aa jayengi. Ho gaya toh 'Band karo' dabao." else "")
        )
        val savedNow = ArrayList(progress.saved)
        showReceived(savedNow)
        showDoneButtons(savedNow)
        btnPause.visibility = View.GONE
        if (keepOpen) btnCancel.text = "Band karo"
        if (!flag("silent")) {
            try {
                window.decorView.performHapticFeedback(
                    if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.LONG_PRESS
                )
            } catch (_: Throwable) {
            }
        }
        if (!lanJoin && joinSsid.isNotEmpty()) rememberSender(joinSsid, joinPass, from)
        notifyMaybe(if (keepOpen) "Ho gaya. Aur files ka intezaar..." else "Ho gaya", 100)
        History.add(
            applicationContext,
            History.Entry(
                System.currentTimeMillis(), historyIn, historyWho.ifEmpty { "Phone" },
                maxOf(checked, savedNow.size), done, avg, true,
                if (checked > 0) "100% verified" else "", savedNow.firstOrNull()?.second?.toString() ?: ""
            )
        )
        log(String.format(
            Locale.US, "result: %.1f MB/s avg, %s new (%s on the wire) in %.1f s, %s already there | %d connections | per connection: Wi-Fi %.1f s, storage %.1f s | verified %d",
            avg, size(sentNow), size(progress.wireBytes.get()), secs, size(progress.resumedBytes), progress.streams,
            progress.netNanos.get() / 1e9 / progress.streams, progress.diskNanos.get() / 1e9 / progress.streams, checked
        ))
    }

    /** After a successful receive: Kholo / Share / Done. */
    private fun showDoneButtons(saved: List<Pair<String, Uri>>) {
        doneBox.removeAllViews()
        if (saved.isEmpty()) return
        fun add(label: String, bg: String, fg: String, onClick: () -> Unit) {
            val b = button(label, Color.parseColor(bg), Color.parseColor(fg))
            b.textSize = 15f
            b.setOnClickListener { onClick() }
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            lp.leftMargin = dp(3)
            lp.rightMargin = dp(3)
            doneBox.addView(b, lp)
        }
        add("Kholo", "#00E68A", "#04130C") { openFile(saved[0].second) }
        add("Share", "#263650", "#FFFFFF") { shareFiles(saved.map { it.second }) }
        add("Done", "#151C2E", "#C8D7E9") {
            doneBox.visibility = View.GONE
            receivedBox.removeAllViews()
            speedView.text = ""
            detailView.text = ""
            linkView.text = ""
            bar.visibility = View.GONE
            say("Aur kuch bhejna ya lena hai? Button dabao ya mujhse likh ke bolo.")
        }
        doneBox.visibility = View.VISIBLE
    }

    private fun shareFiles(uris: List<Uri>) {
        try {
            val i = if (uris.size == 1) {
                Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            }
            i.type = "*/*"
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(i, "Share karo"))
        } catch (_: Exception) {
            say(phoneFault("share karne wala koi app nahi mila."))
        }
    }

    private fun showHistory() {
        val list = History.list(applicationContext)
        if (list.isEmpty()) {
            say("Abhi tak koi transfer nahi hua. Pehla transfer karo, yahan dikhega.")
            return
        }
        val fmt = SimpleDateFormat("d MMM, h:mm a", Locale.US)
        val lines = list.map { e ->
            val dir = if (e.incoming) "Aayi" else "Bheji"
            val mark = if (e.ok) "✓" else "✗"
            val speed = if (e.ok && e.mbps > 0) String.format(Locale.US, ", %.1f MB/s", e.mbps) else ""
            "$mark ${fmt.format(Date(e.time))}  $dir · ${e.who}\n${e.files} file, ${size(e.bytes)}$speed" +
                (if (!e.ok && e.note.isNotEmpty()) "\n${e.note}" else "")
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Pichhle transfer")
            .setItems(lines) { _, which ->
                val u = list[which].uri
                if (u.isNotEmpty()) openFile(Uri.parse(u))
            }
            .setNegativeButton("Band karo", null)
            .setNeutralButton("Saaf karo") { _, _ ->
                History.clear(applicationContext)
                say("History saaf kar di.")
            }
            .show()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (active) {
            // Going back would stop the transfer; keep it running in the background instead.
            moveTaskToBack(true)
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    private fun finishSession() {
        generation++
        active = false
        transferring = false
        hosting = false
        shareCode = null
        web = null
        try {
            wifiLock?.release()
        } catch (_: Exception) {
        }
        wifiLock = null
        try {
            wakeLock?.release()
        } catch (_: Exception) {
        }
        wakeLock = null
        paused = false
        btnPause.visibility = View.GONE
        btnAddMore.visibility = View.GONE
        serversUp = false
        qualityView.text = ""
        TransferService.stop(applicationContext)
        // No half files left behind: anything not finished and checked is deleted.
        val leftover = resume.state
        resume.state = null
        if (leftover != null) {
            val ctx = applicationContext
            thread(name = "ds-cleanup") { leftover.discardUnfinished(ctx) }
        }
        closeAll()
        leaveGroup { }
        qrView.visibility = View.GONE
        altView.visibility = View.GONE
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
        TransferService.stop(applicationContext)
        resume.state?.let { st ->
            resume.state = null
            val ctx = applicationContext
            thread(name = "ds-cleanup") { st.discardUnfinished(ctx) }
        }
        try {
            wakeLock?.release()
        } catch (_: Exception) {
        }
        closeAll()
        leaveGroup { }
        super.onDestroy()
    }

    companion object {
        private const val REQ_PERMISSION = 11
        private const val REQ_PICK = 12
        private const val REQ_NOTIFY = 13
        private const val REQ_FOLDER = 14
        private const val REQ_SAVE_TREE = 15
        private const val MAX_RETRY = 5
    }
}
