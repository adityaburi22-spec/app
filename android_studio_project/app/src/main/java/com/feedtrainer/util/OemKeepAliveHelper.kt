package com.feedtrainer.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

object OemKeepAliveHelper {

    private const val TAG = "OemKeepAliveHelper"

    fun isIgnoringBatteryOptimizations(ctx: Context): Boolean {
        val pm = ctx.getSystemService(PowerManager::class.java) ?: return false
        return pm.isIgnoringBatteryOptimizations(ctx.packageName)
    }

    fun requestIgnoreBatteryOptimizations(ctx: Context) {
        if (!isIgnoringBatteryOptimizations(ctx)) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${ctx.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                ctx.startActivity(intent)
            } catch (e: Exception) {
                try {
                    val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    ctx.startActivity(fallback)
                } catch (e2: Exception) {
                    Log.e(TAG, "Failed to launch battery settings", e2)
                }
            }
        }
    }

    fun getDeviceManufacturer(): String {
        return (Build.MANUFACTURER ?: "generic").lowercase()
    }

    fun getOemNameFormatted(): String {
        return when (val m = getDeviceManufacturer()) {
            "xiaomi", "redmi", "poco" -> "Xiaomi (MIUI / HyperOS)"
            "vivo", "iqoo" -> "Vivo / iQOO (Funtouch / OriginOS)"
            "oppo", "realme", "oneplus" -> "Oppo / Realme / OnePlus (ColorOS / OxygenOS)"
            "samsung" -> "Samsung (One UI)"
            "huawei", "honor" -> "Huawei / Honor (EMUI / MagicOS)"
            "transsion", "infinix", "tecno", "itel" -> "Infinix / Tecno (HiOS / XOS)"
            else -> (Build.MANUFACTURER ?: "Android").replaceFirstChar { it.uppercase() }
        }
    }

    fun getOemInstructions(manufacturer: String = getDeviceManufacturer()): List<String> {
        val m = manufacturer.lowercase()
        return when {
            m.contains("vivo") || m.contains("iqoo") -> listOf(
                "1. Go to Settings > Battery > Background Power Consumption > Select FeedTrainer > Tap 'High Background Power Usage'.",
                "2. Open iManager app > App Management > Autostart > Enable FeedTrainer.",
                "3. In Recent Apps screen, swipe down on FeedTrainer or tap the Lock icon so it is never cleared."
            )
            m.contains("xiaomi") || m.contains("redmi") || m.contains("poco") -> listOf(
                "1. Go to Settings > Apps > Manage Apps > FeedTrainer > Enable 'Autostart'.",
                "2. Under Battery Saver, select 'No restrictions'.",
                "3. In Recent Apps screen, press and hold FeedTrainer card and tap the Padlock icon to lock it in RAM."
            )
            m.contains("oppo") || m.contains("realme") || m.contains("oneplus") -> listOf(
                "1. Go to Settings > Battery > More battery settings > App battery management > FeedTrainer > Enable 'Allow background activity'.",
                "2. Go to Settings > Apps > Auto-launch > Enable FeedTrainer.",
                "3. In Recent Apps, tap the three dots on FeedTrainer and select 'Lock'."
            )
            m.contains("samsung") -> listOf(
                "1. Go to Settings > Battery and device care > Battery > Background usage limits.",
                "2. Tap 'Never sleeping apps' > Add FeedTrainer.",
                "3. Under App Info for FeedTrainer, set Battery to 'Unrestricted'."
            )
            m.contains("huawei") || m.contains("honor") -> listOf(
                "1. Go to Settings > Battery > App launch > FeedTrainer.",
                "2. Turn off 'Manage automatically' and turn ON: Auto-launch, Secondary launch, and Run in background.",
                "3. Lock FeedTrainer in recent apps overview."
            )
            else -> listOf(
                "1. Disable Battery Optimization (set FeedTrainer to 'Unrestricted' or 'Not Optimized').",
                "2. Enable Autostart / Background activity in your device settings if available.",
                "3. Lock FeedTrainer in the recent apps / task switcher."
            )
        }
    }

    fun openAutostartSettings(ctx: Context): Boolean {
        val intents = listOf(
            // Vivo / iQOO
            Intent().setComponent(ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity")),
            Intent().setComponent(ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.PurviewTabActivity")),
            Intent().setComponent(ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager")),

            // Xiaomi / MIUI / HyperOS
            Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
            Intent("miui.intent.action.OP_AUTO_START").addCategory(Intent.CATEGORY_DEFAULT),

            // Oppo / Realme / OnePlus
            Intent().setComponent(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")),
            Intent().setComponent(ComponentName("com.oplus.battery", "com.oplus.battery.AppListActivity")),
            Intent().setComponent(ComponentName("com.coloros.oppoguardelf", "com.coloros.powermanager.fuelgaue.PowerUsageModelActivity")),

            // Samsung
            Intent().setComponent(ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity")),
            Intent().setComponent(ComponentName("com.samsung.android.sm", "com.samsung.android.sm.battery.ui.BatteryActivity")),

            // Huawei / Honor
            Intent().setComponent(ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity")),
            Intent().setComponent(ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity")),

            // Transsion (Infinix, Tecno, itel)
            Intent().setComponent(ComponentName("com.transsion.phonemaster", "com.transsion.phonemaster.autostart.AutoStartManageActivity")),

            // Generic app details
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${ctx.packageName}")
            }
        )

        for (intent in intents) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(intent)
                return true
            } catch (e: Exception) {
                // Try next
            }
        }
        return false
    }
}
