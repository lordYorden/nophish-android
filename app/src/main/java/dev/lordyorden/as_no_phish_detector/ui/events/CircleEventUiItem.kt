package dev.lordyorden.as_no_phish_detector.ui.events

import dev.lordyorden.as_no_phish_detector.models.CircleMember
import dev.lordyorden.as_no_phish_detector.models.Event

data class CircleEventUiItem(
    val event: Event,
    val member: CircleMember,
    val isBlocked: Boolean,
)
