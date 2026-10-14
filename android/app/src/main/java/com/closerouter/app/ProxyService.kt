package com.closerouter.app

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
import android.os.PowerManager
import com.closerouter.app.proxy.ProxyServer

/**
 * 转发前台服务：onCreate 即起 HTTP server（adb 可直接 am start-foreground-service 拉起），
 * dataSync 类型 + 常驻通知 + PARTIAL_WAKE_LOCK 防后台杀死。
 */
class ProxyService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundWithNotification()
        acquireWakeLock()
        ProxyServer.start(App.port)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!ProxyServer.running.value) ProxyServer.start(App.port)
        return START_STICKY
    }

    override fun onDestroy() {
        ProxyServer.stop()
        runCatching { wakeLock?.let { if (it.isHeld) it.release() } }
        wakeLock = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundWithNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "转发服务", NotificationManager.IMPORTANCE_LOW)
        )
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notif: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("CloseRouter 转发中")
            .setContentText("端口 ${App.port} · USB / 局域网")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "closerouter:proxy").apply {
            acquire()
        }
    }

    companion object {
        const val CHANNEL_ID = "proxy"
        const val NOTIF_ID = 1
        const val ACTION_STOP = "com.closerouter.app.STOP"

        fun start(context: Context) {
            val i = Intent(context, ProxyService::class.java)
            context.startForegroundService(i)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ProxyService::class.java))
        }
    }
}
