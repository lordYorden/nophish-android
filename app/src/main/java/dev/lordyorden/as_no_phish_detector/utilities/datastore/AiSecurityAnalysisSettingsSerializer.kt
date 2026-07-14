package dev.lordyorden.as_no_phish_detector.utilities.datastore

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import com.google.protobuf.InvalidProtocolBufferException
import dev.lordyorden.as_no_phish_detector.datastore.AiSecurityAnalysisSettings
import java.io.InputStream
import java.io.OutputStream

object AiSecurityAnalysisSettingsSerializer : Serializer<AiSecurityAnalysisSettings> {
    override val defaultValue: AiSecurityAnalysisSettings = AiSecurityAnalysisSettings.getDefaultInstance()

    override suspend fun readFrom(input: InputStream): AiSecurityAnalysisSettings {
        return try {
            AiSecurityAnalysisSettings.parseFrom(input)
        } catch (exception: InvalidProtocolBufferException) {
            throw CorruptionException("Unable to read AI security analysis settings", exception)
        }
    }

    override suspend fun writeTo(t: AiSecurityAnalysisSettings, output: OutputStream) {
        t.writeTo(output)
    }
}
