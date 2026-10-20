package com.feedtrainer.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.lifecycle.lifecycleScope
import com.feedtrainer.BuildConfig
import com.feedtrainer.R
import com.feedtrainer.data.AppPreferences
import com.feedtrainer.model.ActivityLogItem
import com.feedtrainer.model.AppReleaseUpdate
import com.feedtrainer.model.BugReport
import com.feedtrainer.model.Reel
import com.feedtrainer.network.SupabaseClient
import com.feedtrainer.service.FeedBrain
import com.feedtrainer.service.FeedTrainerService
import com.feedtrainer.util.AppUpdateManager
import com.feedtrainer.util.OemKeepAliveHelper
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    private lateinit var cardDailyCap: View

    private lateinit var switchAutoLike: SwitchCompat
    private lateinit var tvAutoLikeSubtitle: TextView
    private lateinit var switchLikeSponsored: SwitchCompat
    private lateinit var tvLikeSponsoredSubtitle: TextView
    private lateinit var switchDwellGate: SwitchCompat
    private lateinit var switchMicroHud: SwitchCompat
    private lateinit var switchLikeToast: SwitchCompat
    private lateinit var switchFloatingWidget: SwitchCompat
    private lateinit var tvFloatingWidgetSubtitle: TextView
    private lateinit var tvGoalsList: TextView
    private lateinit var btnCustomizeGoals: MaterialButton

    private lateinit var cardFounderDashboard: View
    private lateinit var btnFounderInbox: MaterialButton
    private lateinit var btnReportBug: MaterialButton
    private lateinit var btnCheckUpdates: MaterialButton

    private lateinit var etTestCaption: EditText
    private lateinit var btnRunTest: MaterialButton
    private lateinit var tvTestResult: TextView

    private lateinit var layoutEmptyLogs: LinearLayout
    private lateinit var tvEmptyLogs: TextView
    private lateinit var containerLogs: LinearLayout

    private var isUpdatePromptActive = false

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
        checkForMandatoryUpdate(silent = true)
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
        cardDailyCap = findViewById(R.id.cardDailyCap)

        switchAutoLike = findViewById(R.id.switchAutoLike)
        tvAutoLikeSubtitle = findViewById(R.id.tvAutoLikeSubtitle)
        switchLikeSponsored = findViewById(R.id.switchLikeSponsored)
        tvLikeSponsoredSubtitle = findViewById(R.id.tvLikeSponsoredSubtitle)
        switchDwellGate = findViewById(R.id.switchDwellGate)
        switchMicroHud = findViewById(R.id.switchMicroHud)
        switchLikeToast = findViewById(R.id.switchLikeToast)
        switchFloatingWidget = findViewById(R.id.switchFloatingWidget)
        tvFloatingWidgetSubtitle = findViewById(R.id.tvFloatingWidgetSubtitle)
        tvGoalsList = findViewById(R.id.tvGoalsList)
        btnCustomizeGoals = findViewById(R.id.btnCustomizeGoals)

        cardFounderDashboard = findViewById(R.id.cardFounderDashboard)
        btnFounderInbox = findViewById(R.id.btnFounderInbox)
        btnReportBug = findViewById(R.id.btnReportBug)
        btnCheckUpdates = findViewById(R.id.btnCheckUpdates)

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

        cardDailyCap.setOnClickListener {
            showDailyCapDialog()
        }

        tvAutoLikeSubtitle.setOnClickListener {
            showDailyCapDialog()
        }

        switchAutoLike.setOnCheckedChangeListener { _, isChecked ->
            prefs.isAutoLikeEnabled = isChecked
            Toast.makeText(
                this,
                if (isChecked) "Auto-like enabled for goal matching shorts" else "Auto-like paused",
                Toast.LENGTH_SHORT
            ).show()
        }

        switchLikeSponsored.setOnCheckedChangeListener { _, isChecked ->
            prefs.isLikeSponsoredEnabled = isChecked
            Toast.makeText(
                this,
                if (isChecked) "Liking relevant sponsored shorts enabled" else "Sponsored shorts will be skipped (Default)",
                Toast.LENGTH_SHORT
            ).show()
        }

        switchDwellGate.setOnCheckedChangeListener { _, isChecked ->
            prefs.isDwellTimeGatingEnabled = isChecked
            Toast.makeText(
                this,
                if (isChecked) "Dwell-time gating active (4.6s watch required before like)" else "Dwell-time gating disabled",
                Toast.LENGTH_SHORT
            ).show()
        }

        switchMicroHud.setOnCheckedChangeListener { _, isChecked ->
            prefs.isMicroHudEnabled = isChecked
            Toast.makeText(
                this,
                if (isChecked) "Subtle edge pulse enabled" else "Subtle edge pulse disabled",
                Toast.LENGTH_SHORT
            ).show()
        }

        switchLikeToast.setOnCheckedChangeListener { _, isChecked ->
            prefs.isLikeNotificationEnabled = isChecked
            Toast.makeText(
                this,
                if (isChecked) "Like pop-up notifications enabled" else "Like pop-up notifications disabled (silent mode)",
                Toast.LENGTH_SHORT
            ).show()
        }

        switchFloatingWidget.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (!Settings.canDrawOverlays(this)) {
                    switchFloatingWidget.isChecked = false
                    Toast.makeText(
                        this,
                        "Please allow 'Display over other apps' to enable the floating circle widget",
                        Toast.LENGTH_LONG
                    ).show()
                    try {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                        startActivity(intent)
                    } catch (e: Exception) {
                        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
                    }
                } else {
                    prefs.isFloatingWidgetEnabled = true
                    com.feedtrainer.service.FloatingWidgetService.start(this)
                    updateDashboard()
                }
            } else {
                prefs.isFloatingWidgetEnabled = false
                com.feedtrainer.service.FloatingWidgetService.stop(this)
                updateDashboard()
            }
        }

        btnCustomizeGoals.setOnClickListener {
            showGoalCustomizationDialog()
        }

        btnReportBug.setOnClickListener {
            showBugReportDialog()
        }

        btnFounderInbox.setOnClickListener {
            showFounderInboxDialog()
        }

        btnCheckUpdates.setOnClickListener {
            checkForMandatoryUpdate(silent = false)
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
            tvServiceStatusDesc.text = "FeedTrainer is actively monitoring YouTube Shorts to curate your feed toward life goals."
            btnEnableService.text = "Settings"
            btnEnableService.isEnabled = true
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
            tvOemSubtitle.text = "✓ Battery exemption active. Tap to adjust OEM autostart."
        } else {
            tvOemSubtitle.text = "⚠️ Battery optimization is active. Tap to prevent $oemName from killing the background service."
        }

        // 3. Stats
        tvStatScanned.text = prefs.totalReelsScanned.toString()
        tvStatSteered.text = prefs.totalReelsSteered.toString()
        tvStatDailyCap.text = "${prefs.actionsToday} / ${prefs.dailyLikeCap}"
        tvAutoLikeSubtitle.text = "${prefs.dailyLikeCap} likes/day · 3.2s pacing · goal-aligned only"

        // 4. Switches
        switchAutoLike.isChecked = prefs.isAutoLikeEnabled
        switchLikeSponsored.isChecked = prefs.isLikeSponsoredEnabled
        switchDwellGate.isChecked = prefs.isDwellTimeGatingEnabled
        switchMicroHud.isChecked = prefs.isMicroHudEnabled
        switchLikeToast.isChecked = prefs.isLikeNotificationEnabled

        // 5. Floating widget
        val canOverlay = Settings.canDrawOverlays(this)
        switchFloatingWidget.isChecked = canOverlay && prefs.isFloatingWidgetEnabled
        if (canOverlay && prefs.isFloatingWidgetEnabled) {
            tvFloatingWidgetSubtitle.text = "✓ Floating circle active on screen (drag anywhere)"
            tvFloatingWidgetSubtitle.setTextColor(getColor(R.color.status_green))
            if (!com.feedtrainer.service.FloatingWidgetService.isRunning) {
                com.feedtrainer.service.FloatingWidgetService.start(this)
            }
        } else if (!canOverlay) {
            tvFloatingWidgetSubtitle.text = "Requires 'Display over other apps' permission"
            tvFloatingWidgetSubtitle.setTextColor(getColor(R.color.status_yellow))
        } else {
            tvFloatingWidgetSubtitle.text = "Always-on small circle widget with app logo"
            tvFloatingWidgetSubtitle.setTextColor(getColor(R.color.text_muted))
        }

        // 6. Goals
        val goals = prefs.goals
        tvGoalsList.text = goals.joinToString("\n") { "• $it" }

        // 7. Founder check
        val isFounder = prefs.userEmail?.equals(SupabaseClient.OWNER_EMAIL, ignoreCase = true) == true
        cardFounderDashboard.visibility = if (isFounder) View.VISIBLE else View.GONE

        // 8. Account button
        if (prefs.userEmail != null) {
            val shortName = prefs.userFullName?.split(" ")?.firstOrNull() ?: prefs.userEmail?.substringBefore("@")
            val subBadge = if (isFounder) "Founder" else if (prefs.isSubscribed) "★ Pro" else "Free"
            btnAccount.text = "$shortName ($subBadge)"
        } else {
            btnAccount.text = "Sign In"
        }

        // 9. Recent logs
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

    private fun showBugReportDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_report_bug, null)
        val spinnerCategory = dialogView.findViewById<Spinner>(R.id.spinnerBugCategory)
        val etTitle = dialogView.findViewById<EditText>(R.id.etBugTitle)
        val etDesc = dialogView.findViewById<EditText>(R.id.etBugDescription)
        val tvDiagnostics = dialogView.findViewById<TextView>(R.id.tvDeviceDiagnostics)
        val btnCancel = dialogView.findViewById<MaterialButton>(R.id.btnCancelBug)
        val btnSubmit = dialogView.findViewById<MaterialButton>(R.id.btnSubmitBug)

        val categories = listOf(
            "YouTube Shorts Liking Issue",
            "Slow Scroll Targeting",
            "False Positive Like",
            "Sponsored Content Not Skipped",
            "Accessibility Service Stopped",
            "UI & Design Feedback",
            "Other Issue"
        )
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, categories)
        spinnerCategory.adapter = adapter

        val deviceInfo = "Device: ${Build.MANUFACTURER.uppercase()} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) · App: v${BuildConfig.VERSION_NAME}"
        tvDiagnostics.text = deviceInfo

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnSubmit.setOnClickListener {
            val title = etTitle.text.toString().trim()
            val desc = etDesc.text.toString().trim()
            val category = spinnerCategory.selectedItem.toString()

            if (title.isEmpty() || desc.isEmpty()) {
                Toast.makeText(this, "Please provide a title and description", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            btnSubmit.isEnabled = false
            btnSubmit.text = "Sending..."

            val report = BugReport(
                userEmail = prefs.userEmail ?: "anonymous@feedtrainer.app",
                title = title,
                category = category,
                description = desc,
                deviceInfo = deviceInfo,
                appVersion = BuildConfig.VERSION_NAME
            )

            lifecycleScope.launch {
                val ok = SupabaseClient.submitBugReport(report)
                if (ok) {
                    Toast.makeText(this@MainActivity, "Thank you! Bug report sent to founder.", Toast.LENGTH_LONG).show()
                    dialog.dismiss()
                } else {
                    Toast.makeText(this@MainActivity, "Failed sending. Cached locally.", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                }
            }
        }

        dialog.show()
    }

    private fun showFounderInboxDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_founder_inbox, null)
        val tvReportCount = dialogView.findViewById<TextView>(R.id.tvFounderReportCount)
        val progress = dialogView.findViewById<ProgressBar>(R.id.progressFounderReports)
        val tvEmpty = dialogView.findViewById<TextView>(R.id.tvEmptyFounderReports)
        val container = dialogView.findViewById<LinearLayout>(R.id.containerBugReports)
        val btnRefresh = dialogView.findViewById<MaterialButton>(R.id.btnRefreshReports)
        val btnClose = dialogView.findViewById<MaterialButton>(R.id.btnCloseFounderInbox)

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        fun loadReports() {
            progress.visibility = View.VISIBLE
            container.removeAllViews()
            tvEmpty.visibility = View.GONE
            tvReportCount.text = "Fetching user reports..."

            lifecycleScope.launch {
                val reports = SupabaseClient.fetchBugReports()
                progress.visibility = View.GONE

                if (reports.isEmpty()) {
                    tvEmpty.visibility = View.VISIBLE
                    tvReportCount.text = "0 reports submitted"
                } else {
                    tvEmpty.visibility = View.GONE
                    tvReportCount.text = "${reports.size} total reports from users"

                    val inflater = LayoutInflater.from(this@MainActivity)
                    val dateFormat = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())

                    for (r in reports) {
                        val item = inflater.inflate(R.layout.item_bug_report, container, false)
                        val tvCat = item.findViewById<TextView>(R.id.tvReportCategory)
                        val tvTime = item.findViewById<TextView>(R.id.tvReportTime)
                        val tvT = item.findViewById<TextView>(R.id.tvReportTitle)
                        val tvD = item.findViewById<TextView>(R.id.tvReportDesc)
                        val tvMeta = item.findViewById<TextView>(R.id.tvReportMeta)

                        tvCat.text = r.category
                        tvTime.text = dateFormat.format(Date(r.timestamp))
                        tvT.text = r.title
                        tvD.text = r.description
                        tvMeta.text = "From: ${r.userEmail} · ${r.deviceInfo}"

                        container.addView(item)
                    }
                }
            }
        }

        btnRefresh.setOnClickListener { loadReports() }
        btnClose.setOnClickListener { dialog.dismiss() }

        loadReports()
        dialog.show()
    }

    private fun checkForMandatoryUpdate(silent: Boolean) {
        if (isUpdatePromptActive) return

        lifecycleScope.launch {
            if (!silent) {
                Toast.makeText(this@MainActivity, "Checking for latest update...", Toast.LENGTH_SHORT).show()
            }
            val update = AppUpdateManager.checkForUpdate(BuildConfig.VERSION_NAME)
            if (update != null) {
                showUpdateModal(update)
            } else if (!silent) {
                Toast.makeText(this@MainActivity, "FeedTrainer is up to date (v${BuildConfig.VERSION_NAME})", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showUpdateModal(update: AppReleaseUpdate) {
        isUpdatePromptActive = true

        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_mandatory_update, null)
        val tvVersion = dialogView.findViewById<TextView>(R.id.tvUpdateVersion)
        val tvNotes = dialogView.findViewById<TextView>(R.id.tvUpdateNotes)
        val layoutProgress = dialogView.findViewById<LinearLayout>(R.id.layoutUpdateProgress)
        val progressBar = dialogView.findViewById<ProgressBar>(R.id.progressUpdateDownload)
        val tvStatus = dialogView.findViewById<TextView>(R.id.tvDownloadStatus)
        val btnStart = dialogView.findViewById<MaterialButton>(R.id.btnStartUpdate)

        tvVersion.text = "Update Available: ${update.tagName}"
        tvNotes.text = update.releaseNotes

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false) // Mandatory per user request
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        btnStart.setOnClickListener {
            btnStart.isEnabled = false
            layoutProgress.visibility = View.VISIBLE
            tvStatus.text = "Connecting and downloading APK..."

            lifecycleScope.launch {
                val apkFile = AppUpdateManager.downloadApk(this@MainActivity, update.downloadUrl) { pct ->
                    progressBar.progress = pct
                    tvStatus.text = "Downloading update... $pct%"
                }

                if (apkFile != null && apkFile.exists()) {
                    tvStatus.text = "Download complete. Launching installer..."
                    AppUpdateManager.installApk(this@MainActivity, apkFile)
                } else {
                    btnStart.isEnabled = true
                    tvStatus.text = "Download failed. Please check internet connection."
                    Toast.makeText(this@MainActivity, "Failed to download update APK", Toast.LENGTH_LONG).show()
                }
            }
        }

        dialog.show()
    }

    private fun runClassifierTest() {
        val text = etTestCaption.text.toString().trim()
        if (text.isEmpty()) {
            tvTestResult.text = "Please enter some caption text first"
            return
        }

        tvTestResult.text = "Evaluating on-device AI model..."

        lifecycleScope.launch {
            val testReel = Reel("sample_creator", text, listOf(text), "com.google.android.youtube")
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

    private fun showDailyCapDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_edit_daily_cap, null)
        val etCustomCap = dialogView.findViewById<EditText>(R.id.etCustomCap)
        val btnPreset20 = dialogView.findViewById<MaterialButton>(R.id.btnPreset20)
        val btnPreset40 = dialogView.findViewById<MaterialButton>(R.id.btnPreset40)
        val btnPreset75 = dialogView.findViewById<MaterialButton>(R.id.btnPreset75)
        val btnPreset100 = dialogView.findViewById<MaterialButton>(R.id.btnPreset100)
        val btnCancel = dialogView.findViewById<MaterialButton>(R.id.btnCancelCap)
        val btnSave = dialogView.findViewById<MaterialButton>(R.id.btnSaveCap)

        etCustomCap.setText(prefs.dailyLikeCap.toString())
        etCustomCap.setSelection(etCustomCap.text.length)

        btnPreset20.setOnClickListener { etCustomCap.setText("20"); etCustomCap.setSelection(2) }
        btnPreset40.setOnClickListener { etCustomCap.setText("40"); etCustomCap.setSelection(2) }
        btnPreset75.setOnClickListener { etCustomCap.setText("75"); etCustomCap.setSelection(2) }
        btnPreset100.setOnClickListener { etCustomCap.setText("100"); etCustomCap.setSelection(3) }

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        btnSave.setOnClickListener {
            val input = etCustomCap.text.toString().trim()
            val parsed = input.toIntOrNull()
            if (parsed == null || parsed < 5 || parsed > 500) {
                Toast.makeText(this, "Please enter a valid limit between 5 and 500", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            prefs.dailyLikeCap = parsed
            updateDashboard()
            Toast.makeText(this, "Daily like cap set to $parsed likes/day", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        dialog.show()
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
