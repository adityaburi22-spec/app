package com.feedtrainer.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.feedtrainer.R
import com.feedtrainer.data.AppPreferences
import com.feedtrainer.network.SupabaseClient
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch

class AuthActivity : AppCompatActivity() {

    private lateinit var prefs: AppPreferences
    private var isSignUpMode = false

    private lateinit var tvAuthTitle: TextView
    private lateinit var labelFullName: TextView
    private lateinit var etFullName: EditText
    private lateinit var etEmail: EditText
    private lateinit var etPassword: EditText
    private lateinit var tvAuthError: TextView
    private lateinit var btnSubmitAuth: MaterialButton
    private lateinit var tvToggleAuthMode: TextView
    private lateinit var btnVisitWebsite: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_auth)

        prefs = AppPreferences(this)

        tvAuthTitle = findViewById(R.id.tvAuthTitle)
        labelFullName = findViewById(R.id.labelFullName)
        etFullName = findViewById(R.id.etFullName)
        etEmail = findViewById(R.id.etEmail)
        etPassword = findViewById(R.id.etPassword)
        tvAuthError = findViewById(R.id.tvAuthError)
        btnSubmitAuth = findViewById(R.id.btnSubmitAuth)
        tvToggleAuthMode = findViewById(R.id.tvToggleAuthMode)
        btnVisitWebsite = findViewById(R.id.btnVisitWebsite)

        tvToggleAuthMode.setOnClickListener {
            toggleMode()
        }

        btnSubmitAuth.setOnClickListener {
            performAuth()
        }

        btnVisitWebsite.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(SupabaseClient.PURCHASE_URL))
            startActivity(intent)
        }
    }

    private fun toggleMode() {
        isSignUpMode = !isSignUpMode
        tvAuthError.visibility = View.GONE

        if (isSignUpMode) {
            tvAuthTitle.text = "Create FeedTrainer Account"
            labelFullName.visibility = View.VISIBLE
            etFullName.visibility = View.VISIBLE
            btnSubmitAuth.text = "Sign Up"
            tvToggleAuthMode.text = "Already have an account? Sign In"
        } else {
            tvAuthTitle.text = "Welcome to FeedTrainer"
            labelFullName.visibility = View.GONE
            etFullName.visibility = View.GONE
            btnSubmitAuth.text = "Sign In"
            tvToggleAuthMode.text = "Don't have an account? Sign Up"
        }
    }

    private fun performAuth() {
        val email = etEmail.text.toString().trim()
        val pass = etPassword.text.toString().trim()
        val fullName = etFullName.text.toString().trim()

        if (email.isEmpty() || !email.contains("@")) {
            showError("Please enter a valid email address")
            return
        }
        if (pass.length < 6) {
            showError("Password must be at least 6 characters")
            return
        }
        if (isSignUpMode && fullName.isEmpty()) {
            showError("Please enter your name")
            return
        }

        setLoading(true)

        lifecycleScope.launch {
            val result = if (isSignUpMode) {
                SupabaseClient.signUp(email, pass, fullName)
            } else {
                SupabaseClient.signIn(email, pass)
            }

            setLoading(false)

            when (result) {
                is SupabaseClient.AuthResult.Success -> {
                    val session = result.session
                    prefs.userId = session.userId
                    prefs.userEmail = session.email
                    prefs.userFullName = session.fullName ?: fullName
                    prefs.authToken = session.accessToken

                    // Check subscription status
                    val sub = if (session.userId.isNotBlank()) {
                        SupabaseClient.checkSubscriptionStatus(session.userId, session.email, session.accessToken)
                    } else {
                        Pair(false, null)
                    }

                    prefs.isSubscribed = sub.first
                    prefs.subscriptionPlan = sub.second

                    // Sync user goals to Supabase
                    if (session.accessToken.isNotBlank()) {
                        SupabaseClient.syncUserGoals(session.accessToken, prefs.goals)
                    }

                    if (sub.first) {
                        Toast.makeText(
                            this@AuthActivity,
                            "Welcome ${prefs.userFullName ?: "back"}! Pro membership active.",
                            Toast.LENGTH_SHORT
                        ).show()
                        val intent = Intent(this@AuthActivity, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        }
                        startActivity(intent)
                    } else {
                        Toast.makeText(
                            this@AuthActivity,
                            "Signed in. Subscription required to activate FeedTrainer.",
                            Toast.LENGTH_LONG
                        ).show()
                        val intent = Intent(this@AuthActivity, PaywallActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        }
                        startActivity(intent)
                    }

                    finish()
                }
                is SupabaseClient.AuthResult.Error -> {
                    showError(result.message)
                }
            }
        }
    }

    private fun showError(msg: String) {
        tvAuthError.text = msg
        tvAuthError.visibility = View.VISIBLE
    }

    private fun setLoading(loading: Boolean) {
        btnSubmitAuth.isEnabled = !loading
        btnSubmitAuth.text = if (loading) "Connecting..." else if (isSignUpMode) "Sign Up" else "Sign In"
        if (loading) tvAuthError.visibility = View.GONE
    }
}
