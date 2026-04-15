package repositories

import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.Tuple
import models.ProductionRequest
import java.time.LocalDateTime

class ProductionRequestRepository(private val pool: Pool) {

    suspend fun ensureSchema() {
        pool.query(
            """
            IF NOT EXISTS (SELECT 1 FROM sys.tables WHERE name = 'ProductionRequests')
            BEGIN
                CREATE TABLE ProductionRequests (
                    Id INT IDENTITY(1,1) PRIMARY KEY,
                    CreatedAt DATETIME2 NOT NULL DEFAULT GETDATE(),
                    UpdatedAt DATETIME2 NOT NULL DEFAULT GETDATE(),
                    Status NVARCHAR(100) NOT NULL DEFAULT N'Created',
                    ApplicantName NVARCHAR(200) NOT NULL,
                    ProductId INT NOT NULL,
                    Quantity FLOAT NOT NULL DEFAULT 1,
                    RejectionReason NVARCHAR(MAX) NULL
                )
            END
            """.trimIndent()
        ).execute().coAwait()

        pool.query(
            """
            CREATE OR ALTER PROCEDURE sp_ListProductionRequests
            AS
            BEGIN
                SET NOCOUNT ON;
                SELECT r.Id, r.CreatedAt, r.UpdatedAt, r.Status, r.ApplicantName,
                       r.ProductId, r.Quantity, r.RejectionReason,
                       p.Name AS ProductName
                FROM ProductionRequests r
                LEFT JOIN FinishedProducts p ON p.Id = r.ProductId
                ORDER BY r.CreatedAt DESC
            END
            """.trimIndent()
        ).execute().coAwait()

        pool.query(
            """
            CREATE OR ALTER PROCEDURE sp_GetProductionRequest
                @Id INT
            AS
            BEGIN
                SET NOCOUNT ON;
                SELECT r.Id, r.CreatedAt, r.UpdatedAt, r.Status, r.ApplicantName,
                       r.ProductId, r.Quantity, r.RejectionReason,
                       p.Name AS ProductName
                FROM ProductionRequests r
                LEFT JOIN FinishedProducts p ON p.Id = r.ProductId
                WHERE r.Id = @Id
            END
            """.trimIndent()
        ).execute().coAwait()

        pool.query(
            """
            CREATE OR ALTER PROCEDURE sp_CreateProductionRequest
                @ApplicantName NVARCHAR(200),
                @ProductId INT,
                @Quantity FLOAT
            AS
            BEGIN
                SET NOCOUNT ON;
                INSERT INTO ProductionRequests (ApplicantName, ProductId, Quantity, Status, CreatedAt, UpdatedAt)
                VALUES (@ApplicantName, @ProductId, @Quantity, N'Created', GETDATE(), GETDATE());
                SELECT CAST(SCOPE_IDENTITY() AS INT) AS Id;
            END
            """.trimIndent()
        ).execute().coAwait()

        pool.query(
            """
            CREATE OR ALTER PROCEDURE sp_UpdateProductionRequestStatus
                @Id INT,
                @Status NVARCHAR(100),
                @RejectionReason NVARCHAR(MAX) = NULL
            AS
            BEGIN
                SET NOCOUNT ON;
                UPDATE ProductionRequests
                SET Status = @Status, RejectionReason = @RejectionReason, UpdatedAt = GETDATE()
                WHERE Id = @Id;
            END
            """.trimIndent()
        ).execute().coAwait()

        pool.query(
            """
            CREATE OR ALTER PROCEDURE sp_DeleteProductionRequest
                @Id INT
            AS
            BEGIN
                SET NOCOUNT ON;
                DELETE FROM ProductionRequests WHERE Id = @Id;
            END
            """.trimIndent()
        ).execute().coAwait()
    }

    suspend fun listAll(): List<ProductionRequest> {
        val rows = pool.query("EXEC sp_ListProductionRequests").execute().coAwait()
        return rows.map { row ->
            ProductionRequest(
                id = row.requireInt("Id"),
                createdAt = row.requireLocalDateTime("CreatedAt"),
                updatedAt = row.requireLocalDateTime("UpdatedAt"),
                status = row.requireString("Status"),
                applicantName = row.requireString("ApplicantName"),
                productId = row.requireInt("ProductId"),
                quantity = row.requireDouble("Quantity"),
                rejectionReason = row.getStringSafe("RejectionReason"),
                productName = row.getStringSafe("ProductName")
            )
        }
    }

    suspend fun getById(id: Int): ProductionRequest? {
        val rows = pool.preparedQuery("EXEC sp_GetProductionRequest ?").execute(Tuple.of(id)).coAwait()
        val it = rows.iterator()
        if (!it.hasNext()) return null
        val row = it.next()
        return ProductionRequest(
            id = row.requireInt("Id"),
            createdAt = row.requireLocalDateTime("CreatedAt"),
            updatedAt = row.requireLocalDateTime("UpdatedAt"),
            status = row.requireString("Status"),
            applicantName = row.requireString("ApplicantName"),
            productId = row.requireInt("ProductId"),
            quantity = row.requireDouble("Quantity"),
            rejectionReason = row.getStringSafe("RejectionReason"),
            productName = row.getStringSafe("ProductName")
        )
    }

    suspend fun create(applicantName: String, productId: Int, quantity: Double): Int {
        val rows = pool.preparedQuery("EXEC sp_CreateProductionRequest ?, ?, ?")
            .execute(Tuple.of(applicantName, productId, quantity)).coAwait()
        val it = rows.iterator()
        return if (it.hasNext()) it.next().requireInt("Id") else 0
    }

    suspend fun updateStatus(id: Int, status: String, rejectionReason: String? = null) {
        pool.preparedQuery("EXEC sp_UpdateProductionRequestStatus ?, ?, ?")
            .execute(Tuple.of(id, status, rejectionReason)).coAwait()
    }

    suspend fun delete(id: Int) {
        pool.preparedQuery("EXEC sp_DeleteProductionRequest ?").execute(Tuple.of(id)).coAwait()
    }
}
