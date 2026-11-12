package app.dropswift

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.google.zxing.BarcodeFormat
import com.google.zxing.integration.android.IntentIntegrator
import com.journeyapps.barcodescanner.BarcodeEncoder
import java.io.Closeable
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())

    private lateinit var statusView: TextView
    private lateinit var detailView: TextView
    private lateinit var speedView: TextView
    private lateinit var linkView: TextView
    private lateinit var logView: TextView
    private val logLines = ArrayList<String>()
    private lateinit var qrView: ImageView
    private lateinit var bar: ProgressBar
    private lateinit var btnSend: Button
    private lateinit var btnReceive: Button
    private lateinit var btnCancel: Button

    private var p2p: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null

    private var pendingAction = 0
    private var pickedUris: List<Uri> = emptyList()

    @Volatile private var generation = 0
    @Volatile private var active = false
    @Volatile private var transferring = false
    private val openThings = ArrayList<Closeable>()
    private var wifiLock: WifiManager.WifiLock? = null

    private var progress = Progress()
    private var startedAt = 0L
    private var lastTickAt = 0L
    private var lastTickBytes = 0L

    // ---------------------------------------------------------------- screen

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        installCrashCatcher()
        buildScreen()
        showLastCrash()
        p2p = getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        channel = p2p?.initialize(this, mainLooper, null)
        if (p2p == null || channel == null) {
            setStatus("This phone does not support Wi-Fi Direct")
            btnSend.isEnabled = false
            btnReceive.isEnabled = false
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

    private fun showLastCrash() {
        try {
            val file = File(filesDir, "last_crash.txt")
            if (file.exists()) {
                val textOfCrash = file.readText()
                file.delete()
                setStatus("App closed last time. Send a screenshot of the text below")
                log(textOfCrash)
            }
        } catch (_: Throwable) {
        }
    }

    private fun log(line: String) {
        ui.post {
            logLines.add(line)
            while (logLines.size > 40) logLines.removeAt(0)
            logView.text = logLines.joinToString("\n")
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

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
        b.setBackgroundColor(bg)
        return b
    }

    private fun buildScreen() {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.gravity = Gravity.CENTER_HORIZONTAL
        root.setPadding(dp(22), dp(40), dp(22), dp(24))
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

        val title = text(30f, Color.parseColor("#00E68A"), true)
        title.text = "DropSwift"
        root.addView(title, wide)

        val sub = text(14f, Color.parseColor("#9AA9BD"))
        sub.text = "Phone to phone, no internet needed"
        root.addView(sub, wide)
        root.addView(gap(26))

        btnSend = button("Send files", Color.parseColor("#00E68A"), Color.parseColor("#04130C"))
        btnSend.setOnClickListener { begin(ACTION_SEND) }
        root.addView(btnSend, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(58)))
        root.addView(gap(12))

        btnReceive = button("Receive files", Color.parseColor("#263650"), Color.WHITE)
        btnReceive.setOnClickListener { begin(ACTION_RECEIVE) }
        root.addView(btnReceive, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(58)))
        root.addView(gap(22))

        statusView = text(18f, Color.WHITE, true)
        statusView.text = "Ready"
        root.addView(statusView, wide)
        root.addView(gap(6))

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
        root.addView(speedView, wide)

        detailView = text(14f, Color.parseColor("#9AA9BD"))
        root.addView(detailView, wide)
        root.addView(gap(18))

        btnCancel = button("Cancel", Color.parseColor("#48232D"), Color.parseColor("#FFDCE0"))
        btnCancel.visibility = View.GONE
        btnCancel.setOnClickListener { stopEverything("Cancelled") }
        root.addView(btnCancel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(50)))

        root.addView(gap(18))
        logView = TextView(this)
        logView.textSize = 11f
        logView.setTextColor(Color.parseColor("#71829B"))
        logView.setTextIsSelectable(true)
        root.addView(logView, wide)
        log("DropSwift build " + (try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "?" })
            + ", Android " + Build.VERSION.RELEASE + ", " + Build.MANUFACTURER + " " + Build.MODEL)

        val scroll = ScrollView(this)
        scroll.setBackgroundColor(Color.parseColor("#0B0F19"))
        scroll.isFillViewport = true
        scroll.addView(root)
        setContentView(scroll)
    }

    private fun setStatus(s: String) {
        ui.post { statusView.text = s }
        log("> $s")
    }

    private fun setBusy(busy: Boolean) {
        btnSend.isEnabled = !busy
        btnReceive.isEnabled = !busy
        btnSend.alpha = if (busy) 0.4f else 1f
        btnReceive.alpha = if (busy) 0.4f else 1f
        btnCancel.visibility = if (busy) View.VISIBLE else View.GONE
    }

    // ------------------------------------------------------- start of a flow

    private fun begin(action: Int) {
        if (active) return
        pendingAction = action
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (!wifi.isWifiEnabled) {
            setStatus("Turn Wi-Fi on, then tap again")
            try {
                startActivity(Intent(Settings.Panel.ACTION_WIFI))
            } catch (_: Exception) {
            }
            return
        }
        val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES
        else Manifest.permission.ACCESS_FINE_LOCATION
        if (checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(perm), REQ_PERMISSION)
            return
        }
        proceed()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code != REQ_PERMISSION) return
        if (results.isNotEmpty() && results.all { it == PackageManager.PERMISSION_GRANTED }) proceed()
        else setStatus("Permission is needed to find the other phone")
    }

    private fun proceed() {
        if (pendingAction == ACTION_SEND) pickFiles() else startReceiving()
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
            setStatus("Could not open file picker: ${e.message}")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        log("back from screen $requestCode, result $resultCode")
        if (requestCode == REQ_PICK) {
            if (resultCode != RESULT_OK || data == null) {
                setStatus("No file chosen")
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
            setStatus("${list.size} file(s) chosen. Scan the receiver's QR")
            val scan = IntentIntegrator(this)
            scan.setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
            scan.setPrompt("Scan the QR code on the receiving phone")
            scan.setBeepEnabled(false)
            scan.setOrientationLocked(true)
            try {
                scan.initiateScan()
                log("scanner opened")
            } catch (e: Exception) {
                setStatus("Could not open scanner: ${e.message}")
            }
            return
        }
        val result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        if (result != null) {
            val contents = result.contents
            if (contents == null) setStatus("Scan closed without reading a QR. Point the camera at the QR on the receiving phone")
            else startSending(contents)
            return
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    // -------------------------------------------------------------- receiver

    private fun startReceiving() {
        val gen = beginSession()
        setStatus("Creating direct link...")
        val rnd = SecureRandom()
        val letters = "abcdefghijkmnpqrstuvwxyz23456789"
        fun rand(n: Int): String {
            val sb = StringBuilder()
            for (i in 0 until n) sb.append(letters[rnd.nextInt(letters.length)])
            return sb.toString()
        }
        val ssid = "DIRECT-DS-" + rand(5)
        val pass = rand(12)
        leaveGroup {
            if (gen == generation) createGroup(gen, ssid, pass, WifiP2pConfig.GROUP_OWNER_BAND_5GHZ)
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
                if (band == WifiP2pConfig.GROUP_OWNER_BAND_5GHZ) {
                    // Some phones refuse a forced 5 GHz link; let the phone choose.
                    ui.postDelayed({
                        if (gen == generation) createGroup(gen, ssid, pass, WifiP2pConfig.GROUP_OWNER_BAND_AUTO)
                    }, 400)
                } else {
                    stopEverything("Could not create direct link (code $reason). Turn Wi-Fi off and on, then try again")
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
            stopEverything("Could not draw QR: ${e.message}")
            return
        }
        setStatus("On the other phone: Send files, then scan this")
        showLinkBand(gen, 0)

        thread(name = "ds-receive") {
            var note = "Receive failed"
            var ok = false
            try {
                val server = ServerSocket()
                track(server)
                server.reuseAddress = true
                server.receiveBufferSize = Transfer.SOCKET_BUFFER
                server.bind(InetSocketAddress(Transfer.PORT))
                val socket = server.accept()
                track(socket)
                if (gen != generation) return@thread
                ui.post { if (gen == generation) onTransferStarted("Receiving") }
                Transfer.receive(this, socket, progress)
                ok = true
            } catch (e: Exception) {
                note = "Receive failed: ${e.message ?: e.javaClass.simpleName}"
            }
            ui.post { if (gen == generation) onTransferEnded(ok, note, "Saved in Download/DropSwift") }
        }
    }

    // ---------------------------------------------------------------- sender

    @SuppressLint("MissingPermission")
    private fun startSending(qr: String) {
        val parts = qr.trim().split("\n")
        if (parts.size < 4 || parts[0] != QR_PREFIX) {
            setStatus("That is not a DropSwift QR code")
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
        setStatus("Joining the other phone...")

        leaveGroup {
            if (gen != generation) return@leaveGroup
            val cfg = try {
                WifiP2pConfig.Builder().setNetworkName(ssid).setPassphrase(pass).build()
            } catch (e: Exception) {
                stopEverything("Bad QR code")
                return@leaveGroup
            }
            m.connect(c, cfg, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    if (gen != generation) return
                    log("join request accepted, waiting for link")
                    waitForOwner(gen, SystemClock.elapsedRealtime() + 40_000) { owner ->
                        if (gen != generation) return@waitForOwner
                        if (owner == null) {
                            stopEverything("Could not join. Keep both phones close and try again")
                        } else {
                            showLinkBand(gen, 0)
                            runSender(gen, owner, port, uris)
                        }
                    }
                }

                override fun onFailure(reason: Int) {
                    if (gen != generation) return
                    stopEverything("Could not join (code $reason). Turn Wi-Fi off and on, then try again")
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
        setStatus("Linked. Starting...")
        thread(name = "ds-send") {
            var note = "Send failed"
            var ok = false
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
                ui.post { if (gen == generation) onTransferStarted("Sending") }
                Transfer.send(this, s, items, progress)
                ok = true
            } catch (e: Exception) {
                note = "Send failed: ${e.message ?: e.javaClass.simpleName}"
            }
            ui.post { if (gen == generation) onTransferEnded(ok, note, "Sent") }
        }
    }

    // ------------------------------------------------------- shared plumbing

    private fun beginSession(): Int {
        generation++
        active = true
        transferring = false
        progress = Progress()
        speedView.text = ""
        detailView.text = ""
        linkView.text = ""
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
                } catch (_: Exception) {
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
        m.removeGroup(c, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                ui.postDelayed({ then() }, 700)
            }

            override fun onFailure(reason: Int) {
                then()
            }
        })
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
                    if (info != null && info.networkId != -1 && info.frequency > 0) {
                        line += "\nRouter: ${info.frequency} MHz" +
                            (if (info.frequency != f) " (different channel, this slows the transfer)" else "")
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
        setStatus(word)
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
        detailView.text = "${progress.file}\n${size(done)} of ${size(total)}"
    }

    private fun onTransferEnded(ok: Boolean, failNote: String, okWord: String) {
        transferring = false
        val secs = (SystemClock.elapsedRealtime() - startedAt) / 1000.0
        val done = progress.done.get()
        if (ok) {
            bar.progress = 1000
            val avg = if (secs > 0) done / 1048576.0 / secs else 0.0
            speedView.text = String.format(Locale.US, "%.1f MB/s", avg)
            detailView.text = String.format(
                Locale.US, "%d file(s), %s in %.1f s (average speed above)\n%s",
                progress.files, size(done), secs, okWord
            )
            setStatus("Done")
            log(String.format(
                Locale.US, "result: %.1f MB/s avg, %s in %.1f s | waited on Wi-Fi %.1f s, on storage %.1f s",
                avg, size(done), secs, progress.netNanos.get() / 1e9, progress.diskNanos.get() / 1e9
            ))
        } else {
            setStatus(failNote)
        }
        // Give the last bytes a moment to leave before the link is dropped.
        val gen = generation
        ui.postDelayed({ if (gen == generation) finishSession() }, 1500)
    }

    private fun finishSession() {
        generation++
        active = false
        transferring = false
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
        setStatus(message)
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
        private const val ACTION_SEND = 1
        private const val ACTION_RECEIVE = 2
        private const val REQ_PERMISSION = 11
        private const val REQ_PICK = 12
        private const val QR_PREFIX = "DSWIFT1"
    }
}
