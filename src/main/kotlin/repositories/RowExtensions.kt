package repositories

import io.vertx.sqlclient.Row
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

internal fun Row.getValueAny(column: String): Any? {
    try { return getValue(column) } catch (e: Exception) {}
    try { return getValue(column.lowercase()) } catch (e: Exception) {}
    try { return getValue(column.uppercase()) } catch (e: Exception) {}
    return null
}

internal fun Row.requireInt(column: String): Int {
    val value = getValueAny(column) ?: error("Missing $column")
    return when (value) {
        is Int -> value
        is Short -> value.toInt()
        is Long -> value.toInt()
        is Number -> value.toInt()
        else -> error("Invalid $column")
    }
}

internal fun Row.requireShort(column: String): Short {
    val value = getValueAny(column) ?: error("Missing $column")
    return when (value) {
        is Short -> value
        is Int -> value.toShort()
        is Long -> value.toShort()
        is Number -> value.toShort()
        else -> error("Invalid $column")
    }
}

internal fun Row.requireString(column: String): String {
    val value = getValueAny(column) as? String
    return value ?: error("Missing $column")
}

internal fun Row.requireDouble(column: String): Double {
    val value = getValueAny(column) ?: error("Missing $column")
    return when (value) {
        is Double -> value
        is Float -> value.toDouble()
        is Number -> value.toDouble()
        else -> error("Invalid $column")
    }
}

internal fun Row.requireLocalDateTime(column: String): LocalDateTime {
    return getValueAny(column) as? LocalDateTime ?: error("Missing $column")
}

internal fun Row.requireLocalDate(column: String): LocalDate {
    return getValueAny(column) as? LocalDate ?: error("Missing $column")
}

internal fun Row.getStringSafe(column: String): String? {
    return getValueAny(column) as? String
}

internal fun Row.getShortSafe(column: String): Short? {
    val value = getValueAny(column) ?: return null
    return when (value) {
        is Short -> value
        is Int -> value.toShort()
        is Long -> value.toShort()
        is Number -> value.toShort()
        else -> null
    }
}

internal fun Row.getBigDecimalSafe(column: String): BigDecimal? {
    val value = getValueAny(column) ?: return null
    return when (value) {
        is BigDecimal -> value
        is Number -> BigDecimal(value.toString())
        else -> null
    }
}

internal fun Row.getLocalDateTimeSafe(column: String): LocalDateTime? {
    return getValueAny(column) as? LocalDateTime
}

internal fun Row.getLocalDateSafe(column: String): LocalDate? {
    return getValueAny(column) as? LocalDate
}

internal fun Row.getDoubleSafe(column: String): Double? {
    val value = getValueAny(column) ?: return null
    return when (value) {
        is Double -> value
        is Float -> value.toDouble()
        is Number -> value.toDouble()
        else -> null
    }
}
