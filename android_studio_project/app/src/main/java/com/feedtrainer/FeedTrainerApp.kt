package com.feedtrainer

import android.app.Application
import android.util.Log
import com.feedtrainer.data.AppPreferences
import com.feedtrainer.service.FeedBrain
import com.feedtrainer.service.KeepAliveService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class FeedTrainerApp : Application() {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        Log.i("FeedTrainerApp", "FeedTrainer application starting")

        val prefs = AppPreferences(this)

        // Initialize FeedBrain model in background
        applicationScope.launch {
            try {
                Log.d("FeedTrainerApp", "Initializing FeedBrain ONNX embedding model...")
                FeedBrain.init(applicationContext, prefs.goals)
                Log.d("FeedTrainerApp", "FeedBrain initialized successfully!")
            } catch (e: Throwable) {
                Log.e("FeedTrainerApp", "FeedBrain initialization warning: ${e.message}")
            }
        }

        // Start KeepAlive foreground service if enabled
        if (prefs.isKeepAliveEnabled) {
            try {
                KeepAliveService.start(this)
            } catch (e: Exception) {
                Log.e("FeedTrainerApp", "Failed to start KeepAliveService during app launch", e)
            }
        }
    }
}
