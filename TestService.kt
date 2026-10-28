package com.smartnet.speedtest

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class TestService : Service() {

    companion object {
        const val ACTION_STOP = "com.smartnet.speedtest.STOP"
        private const val CHANNEL = "speedtest"
        private const val NID = 1
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            if (job?.isActive == true) job?.cancel() else stopSelf()
        } else if (intent != null) {
            val mode = Mode.valueOf(intent.getStringExtra("mode") ?: Mode.BOTH.name)
            startTest(mode, intent.getIntExtra("sec", 300))
        } else {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startTest(mode: Mode, sec: Int) {
        if (job?.isActive == true) return
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Speed test", NotificationManager.IMPORTANCE_LOW)
        )
        val first = notification("Starting...")
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NID, first, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NID, first)
        }
        acquireLocks(sec)

        val conn = Prefs.conn(this)
        val unit = Prefs.unit(this)
        val server = Prefs.server(this)
        TestState.live.value = Live(
            running = true, mode = mode, plannedSec = sec, conn = conn,
            status = "Connecting to server..."
        )

        job = scope.launch {
            var samples: List<Sample> = emptyList()
            var total = 0L
            var label = ""
            var snap: NetSnap? = null
            var err: String? = null
            try {
                val res = Servers.resolve(server)
                val ep = res.ep
                if (ep == null) {
                    err = res.err ?: "Server did not respond."
                    return@launch
                }
                label = ep.label
                TestState.live.value = TestState.live.value.copy(server = label, status = "Checking network...")
                snap = NetTools.snapshot(applicationContext)
                TestState.live.value = TestState.live.value.copy(status = "")

                SpeedEngine().run(mode, sec, ep, conn) { s, all, bytes, jitter ->
                    samples = all.toList()
                    total = bytes
                    TestState.live.value = Live(
                        running = true, mode = mode, plannedSec = sec, elapsed = s.t,
                        down = s.down, up = s.up, ping = s.ping, jitter = jitter,
                        totalBytes = bytes, samples = samples, server = label, conn = conn
                    )
                    val text = when (mode) {
                        Mode.DOWN -> "Down ${unit.fmt(s.down)} ${unit.label}"
                        Mode.UP -> "Up ${unit.fmt(s.up)} ${unit.label}"
                        Mode.BOTH -> "Down ${unit.fmt(s.down)} | Up ${unit.fmt(s.up)} ${unit.label}"
                    } + "  (" + fmtTime(s.t) + " / " + fmtDur(sec) + ")"
                    getSystemService(NotificationManager::class.java).notify(NID, notification(text))
                }
            } finally {
                finish(mode, sec, samples, total, label, conn, snap, err)
            }
        }
    }

    private fun finish(
        mode: Mode, sec: Int, samples: List<Sample>, total: Long,
        label: String, conn: ConnType, snap: NetSnap?, err: String?
    ) {
        val report = if (samples.isNotEmpty()) {
            Report(System.currentTimeMillis(), mode, sec, samples.size, total, samples, label, conn, snap)
        } else null
        report?.let { History.add(applicationContext, it) }
        TestState.live.value = Live(
            running = false, mode = mode, plannedSec = sec, error = err, finished = report
        )
        releaseLocks()
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (report != null) {
            val done = NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Speed test finished")
                .setContentText("Open the app to see the report")
                .setContentIntent(openIntent())
                .setAutoCancel(true)
                .build()
            getSystemService(NotificationManager::class.java).notify(2, done)
        }
        stopSelf()
    }

    private fun openIntent() = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
    )

    private fun notification(text: String): Notification {
        val stop = PendingIntent.getService(
            this, 1, Intent(this, TestService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Speed test running")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent())
            .addAction(0, "Stop", stop)
            .build()
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks(sec: Int) {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "speedtest:wake").apply {
            acquire((sec + 120) * 1000L)
        }
        val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        wifi = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "speedtest:wifi").apply {
            acquire()
        }
    }

    private fun releaseLocks() {
        try { wake?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        try { wifi?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        wake = null
        wifi = null
    }

    override fun onDestroy() {
        releaseLocks()
        scope.cancel()
        super.onDestroy()
    }
}
