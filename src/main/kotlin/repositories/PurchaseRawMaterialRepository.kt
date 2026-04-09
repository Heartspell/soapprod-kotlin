package repositories

import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.Tuple
import models.PurchaseRawMaterial
import java.time.LocalDateTime

class PurchaseRawMaterialRepository(private val pool: Pool) {

    suspend fun listAll(): List<PurchaseRawMaterial> {
        val rows = pool.query("EXEC sp_ListPurchases").execute().coAwait()
        return rows.map { row ->
            PurchaseRawMaterial(
                id = row.requireInt("Id"), rawMaterialId = row.requireInt("RawMaterialID"),
                quantity = row.requireDouble("Quantity"), amount = row.requireDouble("Amount"),
                purchaseDate = row.requireLocalDateTime("PurchaseDate"), employeeId = row.requireInt("EmployeeID"),
                rawMaterialName = row.getStringSafe("RawMaterialName"), employeeName = row.getStringSafe("EmployeeName")
            )
        }
    }

    suspend fun getById(id: Int): PurchaseRawMaterial? {
        val rows = pool.preparedQuery("EXEC sp_GetPurchase ?").execute(Tuple.of(id)).coAwait()
        val it = rows.iterator()
        if (!it.hasNext()) return null
        val row = it.next()
        return PurchaseRawMaterial(
            id = row.requireInt("Id"), rawMaterialId = row.requireInt("RawMaterialID"),
            quantity = row.requireDouble("Quantity"), amount = row.requireDouble("Amount"),
            purchaseDate = row.requireLocalDateTime("PurchaseDate"), employeeId = row.requireInt("EmployeeID"),
            rawMaterialName = row.getStringSafe("RawMaterialName"), employeeName = row.getStringSafe("EmployeeName")
        )
    }

    suspend fun create(rawMaterialId: Int, quantity: Double, amount: Double, purchaseDate: LocalDateTime, employeeId: Int): Int {
        pool.preparedQuery("EXEC sp_AddRawMaterialPurchase ?, ?, ?, ?, ?")
            .execute(Tuple.of(rawMaterialId, quantity, amount, purchaseDate, employeeId)).coAwait()
        return 0
    }

    suspend fun update(purchase: PurchaseRawMaterial): Int {
        val rows = pool.preparedQuery("EXEC sp_UpdatePurchase ?, ?, ?, ?, ?, ?")
            .execute(Tuple.of(purchase.id, purchase.rawMaterialId, purchase.quantity, purchase.amount, purchase.purchaseDate, purchase.employeeId))
            .coAwait()
        val it = rows.iterator()
        return if (it.hasNext()) it.next().requireInt("Id") else purchase.id
    }

    suspend fun delete(id: Int): Int =
        pool.preparedQuery("EXEC sp_DeletePurchase ?").execute(Tuple.of(id)).coAwait().rowCount()

    suspend fun deleteLast() {
        pool.query("EXEC sp_DeleteLastPurchase").execute().coAwait()
    }
}
