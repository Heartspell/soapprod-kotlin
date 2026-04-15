package server

import auth.AuthSession
import io.vertx.ext.web.RoutingContext

internal fun AppServer.requireAuth(
    ctx: RoutingContext,
    roles: Set<String>?,
    permission: SessionPermission? = null,
    moduleKey: String? = null
): AuthSession? {
    val sid = readSid(ctx)
    val session = if (sid != null) sessions[sid] else null
    if (session == null) {
        ctx.response().setStatusCode(303).putHeader("Location", "/login").end()
        return null
    }
    if (roles != null && roles.isNotEmpty()) {
        val ok = session.roles.any { role -> roles.any { it.equals(role, ignoreCase = true) } }
        if (!ok) {
            redirectWithAlert(ctx, "/", "Access denied")
            return null
        }
    }
    if (permission != null && !hasPermission(session, moduleKey, permission)) {
        redirectWithAlert(ctx, "/", "Permission denied")
        return null
    }
    return session
}

internal fun AppServer.requireApiAuth(
    ctx: RoutingContext,
    roles: Set<String>?,
    permission: SessionPermission? = null,
    moduleKey: String? = null
): AuthSession? {
    val sid = readSid(ctx)
    val session = if (sid != null) sessions[sid] else null
    if (session == null) {
        apiError(ctx, 401, "Unauthorized")
        return null
    }
    if (roles != null && roles.isNotEmpty()) {
        val ok = session.roles.any { role -> roles.any { it.equals(role, ignoreCase = true) } }
        if (!ok) {
            apiError(ctx, 403, "Access denied")
            return null
        }
    }
    if (permission != null && !hasPermission(session, moduleKey, permission)) {
        apiError(ctx, 403, "Permission denied")
        return null
    }
    return session
}

internal fun hasPermission(session: AuthSession, moduleKey: String?, permission: SessionPermission): Boolean {
    if (moduleKey.isNullOrBlank()) {
        return when (permission) {
            SessionPermission.EDIT -> session.canEdit
            SessionPermission.DELETE -> session.canDelete
        }
    }
    val modulePermission = session.modulePermissions[moduleKey] ?: return false
    return when (permission) {
        SessionPermission.EDIT -> modulePermission.canEdit
        SessionPermission.DELETE -> modulePermission.canDelete
    }
}

internal fun hasRole(session: AuthSession, role: String): Boolean =
    session.roles.any { it.equals(role, ignoreCase = true) }

internal fun canEdit(session: AuthSession, moduleKey: String): Boolean =
    session.modulePermissions[moduleKey]?.canEdit == true

internal fun canDelete(session: AuthSession, moduleKey: String): Boolean =
    session.modulePermissions[moduleKey]?.canDelete == true

internal fun navigationItems(session: AuthSession): List<Map<String, String>> {
    val items = mutableListOf(
        mapOf("to" to "/", "label" to "Dashboard", "group" to "Workspace")
    )
    if (hasRole(session, "Admin") || hasRole(session, "Purchasing")) {
        items.add(mapOf("to" to "/purchase", "label" to "Purchase", "group" to "Operations"))
        items.add(mapOf("to" to "/units", "label" to "Units", "group" to "Directories"))
        items.add(mapOf("to" to "/raw-materials", "label" to "Raw materials", "group" to "Directories"))
    }
    if (hasRole(session, "Admin")) {
        items.add(mapOf("to" to "/positions", "label" to "Positions", "group" to "Directories"))
        items.add(mapOf("to" to "/employees", "label" to "Employees", "group" to "Directories"))
    }
    items.add(mapOf("to" to "/salary", "label" to "Salaries", "group" to "Finance"))
    if (hasRole(session, "Admin") || hasRole(session, "Sales") || hasRole(session, "Production")) {
        items.add(mapOf("to" to "/products", "label" to "Products", "group" to "Recipes and stock"))
    }
    if (hasRole(session, "Admin") || hasRole(session, "Production")) {
        items.add(mapOf("to" to "/ingredients", "label" to "Ingredients list", "group" to "Recipes and stock"))
        items.add(mapOf("to" to "/production", "label" to "Production", "group" to "Operations"))
        items.add(mapOf("to" to "/production-requests", "label" to "Production Requests", "group" to "Operations"))
    }
    if (hasRole(session, "Admin") || hasRole(session, "Sales")) {
        items.add(mapOf("to" to "/sales", "label" to "Sales", "group" to "Operations"))
        items.add(mapOf("to" to "/reports", "label" to "Reports", "group" to "Analytics"))
    }
    if (hasRole(session, "Admin")) {
        items.add(mapOf("to" to "/budget", "label" to "Budget", "group" to "Finance"))
        items.add(mapOf("to" to "/admin/users", "label" to "Users", "group" to "Administration"))
    }
    return items
}

internal fun buildNav(session: AuthSession): String {
    val grouped = linkedMapOf<String, MutableList<Map<String, String>>>()
    for (item in navigationItems(session)) {
        grouped.getOrPut(item.getValue("group")) { mutableListOf() }.add(item)
    }
    return grouped.entries.joinToString("\n") { (group, items) ->
        val links = items.joinToString("\n") { item ->
            "<li><a href=\"${item.getValue("to")}\">${html(item.getValue("label"))}</a></li>"
        }
        "<div class=\"nav-group\"><div class=\"nav-group-title\">${html(group)}</div><ul>$links</ul></div>"
    }
}

internal fun permissionNotice(message: String): String =
    "<section class=\"section-card permission-note\"><p>${html(message)}</p></section>"

internal fun editFormOrNotice(session: AuthSession, moduleKey: String, content: String, message: String): String =
    if (canEdit(session, moduleKey)) content else permissionNotice(message)

internal fun cancelEditLink(path: String, isEditing: Boolean): String =
    if (isEditing) "<a href=\"$path\" class=\"secondary-link\">Cancel</a>" else ""

internal fun rowActions(
    session: AuthSession,
    moduleKey: String,
    editHref: String? = null,
    deleteAction: String? = null,
    id: Number? = null,
    deleteLabel: String = "Delete"
): String {
    val parts = mutableListOf<String>()
    if (editHref != null && canEdit(session, moduleKey)) {
        parts += "<a href=\"$editHref\" class=\"action-link action-link-edit\">Edit</a>"
    }
    if (deleteAction != null && id != null && canDelete(session, moduleKey)) {
        parts += "<form method=\"post\" action=\"$deleteAction\" class=\"action-form\">" +
            "<input type=\"hidden\" name=\"id\" value=\"$id\"/>" +
            "<button type=\"submit\" class=\"action-link action-link-delete\">${html(deleteLabel)}</button></form>"
    }
    return if (parts.isEmpty()) "" else "<div class=\"action-row\">${parts.joinToString("")}</div>"
}
