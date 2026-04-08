import io.vertx.core.buffer.Buffer
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.Tuple
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

data class AuthSession(
    val userId: Int,
    val username: String,
    val roles: Set<String>,
    val modulePermissions: Map<String, ModulePermission>,
    val canEdit: Boolean,
    val canDelete: Boolean
)

private fun Row.getValueAny(column: String): Any? {
    return runCatching { getValue(column) }.getOrNull()
        ?: runCatching { getValue(column.lowercase()) }.getOrNull()
        ?: runCatching { getValue(column.uppercase()) }.getOrNull()
}

private fun Row.requireInt(column: String): Int {
    val value = getValueAny(column) ?: error("Missing $column")
    return when (value) {
        is Int -> value
        is Short -> value.toInt()
        is Long -> value.toInt()
        is Number -> value.toInt()
        else -> error("Invalid $column")
    }
}

private fun Row.requireString(column: String): String =
    (getValueAny(column) as? String) ?: error("Missing $column")

private fun Row.getStringSafe(column: String): String? =
    getValueAny(column) as? String

private fun Row.getBooleanSafe(column: String): Boolean? {
    val value = getValueAny(column) ?: return null
    return when (value) {
        is Boolean -> value
        is Number -> value.toInt() != 0
        is String -> value == "1" || value.equals("true", true)
        else -> null
    }
}

private fun Row.getIntSafe(column: String): Int? {
    val value = getValueAny(column) ?: return null
    return when (value) {
        is Int -> value
        is Short -> value.toInt()
        is Long -> value.toInt()
        is Number -> value.toInt()
        else -> null
    }
}

private fun Row.getBufferSafe(column: String): Buffer? =
    getValueAny(column) as? Buffer

class AuthRepository(private val pool: Pool) {
    suspend fun ensureSchema() {
        pool.query("EXEC sp_EnsureAuthSchema").execute().coAwait()
        pool.query("EXEC sp_EnsureRolePermissionsSchema").execute().coAwait()
    }

    suspend fun getRoles(): List<RoleItem> {
        val rows = pool.query("{call sp_ListRoles}").execute().coAwait()
        data class RoleAggregate(
            val id: Int,
            val name: String,
            val permissions: LinkedHashMap<String, ModulePermission>
        )
        val roles = LinkedHashMap<Int, RoleAggregate>()
        for (row in rows) {
            val id = row.requireInt("Id")
            val name = row.requireString("Name")
            val aggregate = roles.getOrPut(id) {
                RoleAggregate(id, name, defaultModulePermissions())
            }
            val moduleKey = row.getStringSafe("ModuleKey")?.trim().orEmpty()
            val label = MODULE_PERMISSION_LABELS[moduleKey] ?: continue
            aggregate.permissions[moduleKey] = ModulePermission(
                moduleKey = moduleKey,
                label = label,
                canEdit = row.getBooleanSafe("CanEdit") ?: false,
                canDelete = row.getBooleanSafe("CanDelete") ?: false
            )
        }
        return roles.values.map { RoleItem(it.id, it.name, it.permissions.values.toList()) }
    }

    suspend fun getRoleIdByName(name: String): Int? {
        val rows = pool.preparedQuery("{call sp_GetRoleIdByName(?)}").execute(Tuple.of(name)).coAwait()
        val it = rows.iterator()
        return if (it.hasNext()) it.next().requireInt("Id") else null
    }

