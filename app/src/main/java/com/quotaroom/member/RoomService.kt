package com.quotaroom.member

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.net.TrafficStats
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class RoomState(
    val connected: Boolean = false,
    val used: Long = 0,
    val quota: Long = 0,
    val blocked: Boolean = false,
    val error: String? = null,
)

/** Keeps reporting usage to the admin in the background and turns the cut-off VPN on or off. */
class RoomService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var vpnOn = false

    override fun onCreate() {
        super.onCreate()
        val ch = NotificationChannel(CHANNEL, "Quota Room", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val base = intent?.getStringExtra(EXTRA_BASE) ?: return START_NOT_STICKY
        val token = intent.getStringExtra(EXTRA_TOKEN) ?: return START_NOT_STICKY
        startForeground(NOTIF_ID, notification("Connected"))
        job?.cancel()
        job = scope.launch { loop(base, token) }
        return START_STICKY
    }

    private suspend fun loop(base: String, token: String) {
        var last = totalBytes()
        var pending = 0L   // bytes used since the last report to the admin
        var used = 0L      // used, as of the last report
        var quota = 0L
        var blocked = false
        var tick = 0
        var force = true   // report right away on start
        while (scope.isActive) {
            val now = totalBytes()
            pending += (now - last).coerceAtLeast(0)
            last = now

            // Check locally every second and cut the net as soon as the quota is hit,
            // instead of waiting for the next report.
            if (quota > 0 && !blocked && used + pending >= quota) {
                blocked = true
                setVpn(true)
                force = true
            }

            tick++
            if (force || tick % REPORT_EVERY == 0) {
                try {
                    val r = RoomApi.report(base, token, pending)
                    pending = 0
                    used = r.used
                    quota = r.quota
                    blocked = r.blocked
                    setVpn(r.blocked)
                    val pct = if (quota > 0) used * 100 / quota else 100
                    updateNotif(if (blocked) "Quota used up, internet cut" else "Used $pct%")
                    state.value = RoomState(true, used, quota, blocked)
                } catch (e: Exception) {
                    state.value = state.value.copy(connected = false, error = e.message)
                }
                force = false
            }
            if (!force && quota > 0) {
                state.value = state.value.copy(used = used + pending, quota = quota, blocked = blocked)
            }
            delay(1_000)
        }
    }

    private fun totalBytes(): Long {
        val rx = TrafficStats.getTotalRxBytes()
        val tx = TrafficStats.getTotalTxBytes()
        return (if (rx < 0) 0 else rx) + (if (tx < 0) 0 else tx)
    }

    private fun setVpn(blocked: Boolean) {
        if (blocked == vpnOn) return
        vpnOn = blocked
        val i = Intent(this, QuotaVpnService::class.java)
        if (blocked) startService(i) else startService(i.setAction(QuotaVpnService.ACTION_OFF))
    }

    private fun updateNotif(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification(text))
    }

    private fun notification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Quota Room")
            .setContentText(text)
            .setOngoing(true)
            .build()

    override fun onDestroy() {
        job?.cancel()
        if (vpnOn) startService(Intent(this, QuotaVpnService::class.java).setAction(QuotaVpnService.ACTION_OFF))
        vpnOn = false
        state.value = RoomState()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP = "com.quotaroom.member.STOP"
        const val EXTRA_BASE = "base"
        const val EXTRA_TOKEN = "token"
        private const val CHANNEL = "room"
        private const val REPORT_EVERY = 15
        private const val NOTIF_ID = 1
        val state = MutableStateFlow(RoomState())
    }
}
