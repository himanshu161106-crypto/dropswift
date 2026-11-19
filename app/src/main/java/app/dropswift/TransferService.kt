package app.dropswift

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * Keeps DropSwift alive while the screen is off or another app is open, and shows
 * the transfer in the notification bar. The work itself runs in the app's own threads.
 */
class TransferService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = build(this, intent?.getStringExtra(EXTRA_TEXT) ?: "DropSwift chal raha hai", -1)
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(ID, n)
            }
        } catch (_: Exception) {
            running = false
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    companion object {
        private const val ID = 4780
        private const val CHANNEL = "transfer"
        private const val CHANNEL_SILENT = "transfer_silent"

        /** Silent mode: the notification stays minimised with no sound or icon in the status bar. */
        @Volatile var silent = false
        private const val EXTRA_TEXT = "text"

        @Volatile var running = false

        /** Call only while the app is on screen (Android does not allow starting it from the background). */
        fun start(ctx: Context, text: String) {
            try {
                ensureChannel(ctx)
                ctx.startForegroundService(Intent(ctx, TransferService::class.java).putExtra(EXTRA_TEXT, text))
                running = true
            } catch (_: Exception) {
                running = false
            }
        }

        fun update(ctx: Context, text: String, pct: Int) {
            if (!running) return
            try {
                val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(ID, build(ctx, text, pct))
            } catch (_: Exception) {
            }
        }

        fun stop(ctx: Context) {
            running = false
            try {
                ctx.stopService(Intent(ctx, TransferService::class.java))
            } catch (_: Exception) {
            }
        }

        private fun ensureChannel(ctx: Context) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, "Transfers", NotificationManager.IMPORTANCE_LOW)
                )
            }
            if (nm.getNotificationChannel(CHANNEL_SILENT) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_SILENT, "Transfers (silent)", NotificationManager.IMPORTANCE_MIN)
                )
            }
        }

        private fun build(ctx: Context, text: String, pct: Int): Notification {
            // Same as tapping the app icon: brings the running DropSwift back, no new copy.
            val launch = Intent(ctx, MainActivity::class.java)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            val open = PendingIntent.getActivity(
                ctx, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val b = Notification.Builder(ctx, if (silent) CHANNEL_SILENT else CHANNEL)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle("DropSwift")
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(open)
            if (pct >= 0) b.setProgress(100, pct.coerceIn(0, 100), false)
            return b.build()
        }
    }
}
