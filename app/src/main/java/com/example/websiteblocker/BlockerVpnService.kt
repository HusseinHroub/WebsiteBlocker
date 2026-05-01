package com.example.websiteblocker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.*
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetAddress

class BlockerVpnService : VpnService() {

    private var tunFd: ParcelFileDescriptor? = null
    private var job: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopVpn()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, buildNotification())
        startVpn()
        return START_STICKY
    }

    private fun startVpn() {
        val upstreamDns = resolveUpstreamDns()

        tunFd = Builder()
            .addAddress("10.0.0.2", 32)
            .addDnsServer("10.0.0.2")
            .addRoute("0.0.0.0", 0)
            .setSession("WebsiteBlocker")
            .establish() ?: run { stopSelf(); return }

        val processor = DnsPacketProcessor(
            BlocklistRepository(this),
            DnsForwarder(upstreamDns)
        )

        job = CoroutineScope(Dispatchers.IO).launch {
            val input = FileInputStream(tunFd!!.fileDescriptor)
            val output = FileOutputStream(tunFd!!.fileDescriptor)
            val buffer = ByteArray(32767)

            try {
                while (isActive) {
                    try {
                        val length = input.read(buffer)
                        if (length <= 0) continue
                        val response = processor.process(buffer.copyOf(length))
                        if (response != null) output.write(response)
                    } catch (e: Exception) {
                        // skip malformed packet, keep loop running
                    }
                }
            } finally {
                tunFd?.close()
            }
        }
    }

    private fun stopVpn() {
        job?.cancel()
        tunFd?.close()
        tunFd = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        job?.cancel()
        tunFd?.close()
    }

    // Read real device DNS before VPN is established; fall back to 8.8.8.8
    private fun resolveUpstreamDns(): InetAddress {
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val lp = cm.getLinkProperties(cm.activeNetwork)
            val dns = lp?.dnsServers?.firstOrNull()
            if (dns != null && !dns.hostAddress.equals("10.0.0.2")) dns
            else InetAddress.getByName("8.8.8.8")
        } catch (e: Exception) {
            InetAddress.getByName("8.8.8.8")
        }
    }

    private fun buildNotification(): Notification {
        val channelId = "vpn_channel"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(channelId) == null) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "VPN Status", NotificationManager.IMPORTANCE_LOW)
            )
        }
        return Notification.Builder(this, channelId)
            .setContentTitle("Website Blocker active")
            .setSmallIcon(android.R.drawable.ic_menu_close_clear_cancel)
            .build()
    }

    companion object {
        const val ACTION_STOP = "com.example.websiteblocker.STOP"
        private const val NOTIFICATION_ID = 1
    }
}
