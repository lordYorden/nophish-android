package dev.lordyorden.as_no_phish_detector.utilities

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.lordyorden.as_no_phish_detector.models.RelentNotificationInfo

object MaliciousNotificationPayloadParser {
    fun parse(json: String): RelentNotificationInfo {
        val payload = JsonParser.parseString(json).asJsonObject

        return RelentNotificationInfo(
            eventId = payload.requireString("eventId"),
            sourceUserId = payload.requireString("sourceUserId"),
            circleId = payload.optionalString("circleId"),
            title = payload.optionalString("title"),
            body = payload.requireString("body"),
            packageName = payload.requireString("packageName"),
            timestamp = payload.requireLong("timestamp"),
            contentHash = payload.requireString("contentHash"),
            urls = payload.optionalStringList("urls"),
            allowExternalAnalysis = payload.requireBoolean("allowExternalAnalysis")
        )
    }

    private fun JsonObject.requireString(fieldName: String): String {
        val element = get(fieldName)
        if (element == null || element.isJsonNull || element.asString.isBlank()) {
            throw IllegalArgumentException("Missing required field: $fieldName")
        }

        return element.asString
    }

    private fun JsonObject.requireLong(fieldName: String): Long {
        val element = get(fieldName)
        if (element == null || element.isJsonNull) {
            throw IllegalArgumentException("Missing required field: $fieldName")
        }

        return runCatching { element.asLong }
            .getOrElse { throw IllegalArgumentException("Invalid required long field: $fieldName", it) }
    }

    private fun JsonObject.requireBoolean(fieldName: String): Boolean {
        val element = get(fieldName)
        if (element == null || element.isJsonNull || !element.isJsonPrimitive || !element.asJsonPrimitive.isBoolean) {
            throw IllegalArgumentException("Missing or invalid required boolean field: $fieldName")
        }

        return element.asBoolean
    }

    private fun JsonObject.optionalString(fieldName: String): String? {
        val element = get(fieldName)
        return if (element == null || element.isJsonNull) null else element.asString.takeIf { it.isNotBlank() }
    }

    private fun JsonObject.optionalStringList(fieldName: String): List<String> {
        val element = get(fieldName)
        if (element == null || element.isJsonNull) return emptyList()

        return element.asJsonArray.map { it.asString }
    }
}
