package auth

import io.vertx.core.buffer.Buffer
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.Tuple
import models.*

class AuthRepository(private val pool: Pool) {

    suspend fun ensureSchema() {
        pool.query("EXEC sp_EnsureAuthSchema").execute().coAwait()
        pool.query("EXEC sp_EnsureRolePermissionsSchema").execute().coAwait()
    }

    suspend fun getRoles(): List<RoleItem> {
        val rows = pool.query("{call sp_ListRoles}").execute().coAwait()

        val roleIds = mutableListOf<Int>()
        val roleNames = mutableMapOf<Int, String>()
        val rolePermissions = mutableMapOf<Int, LinkedHashMap<String, ModulePermission>>()

        for (row in rows) {
            val id = row.requireInt("Id")
            val name = row.requireString("Name")

            if (!roleIds.contains(id)) {
                roleIds.add(id)
                roleNames[id] = name
                rolePermissions[id] = defaultModulePermissions()
            }

            val moduleKey = row.getStringSafe("ModuleKey")?.trim() ?: ""
            val label = MODULE_PERMISSION_LABELS[moduleKey] ?: continue
            rolePermissions[id]!![moduleKey] = ModulePermission(
                moduleKey = moduleKey,
                label = label,
                canEdit = row.getBooleanSafe("CanEdit") ?: false,
                canDelete = row.getBooleanSafe("CanDelete") ?: false
            )
        }

        val result = mutableListOf<RoleItem>()
        for (id in roleIds) {
            val permissions = rolePermissions[id]!!.values.toList()
            result.add(RoleItem(id, roleNames[id]!!, permissions))
        }
        return result
    }

    suspend fun getRoleIdByName(name: String): Int? {
        val rows = pool.preparedQuery("{call sp_GetRoleIdByName(?)}").execute(Tuple.of(name)).coAwait()
        val it = rows.iterator()
        return if (it.hasNext()) it.next().requireInt("Id") else null
    }

    suspend fun getUserIdByUsername(username: String): Int? {
        val rows = pool.preparedQuery("{call sp_GetUserIdByUsername(?)}").execute(Tuple.of(username)).coAwait()
        val it = rows.iterator()
        return if (it.hasNext()) it.next().requireInt("Id") else null
    }

    suspend fun saveAdminSeed(hash: ByteArray, salt: ByteArray) {
        pool.preparedQuery("{call sp_EnsureAuthSeed(?, ?)}")
            .execute(Tuple.of(Buffer.buffer(hash), Buffer.buffer(salt))).coAwait()
    }

    suspend fun saveRolePermission(roleId: Int, moduleKey: String, canEdit: Boolean, canDelete: Boolean) {
        pool.preparedQuery("{call sp_SaveRolePermissions(?, ?, ?, ?)}")
            .execute(Tuple.of(roleId, moduleKey, canEdit, canDelete)).coAwait()
    }

    suspend fun saveUserWithRoles(
        id: Int,
        username: String,
        isActive: Boolean,
        passwordHash: ByteArray?,
        passwordSalt: ByteArray?,
        roleIds: List<Int>,
        employeeId: Int?
    ): Int {
        val roleCsv = roleIds.distinct().joinToString(",")
        if (passwordHash != null && passwordSalt != null) {
            val rows = pool.preparedQuery(
                "EXEC sp_SaveUserWithRoles @Id=?, @Username=?, @IsActive=?, @EmployeeId=?, @PasswordHash=?, @PasswordSalt=?, @RoleIdsCsv=?"
            ).execute(Tuple.of(id, username, isActive, employeeId, Buffer.buffer(passwordHash), Buffer.buffer(passwordSalt), roleCsv)).coAwait()
            val it = rows.iterator()
            return if (it.hasNext()) it.next().requireInt("Id") else id
        }
        val rows = pool.preparedQuery(
            "EXEC sp_SaveUserWithRoles @Id=?, @Username=?, @IsActive=?, @EmployeeId=?, @RoleIdsCsv=?"
        ).execute(Tuple.of(id, username, isActive, employeeId, roleCsv)).coAwait()
        val it = rows.iterator()
        return if (it.hasNext()) it.next().requireInt("Id") else id
    }

