package com.feedtrainer.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.lifecycle.lifecycleScope
import com.feedtrainer.R
import com.feedtrainer.data.AppPreferences
import com.feedtrainer.model.ActivityLogItem
import com.feedtrainer.model.Reel
import com.feedtrainer.network.SupabaseClient
import com.feedtrainer.service.FeedBrain
import com.feedtrainer.service.FeedTrainerService
import com.feedtrainer.service.KeepAliveService
import com.feedtrainer.util.OemKeepAliveHelper
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: AppPreferences

    // Views
    private lateinit var btnAccount: MaterialButton
    private lateinit var viewServiceDot: View
    private lateinit var tvServiceStatusTitle: TextView
    private lateinit var tvServiceStatusDesc: TextView
    private lateinit var btnEnableService: MaterialButton

    private lateinit var tvOemTitle: TextView
    private lateinit var tvOemSubtitle: TextView
    private lateinit var btnOemGuide: MaterialButton

    private lateinit var tvStatScanned: TextView
    private lateinit var tvStatSteered: TextView
    private lateinit var tvStatDailyCap: TextView

    private lateinit var switchAutoLike: SwitchCompat
    private lateinit var tvGoalsList: TextView
    private lateinit var btnCustomizeGoals: MaterialButton

    private lateinit var etTestCaption: EditText
    private lateinit var btnRunTest: MaterialButton
    private lateinit var tvTestResult: TextView

    private lateinit var layoutEmptyLogs: LinearLayout
    private lateinit var tvEmptyLogs: TextView
    private lateinit var containerLogs: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = AppPreferences(this)

        if (!enforceAccessControl()) return

        setContentView(R.layout.activity_main)

        initViews()
        setupListeners()
        updateDashboard()
    }

    override fun onResume() {
        super.onResume()
        if (isFinishing) return
        if (!enforceAccessControl()) return
        updateDashboard()
        checkSubscriptionWithServer()
    }

    private fun enforceAccessControl(): Boolean {
        if (prefs.userId.isNullOrBlank() || prefs.authToken.isNullOrBlank()) {
            startActivity(Intent(this, AuthActivity::class.java))
            finish()
            return false
        }

        if (!prefs.isSubscribed) {
            startActivity(Intent(this, PaywallActivity::class.java))
            finish()
            return false
        }

        return true
    }

    private fun checkSubscriptionWithServer() {
        val uid = prefs.userId ?: return
        val email = prefs.userEmail
        val token = prefs.authToken ?: ""

        lifecycleScope.launch {
            val status = SupabaseClient.checkSubscriptionStatus(uid, email, token)
            if (!status.first) {
                prefs.isSubscribed = false
                val intent = Intent(this@MainActivity, PaywallActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
                startActivity(intent)
                finish()
            } else {
                prefs.isSubscribed = true
                prefs.subscriptionPlan = status.second
            }
        }
    }

    private fun initViews() {
        btnAccount = findViewById(R.id.btnAccount)
        viewServiceDot = findViewById(R.id.viewServiceDot)
        tvServiceStatusTitle = findViewById(R.id.tvServiceStatusTitle)
        tvServiceStatusDesc = findViewById(R.id.tvServiceStatusDesc)
        btnEnableService = findViewById(R.id.btnEnableService)

        tvOemTitle = findViewById(R.id.tvOemTitle)
        tvOemSubtitle = findViewById(R.id.tvOemSubtitle)
        btnOemGuide = findViewById(R.id.btnOemGuide)

        tvStatScanned = findViewById(R.id.tvStatScanned)
        tvStatSteered = findViewById(R.id.tvStatSteered)
        tvStatDailyCap = findViewById(R.id.tvStatDailyCap)

        switchAutoLike = findViewById(R.id.switchAutoLike)
        tvGoalsList = findViewById(R.id.tvGoalsList)
        btnCustomizeGoals = findViewById(R.id.btnCustomizeGoals)

        etTestCaption = findViewById(R.id.etTestCaption)
        btnRunTest = findViewById(R.id.btnRunTest)
        tvTestResult = findViewById(R.id.tvTestResult)

        layoutEmptyLogs = findViewById(R.id.layoutEmptyLogs)
        tvEmptyLogs = findViewById(R.id.tvEmptyLogs)
        containerLogs = findViewById(R.id.containerLogs)
    }

    private fun setupListeners() {
        btnAccount.setOnClickListener {
            if (prefs.userEmail != null) {
                showAccountDialog()
            } else {
                startActivity(Intent(this, AuthActivity::class.java))
            }
        }

        btnEnableService.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
            Toast.makeText(this, "Find FeedTrainer in Installed apps and turn it ON", Toast.LENGTH_LONG).show()
        }

        btnOemGuide.setOnClickListener {
            startActivity(Intent(this, OemGuideActivity::class.java))
        }

        switchAutoLike.setOnCheckedChangeListener { _, isChecked ->
            prefs.isAutoLikeEnabled = isChecked
            Toast.makeText(
                this,
                if (isChecked) "Auto-like enabled for goal matching reels" else "Auto-like paused",
                Toast.LENGTH_SHORT
            ).show()
        }

        btnCustomizeGoals.setOnClickListener {
            showGoalCustomizationDialog()
        }

        btnRunTest.setOnClickListener {
            runClassifierTest()
        }
    }

    private fun updateDashboard() {
        // 1. Accessibility Service status
        val isServiceActive = isAccessibilityServiceEnabled()
        if (isServiceActive) {
            viewServiceDot.setBackgroundResource(R.drawable.dot_green)
            tvServiceStatusTitle.text = "Steering Engine: Active"
            tvServiceStatusDesc.text = "FeedTrainer is actively monitoring Instagram Reels & YouTube Shorts to curate your feed."
            btnEnableService.text = "Active"
            btnEnableService.isEnabled = false
        } else {
            viewServiceDot.setBackgroundResource(R.drawable.dot_red)
            tvServiceStatusTitle.text = "Accessibility Service: Off"
            tvServiceStatusDesc.text = "Turn ON FeedTrainer in Accessibility settings to allow reel caption reading and feed steering."
            btnEnableService.text = "Enable"
            btnEnableService.isEnabled = true
        }

        // 2. OEM Protection status
        val oemName = OemKeepAliveHelper.getOemNameFormatted()
        val isExempt = OemKeepAliveHelper.isIgnoringBatteryOptimizations(this)
        tvOemTitle.text = "Anti-Kill Shield ($oemName)"
        if (isExempt) {
            tvOemSubtitle.text = "✓ Battery exemption active. KeepAlive foreground service is running."
        } else {
            tvOemSubtitle.text = "⚠️ Battery optimization is active. Tap Settings to prevent $oemName from killing the background service."
        }

        // 3. Stats
        tvStatScanned.text = prefs.totalReelsScanned.toString()
        tvStatSteered.text = prefs.totalReelsSteered.toString()
        tvStatDailyCap.text = "${prefs.actionsToday} / ${prefs.dailyLikeCap}"

        // 4. Switch
        switchAutoLike.isChecked = prefs.isAutoLikeEnabled

        // 5. Goals
        val goals = prefs.goals
        tvGoalsList.text = goals.joinToString("\n") { "• $it" }

        // 6. Account button
        if (prefs.userEmail != null) {
            val shortName = prefs.userFullName?.split(" ")?.firstOrNull() ?: prefs.userEmail?.substringBefore("@")
            val subBadge = if (prefs.isSubscribed) "★ Pro" else "Free"
            btnAccount.text = "$shortName ($subBadge)"
        } else {
            btnAccount.text = "Sign In"
        }

        // 7. Recent logs
        renderActivityLogs()
    }

    private fun renderActivityLogs() {
        containerLogs.removeAllViews()
        val logs = prefs.getActivityLogs()

        if (logs.isEmpty()) {
            layoutEmptyLogs.visibility = View.VISIBLE
            containerLogs.visibility = View.GONE
        } else {
            layoutEmptyLogs.visibility = View.GONE
            containerLogs.visibility = View.VISIBLE

            val inflater = LayoutInflater.from(this)
            val displayLogs = logs.take(15)

            for (log in displayLogs) {
                val itemView = inflater.inflate(R.layout.item_activity_log, containerLogs, false)
                val tvPlatform = itemView.findViewById<TextView>(R.id.tvPlatform)
                val tvUsername = itemView.findViewById<TextView>(R.id.tvUsername)
                val tvActionBadge = itemView.findViewById<TextView>(R.id.tvActionBadge)
                val tvCaptionSnippet = itemView.findViewById<TextView>(R.id.tvCaptionSnippet)
                val tvGoalTag = itemView.findViewById<TextView>(R.id.tvGoalTag)

                tvPlatform.text = log.platform
                tvUsername.text = "@${log.username}"
                tvCaptionSnippet.text = log.captionSnippet
                tvActionBadge.text = log.action

                if (log.action == "LIKED") {
                    tvActionBadge.setTextColor(getColor(R.color.status_green))
                    tvGoalTag.visibility = View.VISIBLE
                    tvGoalTag.text = "🎯 ${log.matchedGoal ?: "Goal"}"
                } else {
                    tvActionBadge.setTextColor(getColor(R.color.text_muted))
                    tvGoalTag.visibility = View.GONE
                }

                containerLogs.addView(itemView)
            }
        }
    }

    private fun runClassifierTest() {
        val text = etTestCaption.text.toString().trim()
        if (text.isEmpty()) {
            tvTestResult.text = "Please enter some caption text first"
            return
        }

        tvTestResult.text = "Evaluating on-device AI model..."

        lifecycleScope.launch {
            val testReel = Reel("sample_creator", text, listOf(text), "com.instagram.android")
            val result = FeedBrain.classify(testReel, prefs.goals)

            val scorePercent = (result.score * 100).toInt()
            when (result.decision) {
                "LIKE" -> {
                    tvTestResult.setTextColor(getColor(R.color.status_green))
                    tvTestResult.text = "✓ LIKE (${scorePercent}% match)\nGoal: ${result.matchedGoal}"
                }
                "NOT_INTERESTED" -> {
                    tvTestResult.setTextColor(getColor(R.color.status_red))
                    tvTestResult.text = "✗ NOT INTERESTED (Clickbait / Distraction detected)"
                }
                else -> {
                    tvTestResult.setTextColor(getColor(R.color.text_muted))
                    tvTestResult.text = "○ SKIP (${scorePercent}% match - Neutral content)"
                }
            }
        }
    }

    private fun showGoalCustomizationDialog() {
        val currentGoals = prefs.goals.toMutableList()
        val allOptions = listOf(
            "Coding, AI, and Software Engineering",
            "Science, Tech & Mathematics",
            "Gym, Fitness, and Nutrition",
            "Investing, Stocks, and Business",
            "Books, Deep Work, and Mindset",
            "Language Learning & Linguistics",
            "Productivity, Habits & Focus",
            "Design, UI/UX & Art"
        )

        val checkedItems = BooleanArray(allOptions.size) { i ->
            currentGoals.contains(allOptions[i])
        }

        AlertDialog.Builder(this)
            .setTitle("Select Your Life Goals")
            .setMultiChoiceItems(allOptions.toTypedArray(), checkedItems) { _, which, isChecked ->
                val selectedGoal = allOptions[which]
                if (isChecked) {
                    if (!currentGoals.contains(selectedGoal)) currentGoals.add(selectedGoal)
                } else {
                    currentGoals.remove(selectedGoal)
                }
            }
            .setPositiveButton("Save Goals") { _, _ ->
                if (currentGoals.isEmpty()) {
                    Toast.makeText(this, "Please keep at least one active goal", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                prefs.goals = currentGoals
                FeedBrain.updateGoals(this, currentGoals)

                // Sync to Supabase if logged in
                val token = prefs.authToken
                if (!token.isNullOrBlank()) {
                    lifecycleScope.launch {
                        SupabaseClient.syncUserGoals(token, currentGoals)
                    }
                }

                updateDashboard()
                Toast.makeText(this, "Goals updated & re-embedded!", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("+ Custom Goal") { _, _ ->
                showCustomGoalInputDialog()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showCustomGoalInputDialog() {
        val input = EditText(this).apply {
            hint = "e.g., Medical School Studies & Anatomy"
            setPadding(40, 30, 40, 30)
        }

        AlertDialog.Builder(this)
            .setTitle("Add Custom Life Goal")
            .setView(input)
            .setPositiveButton("Add") { _, _ ->
                val custom = input.text.toString().trim()
                if (custom.isNotEmpty()) {
                    val updated = prefs.goals.toMutableList()
                    if (!updated.contains(custom)) {
                        updated.add(custom)
                        prefs.goals = updated
                        FeedBrain.updateGoals(this, updated)
                        updateDashboard()
                        Toast.makeText(this, "Added: $custom", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAccountDialog() {
        val email = prefs.userEmail ?: ""
        val status = if (prefs.isSubscribed) "Active Subscriber (${prefs.subscriptionPlan ?: "pro"})" else "Free Account"

        AlertDialog.Builder(this)
            .setTitle("FeedTrainer Account")
            .setMessage("Signed in as:\n$email\n\nStatus: $status")
            .setPositiveButton("Sync Goals") { _, _ ->
                val token = prefs.authToken
                if (!token.isNullOrBlank()) {
                    lifecycleScope.launch {
                        val ok = SupabaseClient.syncUserGoals(token, prefs.goals)
                        Toast.makeText(this@MainActivity, if (ok) "Synced to Cloud!" else "Sync failed", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Sign Out") { _, _ ->
                prefs.clearAuth()
                Toast.makeText(this, "Signed out", Toast.LENGTH_SHORT).show()
                val intent = Intent(this, AuthActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
                startActivity(intent)
                finish()
            }
            .setNeutralButton("Close", null)
            .show()
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        if (FeedTrainerService.isServiceRunning) return true
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager ?: return false
        val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        for (service in enabledServices) {
            if (service.resolveInfo.serviceInfo.packageName == packageName &&
                service.resolveInfo.serviceInfo.name.contains("FeedTrainerService")
            ) {
                return true
            }
        }
        return false
    }
}
