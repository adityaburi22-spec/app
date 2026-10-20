package com.feedtrainer.ui

import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.feedtrainer.R
import com.feedtrainer.util.OemKeepAliveHelper
import com.google.android.material.button.MaterialButton

class OemGuideActivity : AppCompatActivity() {

    private lateinit var tvDetectedBrand: TextView
    private lateinit var tvBatteryStatus: TextView
    private lateinit var tvOemInstructions: TextView
    private lateinit var btnGrantBattery: MaterialButton
    private lateinit var btnOpenAutostart: MaterialButton
    private lateinit var btnStep3AppDetails: MaterialButton
    private lateinit var btnDoneOem: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_oem_guide)

        tvDetectedBrand = findViewById(R.id.tvDetectedBrand)
        tvBatteryStatus = findViewById(R.id.tvBatteryStatus)
        tvOemInstructions = findViewById(R.id.tvOemInstructions)
        btnGrantBattery = findViewById(R.id.btnGrantBattery)
        btnOpenAutostart = findViewById(R.id.btnOpenAutostart)
        btnStep3AppDetails = findViewById(R.id.btnStep3AppDetails)
        btnDoneOem = findViewById(R.id.btnDoneOem)

        tvDetectedBrand.text = "Device Detected: ${OemKeepAliveHelper.getOemNameFormatted()}"

        val instructions = OemKeepAliveHelper.getOemInstructions()
        tvOemInstructions.text = instructions.joinToString("\n\n")

        btnGrantBattery.setOnClickListener {
            OemKeepAliveHelper.requestIgnoreBatteryOptimizations(this)
        }

        btnOpenAutostart.setOnClickListener {
            val opened = OemKeepAliveHelper.openAutostartSettings(this)
            if (!opened) {
                Toast.makeText(
                    this,
                    "Please open your phone's Settings > Apps > Autostart manually",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        btnStep3AppDetails.setOnClickListener {
            try {
                val intent = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = android.net.Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "Could not open App Info", Toast.LENGTH_SHORT).show()
            }
        }

        btnDoneOem.setOnClickListener {
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        updateBatteryStatus()
    }

    private fun updateBatteryStatus() {
        val isExempt = OemKeepAliveHelper.isIgnoringBatteryOptimizations(this)
        if (isExempt) {
            tvBatteryStatus.text = "Status: ✓ Protected (Battery Optimization Disabled)"
            tvBatteryStatus.setTextColor(getColor(R.color.status_green))
            btnGrantBattery.isEnabled = false
            btnGrantBattery.text = "✓ Battery Exemption Granted"
        } else {
            tvBatteryStatus.text = "Status: ⚠️ Not Protected (Service may be killed)"
            tvBatteryStatus.setTextColor(getColor(R.color.status_yellow))
            btnGrantBattery.isEnabled = true
            btnGrantBattery.text = "Grant Battery Exemption"
        }
    }
}
