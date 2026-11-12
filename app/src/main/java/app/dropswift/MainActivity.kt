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
import java.io.PrintWriter
import java.io.StringWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.Collections
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

    private var progress = Progress()
    private var startedAt = 0L
    private var lastTickAt = 0L
    private var lastTickBytes = 0L

    // Sender ("host") state: one link, many receivers.
    private class Peer(val name: String) {
        val p = Progress()
        @Volatile var status = "jud gaya"
        @Volatile var done = false
        @Volatile var ok = false
    }

    private val peers: MutableList<Peer> = Collections.synchronizedList(ArrayList())
    @Volatile private var hosting = false
    private var hostItems: List<Transfer.Item> = emptyList()
    private var hostSsid = ""
    private var hostPass = ""
    private var showingUrlQr = false
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
        btnCancel.setOnClickListener { stopEverything(if (hosting) "Band kar diya." else "Theek hai, rok diya.") }
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
        if (receiving) {
            try {
                val free = StatFs(Environment.getExternalStorageDirectory().path).availableBytes
                if (free < 2L * 1024 * 1024 * 1024) tips.add("Phone mein sirf ${size(free)} jagah bachi hai.")
            } catch (_: Throwable) {
            }
        }
        return if (tips.isEmpty()) null else "Swifty ki salah: " + tips.joinToString(" ")
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

        when {
            active -> say("Abhi ek kaam chal raha hai. Khatam hone do, ya Cancel/Band karo dabao.")
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
            else -> findFiles(raw)
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
            .setItems(arrayOf("Paas mein - uska QR scan karo", "Bahut door - internet code se")) { _, which ->
                if (which == 0) ensureNearby { openScanner() } else startInternetReceive()
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
                    "Paas mein - QR dikhao (sabse tez, kai phone ek saath)",
                    "20 m tak - QR dikhao (dheema, door tak)",
                    "Bahut door - internet code se"
                )
            ) { _, which ->
                when (which) {
                    0 -> ensureNearby { startHosting(WifiP2pConfig.GROUP_OWNER_BAND_5GHZ) }
                    1 -> ensureNearby { startHosting(WifiP2pConfig.GROUP_OWNER_BAND_2GHZ) }
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

    private fun startHosting(band: Int) {
        val uris = pickedUris
        if (uris.isEmpty()) return
        val gen = beginSession()
        hosting = true
        showTip(phoneCheck(false))
        say("Link bana raha hoon...")
        thread(name = "ds-describe") {
            val items = Transfer.describe(this, uris)
            ui.post {
                if (gen != generation) return@post
                hostItems = items
                val ssid = "DIRECT-DS-" + randomText(5)
                val pass = randomText(12)
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
        hostSsid = ssid
        hostPass = pass
        showingUrlQr = false
        showQr(wifiQr(ssid, pass))
        altView.text = "iPhone ya laptop pe lena hai? Yahan dabao"
        altView.visibility = View.VISIBLE
        btnCancel.text = "Band karo"
        say("Ready! Lene wale phone pe DropSwift → Receive → yeh QR scan karo. 3 ya zyada phone bhi ek-ek karke scan kar sakte hain.")
        showLinkBand(gen, 0)
        holdWifi()
        startHostTicker(gen)

        thread(name = "ds-host") {
            try {
                val server = ServerSocket()
                track(server)
                server.reuseAddress = true
                server.receiveBufferSize = Transfer.SOCKET_BUFFER
                server.bind(InetSocketAddress(Transfer.PORT))
                Transfer.serve(server, pass, { c -> track(c) }) { name, sockets ->
                    if (gen == generation) servePeer(gen, name, sockets)
                }
            } catch (e: Throwable) {
                log("host failed: ${e.message}")
                ui.post { if (gen == generation) stopEverything(diagnose(e)) }
            }
        }
        try {
            val w = WebShare(applicationContext, hostItems, pass)
            w.start { c -> track(c) }
            track(w)
            web = w
        } catch (e: Throwable) {
            log("browser share failed: ${e.message}")
        }
    }

    /** Runs on its own thread for each receiver that joins. */
    private fun servePeer(gen: Int, name: String, sockets: List<Socket>) {
        val peer = Peer(name)
        peers.add(peer)
        log("receiver joined: $name")
        ui.post { if (gen == generation) say("$name jud gaya, bhej raha hoon... Aur phone bhi scan kar sakte hain.") }
        val t0 = SystemClock.elapsedRealtime()
        try {
            peer.status = "bhej raha hoon"
            Transfer.send(this, sockets, hostItems, peer.p)
            peer.ok = true
            peer.status = "pahunch gayi, 100% match"
        } catch (e: Throwable) {
            peer.status = diagnose(e)
            log("receiver $name failed: ${e.javaClass.simpleName}: ${e.message}")
        }
        peer.done = true
        val secs = (SystemClock.elapsedRealtime() - t0) / 1000.0
        val dataSecs = peer.p.dataNanos / 1e9
        val avg = if (dataSecs > 0) peer.p.done.get() / 1048576.0 / dataSecs else 0.0
        log(String.format(
            Locale.US, "result %s: %s | %.1f MB/s while sending, %s in %.1f s (%.1f s total with receiver's check) | per connection: waited on Wi-Fi %.1f s, on storage %.1f s",
            name, if (peer.ok) "ok" else "failed", avg, size(peer.p.done.get()), dataSecs, secs,
            peer.p.netNanos.get() / 1e9 / peer.p.streams, peer.p.diskNanos.get() / 1e9 / peer.p.streams
        ))
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
            say("${ok}/${list.size} phone ko pahunchi. ${bad.name}: ${bad.status}")
        }
    }

    private fun toggleBrowserQr() {
        if (!hosting) return
        showingUrlQr = !showingUrlQr
        val url = "http://${WebShare.groupAddress()}:${WebShare.PORT}/$hostPass/"
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
        val moved = list.sumOf { it.p.done.get() } + (w?.bytes?.get() ?: 0L)
        val dt = (now - lastTickAt) / 1000.0
        if (dt > 0 && (list.any { !it.done } || (w?.active?.get() ?: 0) > 0)) {
            speedView.text = String.format(Locale.US, "%.1f MB/s", (moved - lastTickBytes) / 1048576.0 / dt)
        }
        lastTickAt = now
        lastTickBytes = moved
        val lines = ArrayList<String>()
        for (peer in list) {
            val total = peer.p.total
            val pct = if (total > 0) peer.p.done.get() * 100 / total else 0
            lines.add(
                if (peer.done) "${peer.name}: ${if (peer.ok) "pahunch gayi ✓ 100% match" else "nahi pahunchi ✗"}"
                else if (total > 0 && pct >= 100) "${peer.name}: woh jaanch kar raha hai..."
                else "${peer.name}: $pct%"
            )
        }
        if (w != null && (w.active.get() > 0 || w.finished.get() > 0)) {
            lines.add("${w.lastClient.ifEmpty { "Browser" }}: ${w.active.get()} download chal rahe, ${w.finished.get()} poore, ${size(w.bytes.get())}")
        }
        detailView.text = if (lines.isEmpty()) "Abhi koi juda nahi" else lines.joinToString("\n")
        val running = list.filter { !it.done }
        if (running.isNotEmpty()) {
            val total = running.sumOf { it.p.total }
            val done = running.sumOf { it.p.done.get() }
            bar.visibility = View.VISIBLE
            if (total > 0) bar.progress = ((done * 1000) / total).toInt().coerceIn(0, 1000)
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
        val wifi = parseWifiQr(text)
        if (wifi == null || !wifi.first.startsWith("DIRECT-DS-")) {
            say("Yeh DropSwift ka QR nahi hai. Bhejne wale phone pe Send files → file chuno → 'Paas mein'. Jo QR aata hai woh scan karo.")
            return
        }
        val ssid = wifi.first
        val pass = wifi.second
        val m = p2p
        val c = channel
        if (m == null || c == null) return
        val gen = beginSession()
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
                            runJoin(gen, owner, pass)
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

    private fun runJoin(gen: Int, owner: InetAddress, pass: String) {
        say("Jud gaya. File ka intezaar...")
        val device = "${Build.MANUFACTURER} ${Build.MODEL}"
        thread(name = "ds-join") {
            var error: Throwable? = null
            try {
                val sockets = Transfer.join(owner, Transfer.PORT, pass, device) { c -> track(c) }
                ui.post { if (gen == generation) onTransferStarted("File aa rahi hai...") }
                Transfer.receive(this, sockets, progress)
            } catch (e: Throwable) {
                error = e
            }
            ui.post { if (gen == generation) onTransferEnded(error, "Download/DropSwift mein save ho gayi") }
        }
    }

    // --------------------------------------------------------- internet mode

    private fun startInternetReceive() {
        if (active) return
        val gen = beginSession()
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
        hosting = false
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
            return
        }
        val now = SystemClock.elapsedRealtime()
        val done = progress.done.get()
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
            val checked = progress.verified.get()
            val integrity = if (checked > 0) "sab $checked file 100% sahi (SHA-256 match)" else okWord
            detailView.text = String.format(
                Locale.US, "%d file, %s, %.1f second mein (upar average speed, jaanch ka time mila ke)\n%s",
                progress.files.get(), size(done), secs, integrity
            )
            say(if (checked > 0) "Ho gaya! $integrity. $okWord." else "Ho gaya! $okWord.")
            showReceived(ArrayList(progress.saved))
            log(String.format(
                Locale.US, "result: %.1f MB/s avg, %s in %.1f s | %d connections | per connection: waited on Wi-Fi %.1f s, on storage %.1f s | verified %d",
                avg, size(done), secs, progress.streams,
                progress.netNanos.get() / 1e9 / progress.streams, progress.diskNanos.get() / 1e9 / progress.streams, checked
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
        hosting = false
        shareCode = null
        web = null
        try {
            wifiLock?.release()
        } catch (_: Exception) {
        }
        wifiLock = null
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
        closeAll()
        leaveGroup { }
        super.onDestroy()
    }

    companion object {
        private const val REQ_PERMISSION = 11
        private const val REQ_PICK = 12
    }
}
