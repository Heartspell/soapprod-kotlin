package models

data class AuthSession(
    val userId: Int,
    val username: String,
    val roles: Set<String>,
    val modulePermissions: Map<String, ModulePermission>,
    val canEdit: Boolean,
    val canDelete: Boolean
)
