package auth

import io.vertx.core.buffer.Buffer
import io.vertx.sqlclient.Row

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

internal fun Row.requireString(column: String): String {
    val value = getValueAny(column) as? String
    return value ?: error("Missing $column")
}

internal fun Row.getStringSafe(column: String): String? {
    return getValueAny(column) as? String
}

internal fun Row.getBooleanSafe(column: String): Boolean? {
    val value = getValueAny(column) ?: return null
    return when (value) {
        is Boolean -> value
        is Number -> value.toInt() != 0
        is String -> value == "1" || value.equals("true", ignoreCase = true)
        else -> null
    }
}

internal fun Row.getIntSafe(column: String): Int? {
    val value = getValueAny(column) ?: return null
    return when (value) {
        is Int -> value
        is Short -> value.toInt()
        is Long -> value.toInt()
        is Number -> value.toInt()
        else -> null
    }
}

internal fun Row.getBufferSafe(column: String): Buffer? {
    return getValueAny(column) as? Buffer
}
