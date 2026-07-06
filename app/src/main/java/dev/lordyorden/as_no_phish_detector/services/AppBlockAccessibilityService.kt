package dev.lordyorden.as_no_phish_detector.services

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import dev.lordyorden.as_no_phish_detector.BlockActivity
import dev.lordyorden.as_no_phish_detector.models.TempAppBlock
import dev.lordyorden.as_no_phish_detector.utilities.ConvexHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class AppBlockAccessibilityService : AccessibilityService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var packageSubscriptionJob: Job? = null
    private var currentPackageName: String? = null
    private var activeBlockEventId: String? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val packageName = event.packageName?.toString()
        if (packageName.isNullOrBlank()) return

        if (packageName == applicationContext.packageName) {
            currentPackageName = null
            activeBlockEventId = null
            packageSubscriptionJob?.cancel()
            packageSubscriptionJob = null
            return
        }

        if (packageName == currentPackageName && packageSubscriptionJob?.isActive == true) return

        currentPackageName = packageName
        activeBlockEventId = null
        subscribeToPackage(packageName)
    }

    override fun onInterrupt() {
        packageSubscriptionJob?.cancel()
        packageSubscriptionJob = null
    }

    override fun onDestroy() {
        packageSubscriptionJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun subscribeToPackage(packageName: String) {
        packageSubscriptionJob?.cancel()
        packageSubscriptionJob = scope.launch {
            try {
                ConvexHelper.getInstance().convexClient
                    .subscribe<TempAppBlock?>(
                        "blocks:getActiveForApp",
                        mapOf("packageName" to packageName)
                    )
                    .collect { result ->
                        result.onSuccess { block ->
                            if (block == null) {
                                activeBlockEventId = null
                                return@onSuccess
                            }

                            if (activeBlockEventId == block.eventId) return@onSuccess
                            activeBlockEventId = block.eventId
                            launchBlockActivity(block)
                        }.onFailure { error ->
                            Log.e(TAG, "App block subscription failed for packageName=$packageName", error)
                        }
                    }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.e(TAG, "App block subscription crashed for packageName=$packageName", error)
            }
        }
    }

    private fun launchBlockActivity(block: TempAppBlock) {
        startActivity(
            Intent(this, BlockActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(BlockActivity.EXTRA_EVENT_ID, block.eventId)
                putExtra(BlockActivity.EXTRA_PACKAGE_NAME, block.packageName)
            }
        )
    }

    companion object {
        private const val TAG = "AppBlockAccessibility"
    }
}
