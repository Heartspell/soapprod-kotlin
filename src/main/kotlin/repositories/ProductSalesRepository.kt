package repositories

import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.Tuple
import models.ProductSale
import java.time.LocalDateTime

class ProductSalesRepository(private val pool: Pool) {

    suspend fun listAll(): List<ProductSale> {
        val rows = pool.query("EXEC sp_ListSales").execute().coAwait()
        return rows.map { row ->
            ProductSale(
                id = row.requireInt("SaleID"), productId = row.requireInt("ProductID"),
                quantity = row.requireDouble("Quantity"), amount = row.requireDouble("Amount"),
                saleDate = row.requireLocalDateTime("SaleDate"), employeeId = row.requireInt("EmployeeID"),
                productName = row.requireString("ProductName"), employeeName = row.requireString("EmployeeName")
            )
        }
    }

    suspend fun getById(id: Int): ProductSale? {
        val rows = pool.preparedQuery("EXEC sp_GetSale ?").execute(Tuple.of(id)).coAwait()
        val it = rows.iterator()
        if (!it.hasNext()) return null
        val row = it.next()
        return ProductSale(
            id = row.requireInt("SaleID"), productId = row.requireInt("ProductID"),
            quantity = row.requireDouble("Quantity"), amount = row.requireDouble("Amount"),
            saleDate = row.requireLocalDateTime("SaleDate"), employeeId = row.requireInt("EmployeeID"),
            productName = row.requireString("ProductName"), employeeName = row.requireString("EmployeeName")
        )
    }

    suspend fun create(productId: Int, quantity: Double, saleDate: LocalDateTime, employeeId: Int): Int {
        val rows = pool.preparedQuery("EXEC sp_AddProductSale ?, ?, ?, ?")
            .execute(Tuple.of(productId, quantity, saleDate, employeeId)).coAwait()
        val it = rows.iterator()
        return if (it.hasNext()) it.next().getInteger("Result") ?: 0 else 0
    }

    suspend fun update(sale: ProductSale): Int {
        val rows = pool.preparedQuery("EXEC sp_UpdateSale ?, ?, ?, ?, ?, ?")
            .execute(Tuple.of(sale.id, sale.productId, sale.quantity, sale.amount, sale.saleDate, sale.employeeId))
            .coAwait()
        val it = rows.iterator()
        return if (it.hasNext()) it.next().requireInt("Id") else sale.id
    }

    suspend fun delete(id: Int): Int =
        pool.preparedQuery("EXEC sp_DeleteSale ?").execute(Tuple.of(id)).coAwait().rowCount()

    suspend fun deleteLast() {
        pool.query("EXEC sp_DeleteLastSale").execute().coAwait()
    }
}