    suspend fun getUserIdByUsername(username: String): Int? {
        val rows = pool.preparedQuery("{call sp_GetUserIdByUsername(?)}")
                .execute(Tuple.of(username)).coAwait()
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
            )
                .execute(Tuple.of(id, username, isActive, employeeId, Buffer.buffer(passwordHash), Buffer.buffer(passwordSalt), roleCsv))
                .coAwait()
            val it = rows.iterator()
            return if (it.hasNext()) it.next().requireInt("Id") else id
        }
        val rows = pool.preparedQuery(
            "EXEC sp_SaveUserWithRoles @Id=?, @Username=?, @IsActive=?, @EmployeeId=?, @RoleIdsCsv=?"
        )
            .execute(Tuple.of(id, username, isActive, employeeId, roleCsv)).coAwait()
        val it = rows.iterator()
        return if (it.hasNext()) it.next().requireInt("Id") else id
    }

    suspend fun deleteUser(id: Int) {
        pool.preparedQuery("{call sp_DeleteUser(?)}").execute(Tuple.of(id)).coAwait()
    }

    suspend fun getUserRoleIds(userId: Int): List<Int> {
        val rows = pool.preparedQuery("{call sp_GetUserRoleIds(?)}").execute(Tuple.of(userId)).coAwait()
        return rows.map { it.requireInt("RoleId") }
    }

    suspend fun getUsersWithRoles(): List<UserWithRoles> {
        val rows = pool.query("{call sp_ListUsersWithRoles}").execute().coAwait()
        data class UserAggregate(
            val id: Int,
            val username: String,
            val isActive: Boolean,
            val employeeId: Int?,
            val employeeName: String?,
            val roles: MutableList<String>
        )
        val users = LinkedHashMap<Int, UserAggregate>()
        for (row in rows) {
            val id = row.requireInt("Id")
            val aggregate = users.getOrPut(id) {
                UserAggregate(
                    id = id,
                    username = row.requireString("Username"),
                    isActive = row.getBooleanSafe("IsActive") ?: false,
                    employeeId = row.getIntSafe("EmployeeID"),
                    employeeName = row.getStringSafe("EmployeeName"),
                    roles = mutableListOf()
                )
            }
            val role = row.getStringSafe("Name")
            if (!role.isNullOrBlank()) {
                aggregate.roles.add(role)
            }
        }
        return users.values.map { user ->
            UserWithRoles(user.id, user.username, user.isActive, user.roles, user.employeeId, user.employeeName)
        }
    }

    suspend fun getUserAuth(username: String): UserAuthInfo? {
        val rows = pool.preparedQuery("{call sp_GetUserAuth(?)}").execute(Tuple.of(username)).coAwait()
        val it = rows.iterator()
        var id: Int? = null
        var userName: String? = null
        var hash: ByteArray? = null
        var salt: ByteArray? = null
        var isActive = false
        val roles = mutableListOf<String>()
        val modulePermissions = defaultModulePermissions()
        var canEdit = false
        var canDelete = false
        while (it.hasNext()) {
            val row = it.next()
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
            val moduleKey = row.getStringSafe("ModuleKey")?.trim().orEmpty()
            if (moduleKey.isNotBlank() && MODULE_PERMISSION_LABELS.containsKey(moduleKey)) {
                val current = modulePermissions.getValue(moduleKey)
                val updated = ModulePermission(
                    moduleKey = moduleKey,
                    label = current.label,
                    canEdit = current.canEdit || (row.getBooleanSafe("CanEdit") ?: false),
                    canDelete = current.canDelete || (row.getBooleanSafe("CanDelete") ?: false)
                )
                modulePermissions[moduleKey] = updated
            }
        }
        if (id == null || userName == null || hash == null || salt == null) {
            return null
        }
        canEdit = modulePermissions.values.any { it.canEdit }
        canDelete = modulePermissions.values.any { it.canDelete }
        return UserAuthInfo(id, userName, hash, salt, isActive, roles, modulePermissions, canEdit, canDelete)
    }
}

class AuthService(private val repo: AuthRepository) {
    suspend fun ensureSeed() {
        repo.ensureSchema()
        if (repo.getUserIdByUsername("admin") == null) {
            val (hash, salt) = createPasswordHash("admin123")
            poolSeed(hash, salt)
        }
    }

    private suspend fun poolSeed(hash: ByteArray, salt: ByteArray) {
        repo.saveAdminSeed(hash, salt)
    }

    suspend fun tryLogin(username: String, password: String): AuthSession? {
        if (username.isBlank() || password.isBlank()) return null
        val user = repo.getUserAuth(username.trim()) ?: return null
        if (!user.isActive) return null
        if (!verifyPassword(password, user.passwordHash, user.passwordSalt)) return null
        return AuthSession(
            user.id,
            user.username,
            user.roles.toSet(),
            user.modulePermissions,
            user.canEdit,
            user.canDelete
        )
    }

    fun createPasswordHash(password: String): Pair<ByteArray, ByteArray> {
        val salt = ByteArray(16)
        SecureRandom().nextBytes(salt)
        val hash = pbkdf2(password, salt)
        return hash to salt
    }

    fun verifyPassword(password: String, hash: ByteArray, salt: ByteArray): Boolean {
        val computed = pbkdf2(password, salt)
        return computed.contentEquals(hash)
    }

    private fun pbkdf2(password: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, 10000, 256)
        val skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return skf.generateSecret(spec).encoded
    }
}
