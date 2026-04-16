package repositories

import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.Tuple
import models.ProductionRequest
import java.time.LocalDateTime

class ProductionRequestRepository(private val pool: Pool) {

    suspend fun ensureSchema() {
        pool.query("EXEC sp_EnsureProductionRequestSchema").execute().coAwait()
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
