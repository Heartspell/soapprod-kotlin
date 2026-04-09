package config

import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

enum class DbAuthMode { SQL, INTEGRATED }

data class DbConfig(
    val host: String,
    val port: Int,
    val database: String,
    val user: String,
    val password: String,
    val trustServerCertificate: Boolean,
    val encrypt: Boolean,
    val authMode: DbAuthMode,
    val connectionString: String?
) {
    companion object {
        fun fromEnv(env: Map<String, String>): DbConfig {
            val fileProps = loadDbProperties()
            val connectionString = firstNonBlank(env["DB_CONNECTION_STRING"], fileProps.getProperty("db.connectionString"))
            val host = firstNonBlank(env["DB_HOST"], fileProps.getProperty("db.host")) ?: "DESKTOP-6CEGSEJ"
            val port = firstNonBlank(env["DB_PORT"], fileProps.getProperty("db.port"))?.toIntOrNull() ?: 1433
            val database = firstNonBlank(env["DB_NAME"], fileProps.getProperty("db.name")) ?: "SoapProduction"
            val user = firstNonBlank(env["DB_USER"], fileProps.getProperty("db.user")) ?: ""
            val password = firstNonBlank(env["DB_PASSWORD"], fileProps.getProperty("db.password")) ?: ""
            val trust = firstNonBlank(env["DB_TRUST_SERVER_CERT"], fileProps.getProperty("db.trustServerCertificate"))
                ?.toBooleanStrictOrNull() ?: true
            val encrypt = firstNonBlank(env["DB_ENCRYPT"], fileProps.getProperty("db.encrypt"))
                ?.toBooleanStrictOrNull() ?: true
            val authMode = when (firstNonBlank(env["DB_AUTH"], fileProps.getProperty("db.auth"))?.lowercase()) {
                "integrated", "windows", "ia" -> DbAuthMode.INTEGRATED
                else -> DbAuthMode.SQL
            }
            if (connectionString == null && authMode == DbAuthMode.SQL) {
                require(user.isNotBlank()) { "DB_USER is required for SQL Server login." }
            }
            return DbConfig(host, port, database, user, password, trust, encrypt, authMode, connectionString)
        }

        private fun loadDbProperties(): Properties {
            val props = Properties()
            val path = Path.of("db.properties")
            if (Files.exists(path)) Files.newInputStream(path).use { props.load(it) }
            return props
        }

        private fun firstNonBlank(vararg values: String?): String? =
            values.firstOrNull { !it.isNullOrBlank() }
    }
}
