package com.feedtrainer.service
import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
/**
 * Executes a decision — but ONLY what the user opted into, visibly, and paced.
 * Covert mass-automation gets accounts banned and is unethical; this stays a
 * consent-first curation tool: daily cap + min spacing + user-visible toast.
 */
object ActionExecutor {
    private const val DAILY_CAP = 40
    private const val MIN_SPACING_MS = 4000L
    private var actionsToday = 0
    private var lastActionAt = 0L
    var userEnabledActions = false   // flipped true only after explicit opt-in

    fun maybeAct(svc: AccessibilityService, root: AccessibilityNodeInfo, decision: String) {
        if (!userEnabledActions || decision == "SKIP") return
        val now = System.currentTimeMillis()
        if (actionsToday >= DAILY_CAP || now - lastActionAt < MIN_SPACING_MS) return

        val label = if (decision == "LIKE") "Like" else "Not interested"
        val node = findClickableByText(root, label) ?: return
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        actionsToday++; lastActionAt = now
        Toast.makeText(svc, "FeedTrainer: $label (undo in app)", Toast.LENGTH_SHORT).show()
    }

    private fun findClickableByText(node: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
        node ?: return null
        val hit = node.findAccessibilityNodeInfosByText(text).firstOrNull { it.isClickable }
        if (hit != null) return hit
        for (i in 0 until node.childCount) findClickableByText(node.getChild(i), text)?.let { return it }
        return null
    }
    fun resetDailyCounter() { actionsToday = 0 }
}
