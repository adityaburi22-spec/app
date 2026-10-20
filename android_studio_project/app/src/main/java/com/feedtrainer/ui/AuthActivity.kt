package com.feedtrainer.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.feedtrainer.R
import com.feedtrainer.data.AppPreferences
import com.feedtrainer.network.SupabaseClient
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.tasks.Task
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch

class AuthActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "AuthActivity"
        private const val RC_GOOGLE_SIGN_IN = 9002
    }

    private lateinit var prefs: AppPreferences
    private lateinit var googleSignInClient: GoogleSignInClient
    private var isSignUpMode = false

    private lateinit var tvAuthTitle: TextView
    private lateinit var btnGoogleSignIn: MaterialButton
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

        initGoogleSignIn()

        tvAuthTitle = findViewById(R.id.tvAuthTitle)
        btnGoogleSignIn = findViewById(R.id.btnGoogleSignIn)
        labelFullName = findViewById(R.id.labelFullName)
        etFullName = findViewById(R.id.etFullName)
        etEmail = findViewById(R.id.etEmail)
        etPassword = findViewById(R.id.etPassword)
        tvAuthError = findViewById(R.id.tvAuthError)
        btnSubmitAuth = findViewById(R.id.btnSubmitAuth)
        tvToggleAuthMode = findViewById(R.id.tvToggleAuthMode)
        btnVisitWebsite = findViewById(R.id.btnVisitWebsite)

        btnGoogleSignIn.setOnClickListener {
            startGoogleSignIn()
        }

        tvToggleAuthMode.setOnClickListener {
            toggleMode()
        }

        btnSubmitAuth.setOnClickListener {
            performAuth()
        }

        btnVisitWebsite.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(SupabaseClient.PURCHASE_URL))
            startActivity(intent)
        }
    }

    private fun initGoogleSignIn() {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(SupabaseClient.GOOGLE_SERVER_CLIENT_ID)
            .requestEmail()
            .requestProfile()
            .build()

        googleSignInClient = GoogleSignIn.getClient(this, gso)
    }

    private fun startGoogleSignIn() {
        tvAuthError.visibility = View.GONE
        setGoogleLoading(true)
        val signInIntent = googleSignInClient.signInIntent
        startActivityForResult(signInIntent, RC_GOOGLE_SIGN_IN)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == RC_GOOGLE_SIGN_IN) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(data)
            handleGoogleSignInResult(task)
        }
    }

    private fun handleGoogleSignInResult(completedTask: Task<GoogleSignInAccount>) {
        try {
            val account = completedTask.getResult(ApiException::class.java)
            val email = account?.email
            val displayName = account?.displayName

            if (email.isNullOrBlank()) {
                setGoogleLoading(false)
                showError("Google sign-in did not provide an email address.")
                return
            }

            Log.i(TAG, "Google Sign-In returned email: $email, checking existing account in database...")

            lifecycleScope.launch {
                val authResult = SupabaseClient.authenticateExistingGoogleUser(email, displayName)
                setGoogleLoading(false)

                when (authResult) {
                    is SupabaseClient.AuthResult.Success -> {
                        val session = authResult.session
                        prefs.userId = session.userId
                        prefs.userEmail = session.email
                        prefs.userFullName = session.fullName ?: displayName
                        prefs.authToken = session.accessToken

                        // Check subscription status
                        val sub = SupabaseClient.checkSubscriptionStatus(session.userId, session.email, session.accessToken)
                        prefs.isSubscribed = sub.first
                        prefs.subscriptionPlan = sub.second

                        // Sync user goals to cloud
                        if (session.accessToken.isNotBlank()) {
                            SupabaseClient.syncUserGoals(session.accessToken, prefs.goals)
                        }

                        if (sub.first) {
                            Toast.makeText(
                                this@AuthActivity,
                                "Welcome back, ${prefs.userFullName ?: "User"}! Membership active.",
                                Toast.LENGTH_SHORT
                            ).show()
                            val intent = Intent(this@AuthActivity, MainActivity::class.java).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                            }
                            startActivity(intent)
                        } else {
                            Toast.makeText(
                                this@AuthActivity,
                                "Signed in with Google. Active subscription required.",
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
                        // User does not exist in Supabase! Must create account on website.
                        googleSignInClient.signOut()
                        showAccountNotFoundDialog(email)
                    }
                }
            }
        } catch (e: ApiException) {
            setGoogleLoading(false)
            Log.w(TAG, "Google sign in failed with statusCode: ${e.statusCode}", e)
            if (e.statusCode != 12501 && e.statusCode != 12502) { // 12501 = user canceled
                showError("Google sign-in failed (Code ${e.statusCode}). Please try again.")
            }
        }
    }

    /**
     * Enforces the critical rule: No new user accounts can be created via Google in-app.
     * Tells user to create an account on the website first.
     */
    private fun showAccountNotFoundDialog(email: String) {
        AlertDialog.Builder(this)
            .setTitle("Account Not Found")
            .setMessage("No registered FeedTrainer account exists for $email.\n\nYou cannot create a new account in the app. Please create your account and choose a plan on our website first.")
            .setPositiveButton("Create Account on Website") { _, _ ->
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(SupabaseClient.PURCHASE_URL))
                startActivity(intent)
            }
            .setNegativeButton("Dismiss", null)
            .show()
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
            tvAuthTitle.text = "Welcome to BetterBrainX"
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
        btnGoogleSignIn.isEnabled = !loading
        if (loading) tvAuthError.visibility = View.GONE
    }

    private fun setGoogleLoading(loading: Boolean) {
        btnGoogleSignIn.isEnabled = !loading
        btnGoogleSignIn.text = if (loading) "Connecting to Google..." else "Continue with Google"
        btnSubmitAuth.isEnabled = !loading
        if (loading) tvAuthError.visibility = View.GONE
    }
}

