package dev.lordyorden.as_no_phish_detector.utilities

import android.content.Context
import androidx.datastore.core.DataStore
import dev.lordyorden.as_no_phish_detector.datastore.AiSecurityAnalysisSettings
import dev.lordyorden.as_no_phish_detector.utilities.datastore.AiSecurityAnalysisSettingsSerializer
import kotlinx.coroutines.flow.first

class AiSecurityAnalysisSettingsStore private constructor(context: Context) {
    private val dataStore: DataStore<AiSecurityAnalysisSettings> = EncryptedDataStoreFactory.create(
        context.applicationContext,
        DATASTORE_FILE_NAME,
        AiSecurityAnalysisSettingsSerializer
    )

    suspend fun isEnabled(): Boolean = dataStore.data.first().enabled

    suspend fun setEnabled(enabled: Boolean) {
        dataStore.updateData { current ->
            current.toBuilder()
                .setEnabled(enabled)
                .build()
        }
    }

    companion object {
        private const val DATASTORE_FILE_NAME = "ai_security_analysis_settings.pb"

        @Volatile
        private var instance: AiSecurityAnalysisSettingsStore? = null

        fun getInstance(context: Context): AiSecurityAnalysisSettingsStore {
            return instance ?: synchronized(this) {
                instance ?: AiSecurityAnalysisSettingsStore(context).also { instance = it }
            }
        }
    }
}