    suspend fun deleteUser(id: Int) {
        pool.preparedQuery("{call sp_DeleteUser(?)}").execute(Tuple.of(id)).coAwait()
    }

    suspend fun getUserRoleIds(userId: Int): List<Int> {
        val rows = pool.preparedQuery("{call sp_GetUserRoleIds(?)}").execute(Tuple.of(userId)).coAwait()
        val result = mutableListOf<Int>()
        for (row in rows) {
            result.add(row.requireInt("RoleId"))
        }
        return result
    }

    suspend fun getUsersWithRoles(): List<UserWithRoles> {
        val rows = pool.query("{call sp_ListUsersWithRoles}").execute().coAwait()

        val userIds = mutableListOf<Int>()
        val usernames = mutableMapOf<Int, String>()
        val activeFlags = mutableMapOf<Int, Boolean>()
        val employeeIds = mutableMapOf<Int, Int?>()
        val employeeNames = mutableMapOf<Int, String?>()
        val userRoles = mutableMapOf<Int, MutableList<String>>()

        for (row in rows) {
            val id = row.requireInt("Id")
            if (!userIds.contains(id)) {
                userIds.add(id)
                usernames[id] = row.requireString("Username")
                activeFlags[id] = row.getBooleanSafe("IsActive") ?: false
                employeeIds[id] = row.getIntSafe("EmployeeID")
                employeeNames[id] = row.getStringSafe("EmployeeName")
                userRoles[id] = mutableListOf()
            }
            val role = row.getStringSafe("Name")
            if (!role.isNullOrBlank()) {
                userRoles[id]!!.add(role)
            }
        }

        val result = mutableListOf<UserWithRoles>()
        for (id in userIds) {
            result.add(
                UserWithRoles(
                    id = id,
                    username = usernames[id]!!,
                    isActive = activeFlags[id]!!,
                    roles = userRoles[id]!!,
                    employeeId = employeeIds[id],
                    employeeName = employeeNames[id]
                )
            )
        }
        return result
    }

    suspend fun getUserAuth(username: String): UserAuthInfo? {
        val rows = pool.preparedQuery("{call sp_GetUserAuth(?)}").execute(Tuple.of(username)).coAwait()

        var id: Int? = null
        var userName: String? = null
        var hash: ByteArray? = null
        var salt: ByteArray? = null
        var isActive = false
        val roles = mutableListOf<String>()
        val modulePermissions = defaultModulePermissions()

        for (row in rows) {
            if (id == null) {
                id = row.requireInt("Id")
                userName = row.requireString("Username")
                hash = row.getBufferSafe("PasswordHash")?.bytes
                salt = row.getBufferSafe("PasswordSalt")?.bytes
                isActive = row.getBooleanSafe("IsActive") ?: false
            }
            val role = row.getStringSafe("Name")
            if (!role.isNullOrBlank()) {
                roles.add(role)
            }
            val moduleKey = row.getStringSafe("ModuleKey")?.trim() ?: ""
            if (moduleKey.isNotBlank() && MODULE_PERMISSION_LABELS.containsKey(moduleKey)) {
                val current = modulePermissions.getValue(moduleKey)
                val newCanEdit = current.canEdit || (row.getBooleanSafe("CanEdit") ?: false)
                val newCanDelete = current.canDelete || (row.getBooleanSafe("CanDelete") ?: false)
                modulePermissions[moduleKey] = current.copy(canEdit = newCanEdit, canDelete = newCanDelete)
            }
        }

        if (id == null || userName == null || hash == null || salt == null) return null
        return UserAuthInfo(
            id = id,
            username = userName,
            passwordHash = hash,
            passwordSalt = salt,
            isActive = isActive,
            roles = roles,
            modulePermissions = modulePermissions,
            canEdit = modulePermissions.values.any { it.canEdit },
            canDelete = modulePermissions.values.any { it.canDelete }
        )
    }
}
