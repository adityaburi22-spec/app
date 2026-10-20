package com.feedtrainer.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.feedtrainer.data.AppPreferences

/**
 * ChargerReceiver — Listens for phone being plugged in to charge.
 * When the user plugs in their phone, automatically triggers the
 * Nightstand Ghost Training Calibration session if:
 * 1. The user has opted in to auto-calibration on charge
 * 2. FeedTrainerService is running
 * 3. Calibration is not already running
 */
class ChargerReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ChargerReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_POWER_CONNECTED) return

        val prefs = AppPreferences(context)
        if (!prefs.isAutoCalibrationOnChargeEnabled) {
            Log.d(TAG, "Charger connected but auto-calibration on charge is disabled")
            return
        }

        if (!prefs.isSubscribed) {
            Log.d(TAG, "Charger connected but user is not subscribed")
            return
        }

        if (NightstandCalibrator.isRunning) {
            Log.d(TAG, "Charger connected but calibration is already running")
            return
        }

        Log.i(TAG, "Phone plugged in → Scheduling nightstand calibration via FeedTrainerService")

        // Broadcast intent to FeedTrainerService to start calibration
        val calibrateIntent = Intent(context, FeedTrainerService::class.java).apply {
            action = FeedTrainerService.ACTION_START_CALIBRATION
        }
        try {
            context.startService(calibrateIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start calibration via charger event", e)
        }
    }
}
