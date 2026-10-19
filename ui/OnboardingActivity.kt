package com.feedtrainer.ui
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import com.feedtrainer.service.FeedBrain
import com.feedtrainer.service.KeepAliveService
/** One-time setup: pick goals (embedded once), then the three grants that keep the service alive. */
class OnboardingActivity : AppCompatActivity() {
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        val goals = listOf("machine learning and AI", "fitness and strength training",
            "personal finance and investing")
        FeedBrain.init(applicationContext, goals)
        requestBatteryExemption(this)
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        openAutostartSettings(this)
        KeepAliveService.start(this)
    }
    private fun requestBatteryExemption(ctx: Context) {
        val pm = ctx.getSystemService(PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(ctx.packageName))
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:${ctx.packageName}")))
    }
    private fun openAutostartSettings(ctx: Context) {
        val oem = listOf(
            "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
            "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
            "com.samsung.android.lool" to "com.samsung.android.sm.ui.battery.BatteryActivity")
        for ((pkg, cls) in oem) try { startActivity(Intent().setClassName(pkg, cls)); return } catch (_: Exception) {}
    }
}
