package auth

import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class AuthService(private val repo: AuthRepository) {

    suspend fun ensureSeed() {
        repo.ensureSchema()
        if (repo.getUserIdByUsername("admin") == null) {
            val (hash, salt) = createPasswordHash("admin123")
            repo.saveAdminSeed(hash, salt)
        }
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
        return Pair(hash, salt)
    }

    fun verifyPassword(password: String, hash: ByteArray, salt: ByteArray): Boolean {
        val computed = pbkdf2(password, salt)
        return computed.contentEquals(hash)
    }

    private fun pbkdf2(password: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, 10000, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }
}
