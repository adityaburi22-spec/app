package com.feedtrainer.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.feedtrainer.data.AppPreferences

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        context ?: return
        val action = intent?.action ?: return
        Log.d("BootReceiver", "Received broadcast action: $action")

        val prefs = AppPreferences(context)
        if (prefs.isKeepAliveEnabled) {
            try {
                KeepAliveService.start(context)
                Log.d("BootReceiver", "KeepAliveService successfully revived after $action")
            } catch (e: Exception) {
                Log.e("BootReceiver", "Failed to restart KeepAliveService", e)
            }
        }
    }
}
