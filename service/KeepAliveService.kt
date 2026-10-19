package com.feedtrainer.service
import android.app.*
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.feedtrainer.R
/** Foreground service + silent low-importance notification = OEM-kill resistant persistence. */
class KeepAliveService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val ch = "feedtrainer"
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(ch, "FeedTrainer", NotificationManager.IMPORTANCE_LOW))
        startForeground(1, Notification.Builder(this, ch)
            .setContentTitle("FeedTrainer active")
            .setContentText("Curating your feed toward your goals")
            .setSmallIcon(R.drawable.ic_stat).build())
        return START_STICKY
    }
    companion object {
        fun start(ctx: Context) = ctx.startForegroundService(Intent(ctx, KeepAliveService::class.java))
    }
}
