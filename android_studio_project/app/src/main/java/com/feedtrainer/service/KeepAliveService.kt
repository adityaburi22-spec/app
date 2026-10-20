package com.feedtrainer.service

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
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.feedtrainer.R
import com.feedtrainer.ui.MainActivity

class KeepAliveService : Service() {

    companion object {
        const val CHANNEL_ID = "feedtrainer_keepalive"
        const val NOTIFICATION_ID = 1001

        fun start(ctx: Context) {
            val intent = Intent(ctx, KeepAliveService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        }

        fun stop(ctx: Context) {
            val intent = Intent(ctx, KeepAliveService::class.java)
            ctx.stopService(intent)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("FeedTrainer Active")
                .setContentText("Curating your feed toward your life goals")
                .setSmallIcon(R.drawable.ic_stat)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setContentIntent(pendingIntent)
                .build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                } else {
                    0
                }
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, fgsType)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }

            val prefs = com.feedtrainer.data.AppPreferences(this)
            if (!prefs.isSubscribed || !prefs.isKeepAliveEnabled) {
                android.util.Log.i("KeepAliveService", "Stopping service: user not subscribed or keepAlive disabled")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
                stopSelf()
                return START_NOT_STICKY
            }
        } catch (e: Exception) {
            android.util.Log.e("KeepAliveService", "Failed to start foreground notification", e)
            stopSelf()
            return START_NOT_STICKY
        }

        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "FeedTrainer Background Service",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Maintains feed steering and persistence against OEM battery killers"
                    setShowBadge(false)
                }
                nm.createNotificationChannel(channel)
            }
        }
    }
}
