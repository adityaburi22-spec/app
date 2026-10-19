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
    private lateinit var btnContinueGuest: MaterialButton

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
        btnContinueGuest = findViewById(R.id.btnContinueGuest)

        tvToggleAuthMode.setOnClickListener {
            toggleMode()
        }

        btnSubmitAuth.setOnClickListener {
            performAuth()
        }

        btnContinueGuest.setOnClickListener {
            Toast.makeText(this, "Continuing in Guest Mode (Offline)", Toast.LENGTH_SHORT).show()
            finish()
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

                    // Check subscription status asynchronously
                    if (session.userId.isNotBlank()) {
                        val sub = SupabaseClient.checkSubscriptionStatus(session.userId, session.accessToken)
                        prefs.isSubscribed = sub.first
                        prefs.subscriptionPlan = sub.second
                    }

                    // Sync user goals to Supabase
                    if (session.accessToken.isNotBlank()) {
                        SupabaseClient.syncUserGoals(session.accessToken, prefs.goals)
                    }

                    Toast.makeText(
                        this@AuthActivity,
                        "Welcome ${prefs.userFullName ?: "back"}!",
                        Toast.LENGTH_SHORT
                    ).show()

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
