package dev.lordyorden.as_no_phish_detector

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import dev.lordyorden.as_no_phish_detector.databinding.ActivityBlockBinding
import dev.lordyorden.as_no_phish_detector.models.TempAppBlock
import dev.lordyorden.as_no_phish_detector.utilities.ConvexHelper
import dev.lordyorden.as_no_phish_detector.utilities.ImageLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class BlockActivity : AppCompatActivity() {
    private lateinit var binding: ActivityBlockBinding
    private var blockJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val eventId = requireIntentString(EXTRA_EVENT_ID)
        val packageName = requireIntentString(EXTRA_PACKAGE_NAME)

        binding = ActivityBlockBinding.inflate(layoutInflater)
        setContentView(binding.root)

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = Unit
            }
        )

        showBlock(eventId, packageName)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)

        val eventId = requireIntentString(EXTRA_EVENT_ID)
        val packageName = requireIntentString(EXTRA_PACKAGE_NAME)
        showBlock(eventId, packageName)
    }

    override fun onDestroy() {
        blockJob?.cancel()
        super.onDestroy()
    }

    private fun showBlock(eventId: String, packageName: String) {
        ImageLoader.getInstance().loadAppIcon(packageName, binding.ivBlockedAppIcon)
        subscribeToBlock(eventId, packageName)
    }

    private fun subscribeToBlock(eventId: String, packageName: String) {
        blockJob?.cancel()
        blockJob = lifecycleScope.launch {
            try {
                ConvexHelper.getInstance().convexClient
                    .subscribe<TempAppBlock?>(
                        "blocks:getActiveByEvent",
                        mapOf(EXTRA_EVENT_ID to eventId)
                    )
                    .collect { result ->
                        result.onSuccess { block ->
                            if (block == null) {
                                closeBlockerToPreviousTask()
                                return@onSuccess
                            }

                            if (block.packageName != packageName) {
                                Log.e(TAG, "Block packageName mismatch for eventId=$eventId")
                                closeBlockerToPreviousTask()
                            }
                        }.onFailure { error ->
                            Log.e(TAG, "Block subscription failed for eventId=$eventId", error)
                        }
                    }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.e(TAG, "BlockActivity failed for eventId=$eventId", error)
                throw error
            }
        }
    }

    private fun closeBlockerToPreviousTask() {
        moveTaskToBack(true)
        finish()
    }

    private fun requireIntentString(key: String): String {
        val value = intent.getStringExtra(key)
        require(!value.isNullOrBlank()) { "Missing required intent extra: $key" }
        return value
    }

    companion object {
        private const val TAG = "BlockActivity"
        const val EXTRA_EVENT_ID = "eventId"
        const val EXTRA_PACKAGE_NAME = "packageName"
    }
}
