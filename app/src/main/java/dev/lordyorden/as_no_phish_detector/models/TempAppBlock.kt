package dev.lordyorden.as_no_phish_detector.models

import kotlinx.serialization.Serializable

@Serializable
data class TempAppBlock(
    val eventId: String,
    val circleId: String,
    val targetId: String,
    val packageName: String,
    val blockedBy: String,
    val createdAt: Double,
) {
    init {
        require(eventId.isNotBlank()) { "eventId must not be blank" }
        require(circleId.isNotBlank()) { "circleId must not be blank" }
        require(targetId.isNotBlank()) { "targetId must not be blank" }
        require(packageName.isNotBlank()) { "packageName must not be blank" }
        require(blockedBy.isNotBlank()) { "blockedBy must not be blank" }
    }
}
