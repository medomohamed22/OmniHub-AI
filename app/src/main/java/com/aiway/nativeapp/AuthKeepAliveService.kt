package com.aiway.nativeapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat

/** Foreground service that only lives while ChatGPT sign-in is in progress. */
class AuthKeepAliveService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "AiWay login", NotificationManager.IMPORTANCE_LOW)
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("AiWay")
            .setContentText("جاري إكمال تسجيل الدخول إلى ChatGPT…")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notification)
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "aiway_login"
        private const val NOTIF_ID = 1455

        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(
                    context.applicationContext,
                    Intent(context.applicationContext, AuthKeepAliveService::class.java)
                )
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.applicationContext.stopService(Intent(context.applicationContext, AuthKeepAliveService::class.java))
            }
        }
    }
}
