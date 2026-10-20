package com.feedtrainer.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.feedtrainer.R
import com.feedtrainer.data.AppPreferences
import com.feedtrainer.network.SupabaseClient
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch

class PaywallActivity : AppCompatActivity() {

    private lateinit var prefs: AppPreferences
    private lateinit var tvPaywallUserEmail: TextView
    private lateinit var tvPaywallStatusBadge: TextView
    private lateinit var btnRefreshSubscription: MaterialButton
    private lateinit var btnPurchaseWebsite: MaterialButton
    private lateinit var btnPaywallSignOut: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_paywall)

        prefs = AppPreferences(this)

        tvPaywallUserEmail = findViewById(R.id.tvPaywallUserEmail)
        tvPaywallStatusBadge = findViewById(R.id.tvPaywallStatusBadge)
        btnRefreshSubscription = findViewById(R.id.btnRefreshSubscription)
        btnPurchaseWebsite = findViewById(R.id.btnPurchaseWebsite)
        btnPaywallSignOut = findViewById(R.id.btnPaywallSignOut)

        tvPaywallUserEmail.text = prefs.userEmail ?: "No account detected"

        btnRefreshSubscription.setOnClickListener {
            verifyPurchase(userInitiated = true)
        }

        btnPurchaseWebsite.setOnClickListener {
            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(SupabaseClient.PURCHASE_URL))
            startActivity(browserIntent)
        }

        btnPaywallSignOut.setOnClickListener {
            prefs.clearAuth()
            val intent = Intent(this, AuthActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            startActivity(intent)
            finish()
        }

        // Intercept back button to exit app completely instead of exposing dashboard
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                finishAffinity()
            }
        })

        // Check automatically once on open
        verifyPurchase(userInitiated = false)
    }

    private fun verifyPurchase(userInitiated: Boolean) {
        val uid = prefs.userId
        val email = prefs.userEmail
        val token = prefs.authToken ?: ""

        if (uid.isNullOrBlank()) {
            if (userInitiated) {
                Toast.makeText(this, "Please sign in to verify your purchase", Toast.LENGTH_SHORT).show()
            }
            return
        }

        if (userInitiated) {
            btnRefreshSubscription.isEnabled = false
            btnRefreshSubscription.text = "Checking database..."
        }

        lifecycleScope.launch {
            val status = SupabaseClient.checkSubscriptionStatus(uid, email, token)
            if (userInitiated) {
                btnRefreshSubscription.isEnabled = true
                btnRefreshSubscription.text = "Check Purchase Status"
            }

            if (status.first) {
                prefs.isSubscribed = true
                prefs.subscriptionPlan = status.second
                Toast.makeText(
                    this@PaywallActivity,
                    "🎉 Membership verified! Welcome to FeedTrainer Pro.",
                    Toast.LENGTH_LONG
                ).show()

                val intent = Intent(this@PaywallActivity, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
                startActivity(intent)
                finish()
            } else {
                prefs.isSubscribed = false
                tvPaywallStatusBadge.text = "⚠️ No verified payment found for this account"
                if (userInitiated) {
                    Toast.makeText(
                        this@PaywallActivity,
                        "No active purchase found. If you just paid on the website, please wait 30 seconds and refresh.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }
}
