package server

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

internal fun html(value: String): String {
    return value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}

internal fun format2(value: Double): String = String.format("%.2f", value)
internal fun format3(value: Double): String = String.format("%.3f", value)

internal fun unitCost(total: Double, quantity: Double): Double {
    if (quantity <= 0) return 0.0
    return total / quantity
}

internal fun calculateSaleUnitPrice(total: Double, quantity: Double): Double {
    val cost = unitCost(total, quantity)
    if (cost <= 0.0) return 0.0
    return cost * (1 + SALE_MARKUP)
}

internal fun parseDateTime(value: String?): LocalDateTime {
    if (value.isNullOrBlank()) return LocalDateTime.now()
    val trimmed = value.trim()
    return try {
        if (trimmed.length == 10) {
            LocalDate.parse(trimmed, DateTimeFormatter.ISO_LOCAL_DATE).atStartOfDay()
        } else {
            LocalDateTime.parse(trimmed, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        }
    } catch (e: Exception) {
        LocalDateTime.now()
    }
}

internal fun parseDateTimeNullable(value: String?): LocalDateTime? {
    if (value.isNullOrBlank()) return null
    val trimmed = value.trim()
    return try {
        if (trimmed.length == 10) {
            LocalDate.parse(trimmed, DateTimeFormatter.ISO_LOCAL_DATE).atStartOfDay()
        } else {
            LocalDateTime.parse(trimmed, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        }
    } catch (e: Exception) {
        null
    }
}
