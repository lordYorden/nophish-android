package dev.lordyorden.as_no_phish_detector.models

import kotlinx.serialization.Serializable

@Serializable
data class CircleEvent(
    val action: String,
    val timestamp: Double,
    val userId: String,
    val circleId: String,
    val eventId: String,
    val contentHash: String,
    val isBlocked: Boolean,
    val packageName: String? = null,
    val requiresAction: Boolean? = null,
) {
    init {
        require(circleId.isNotBlank()) { "circleId must not be blank" }
        require(eventId.isNotBlank()) { "eventId must not be blank" }
        require(contentHash.isNotBlank()) { "contentHash must not be blank" }
    }

    fun toEvent(): Event {
        return Event(
            action = action,
            timestamp = timestamp,
            userId = userId,
            circleId = circleId,
            eventId = eventId,
            contentHash = contentHash,
            packageName = packageName,
            requiresAction = requiresAction,
        )
    }
}
