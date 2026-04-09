package models

data class RoleItem(
    val id: Int,
    val name: String,
    val permissions: List<ModulePermission>
)

data class UserWithRoles(
    val id: Int,
    val username: String,
    val isActive: Boolean,
    val roles: List<String>,
    val employeeId: Int?,
    val employeeName: String?
)

data class UserAuthInfo(
    val id: Int,
    val username: String,
    val passwordHash: ByteArray,
    val passwordSalt: ByteArray,
    val isActive: Boolean,
    val roles: List<String>,
    val modulePermissions: Map<String, ModulePermission>,
    val canEdit: Boolean,
    val canDelete: Boolean
)
