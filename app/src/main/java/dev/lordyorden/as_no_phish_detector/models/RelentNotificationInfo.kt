package dev.lordyorden.as_no_phish_detector.models

data class RelentNotificationInfo(
    var eventId: String,
    var sourceUserId: String,
    var circleId: String? = null,
    var title: String? = null,
    var body: String,
    var packageName: String,
    var timestamp: Long,
    var contentHash: String,
    var urls: List<String> = listOf(),
    var allowExternalAnalysis: Boolean
)
