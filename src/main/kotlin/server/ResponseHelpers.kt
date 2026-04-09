package server

import io.vertx.core.json.Json
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.Route
import io.vertx.ext.web.RoutingContext
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

internal fun apiJson(ctx: RoutingContext, payload: Any, statusCode: Int = 200) {
    ctx.response()
        .setStatusCode(statusCode)
        .putHeader("Content-Type", "application/json; charset=utf-8")
        .end(Json.encode(payload))
}

internal fun apiError(ctx: RoutingContext, statusCode: Int, message: String) {
    apiJson(ctx, mapOf("error" to message), statusCode)
}

internal fun redirect(ctx: RoutingContext, path: String) {
    ctx.response().setStatusCode(303).putHeader("Location", path).end()
}

internal fun redirectWithAlert(ctx: RoutingContext, path: String, message: String) {
    val separator = if (path.contains("?")) "&" else "?"
    val encoded = URLEncoder.encode(message, StandardCharsets.UTF_8)
    redirect(ctx, "$path${separator}error=$encoded")
}

internal fun redirectBackWithAlert(ctx: RoutingContext, message: String) {
    val referer = ctx.request().getHeader("Referer")
    var target: String? = null
    if (!referer.isNullOrBlank()) {
        try {
            target = URI(referer).path
        } catch (e: Exception) {
            // ignore malformed referer
        }
    }
    if (target.isNullOrBlank()) {
        target = ctx.normalizedPath()
    }
    redirectWithAlert(ctx, target, message)
}

internal fun badRequest(ctx: RoutingContext, message: String) {
    redirectBackWithAlert(ctx, message)
}

internal fun presentableErrorMessage(ex: Throwable, path: String = ""): String {
    val raw = ex.message?.trim() ?: ""
    val lowered = raw.lowercase()
    if ("delete" in lowered && ("reference" in lowered || "fk_" in lowered || "constraint" in lowered)) {
        return when {
            path.contains("/units") -> "Cannot delete this unit because it is used in raw materials or products."
            path.contains("/positions") -> "Cannot delete this position because it is assigned to employees."
            path.contains("/employees") -> "Cannot delete this employee because it is used in salaries, purchases, production or sales."
            path.contains("/raw-materials") -> "Cannot delete this raw material because it is used in ingredients or purchases."
            path.contains("/products") -> "Cannot delete this product because it is used in ingredients, production or sales."
            path.contains("/ingredients") -> "Cannot delete this ingredient because it is linked to a product recipe."
            path.contains("/purchase") -> "Cannot delete this purchase because it is linked to inventory history."
            path.contains("/production") -> "Cannot delete this production record because it is linked to inventory history."
            path.contains("/sales") -> "Cannot delete this sale because it is linked to inventory or finance history."
            path.contains("/admin/users") -> "Cannot delete this user because it is still linked to roles or audit data."
            else -> "Cannot delete this record because it is used in other data."
        }
    }
    if ("reference" in lowered || "fk_" in lowered) {
        return "Operation failed because this record is linked to other data."
    }
    if (raw.isBlank()) return "Server error"
    return raw
}

internal suspend fun <T> safeLookup(block: suspend () -> List<T>): List<T> {
    return try {
        block()
    } catch (e: Exception) {
        emptyList()
    }
}

internal fun jsonBody(ctx: RoutingContext): JsonObject {
    return try {
        ctx.body().asJsonObject() ?: JsonObject()
    } catch (e: Exception) {
        JsonObject()
    }
}

internal fun serveStaticFile(ctx: RoutingContext, path: Path) {
    val normalized = path.normalize()
    val frontendRoot = Paths.get("frontend").normalize()
    if (!normalized.startsWith(frontendRoot) || !Files.exists(normalized) || Files.isDirectory(normalized)) {
        ctx.response().setStatusCode(404).end()
        return
    }
    val contentType = when (normalized.fileName.toString().substringAfterLast('.', "").lowercase()) {
        "css" -> "text/css; charset=utf-8"
        "js" -> "application/javascript; charset=utf-8"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "svg" -> "image/svg+xml"
        else -> "application/octet-stream"
    }
    ctx.response()
        .putHeader("Content-Type", contentType)
        .sendFile(normalized.toString())
}

internal fun readSid(ctx: RoutingContext): String? {
    val cookieHeader = ctx.request().getHeader("Cookie") ?: return null
    for (cookie in cookieHeader.split(";")) {
        val parts = cookie.trim().split("=", limit = 2)
        if (parts.size == 2 && parts[0] == "SID") return parts[1]
    }
    return null
}

internal fun setSessionCookie(ctx: RoutingContext, sid: String) {
    ctx.response().putHeader("Set-Cookie", "SID=$sid; HttpOnly; Path=/")
}

internal fun clearSessionCookie(ctx: RoutingContext) {
    ctx.response().putHeader("Set-Cookie", "SID=; Max-Age=0; Path=/")
}

internal fun Route.coroutineHandler(block: suspend (RoutingContext) -> Unit) {
    handler { ctx ->
        CoroutineScope(ctx.vertx().dispatcher()).launch {
            try {
                block(ctx)
            } catch (ex: Exception) {
                if (!ctx.response().ended()) {
                    val message = presentableErrorMessage(ex, ctx.normalizedPath())
                    if (ctx.normalizedPath().startsWith("/api/")) {
                        apiError(ctx, 500, message)
                    } else if (ctx.request().method().name() == "POST") {
                        redirectBackWithAlert(ctx, message)
                    } else {
                        ctx.response().setStatusCode(500).end(message)
                    }
                }
            }
        }
    }
}
