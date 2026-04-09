package repositories

import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.Tuple
import models.ProductProduction
import java.time.LocalDateTime

class ProductProductionRepository(private val pool: Pool) {

    suspend fun listAll(): List<ProductProduction> {
        val rows = pool.query("EXEC sp_ListProduction").execute().coAwait()
        return rows.map { row ->
            ProductProduction(
                id = row.requireInt("Id"), productId = row.requireInt("ProductID"),
                quantity = row.requireDouble("Quantity"),
                productionDate = row.requireLocalDateTime("ProductionDate"),
                employeeId = row.requireInt("EmployeeID"),
                productName = row.getStringSafe("ProductName"), employeeName = row.getStringSafe("EmployeeName")
            )
        }
    }

    suspend fun getById(id: Int): ProductProduction? {
        val rows = pool.preparedQuery("EXEC sp_GetProduction ?").execute(Tuple.of(id)).coAwait()
        val it = rows.iterator()
        if (!it.hasNext()) return null
        val row = it.next()
        return ProductProduction(
            id = row.requireInt("Id"), productId = row.requireInt("ProductID"),
            quantity = row.requireDouble("Quantity"),
            productionDate = row.requireLocalDateTime("ProductionDate"),
            employeeId = row.requireInt("EmployeeID"),
            productName = row.getStringSafe("ProductName"), employeeName = row.getStringSafe("EmployeeName")
        )
    }

    suspend fun create(productId: Int, quantity: Double, productionDate: LocalDateTime, employeeId: Int): Int {
        val rows = pool.preparedQuery("EXEC sp_AddProductProduction ?, ?, ?, ?")
            .execute(Tuple.of(productId, quantity, productionDate, employeeId)).coAwait()
        val it = rows.iterator()
        return if (it.hasNext()) it.next().getInteger("Result") ?: 0 else 0
    }

    suspend fun update(production: ProductProduction): Int {
        val rows = pool.preparedQuery("EXEC sp_UpdateProduction ?, ?, ?, ?, ?")
            .execute(Tuple.of(production.id, production.productId, production.quantity, production.productionDate, production.employeeId))
            .coAwait()
        val it = rows.iterator()
        return if (it.hasNext()) it.next().requireInt("Id") else production.id
    }

    suspend fun delete(id: Int): Int =
        pool.preparedQuery("EXEC sp_DeleteProduction ?").execute(Tuple.of(id)).coAwait().rowCount()

    suspend fun deleteLast() {
        pool.query("EXEC sp_DeleteLastProduction").execute().coAwait()
    }
}
