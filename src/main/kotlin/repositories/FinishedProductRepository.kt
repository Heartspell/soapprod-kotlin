package repositories

import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.Tuple
import models.FinishedProduct

class FinishedProductRepository(private val pool: Pool) {

    suspend fun listAll(): List<FinishedProduct> {
        val rows = pool.query("EXEC sp_ListFinishedProducts").execute().coAwait()
        return rows.map { row ->
            FinishedProduct(
                id = row.requireInt("Id"), name = row.requireString("Name"),
                unitId = row.requireInt("UnitID"), quantity = row.requireDouble("Quantity"),
                amount = row.requireDouble("Amount"), unitName = row.getStringSafe("UnitName")
            )
        }
    }

    suspend fun getById(id: Int): FinishedProduct? {
        val rows = pool.preparedQuery("EXEC sp_GetFinishedProduct ?").execute(Tuple.of(id)).coAwait()
        val it = rows.iterator()
        if (!it.hasNext()) return null
        val row = it.next()
        return FinishedProduct(
            id = row.requireInt("Id"), name = row.requireString("Name"),
            unitId = row.requireInt("UnitID"), quantity = row.requireDouble("Quantity"),
            amount = row.requireDouble("Amount"), unitName = row.getStringSafe("UnitName")
        )
    }

    suspend fun create(name: String, unitId: Int, quantity: Double, amount: Double): Int {
        val rows = pool.preparedQuery("EXEC sp_SaveFinishedProduct ?, ?, ?, ?, ?")
            .execute(Tuple.of(0, name, unitId, quantity, amount)).coAwait()
        return rows.iterator().next().requireInt("Id")
    }

    suspend fun update(product: FinishedProduct): Int {
        val rows = pool.preparedQuery("EXEC sp_SaveFinishedProduct ?, ?, ?, ?, ?")
            .execute(Tuple.of(product.id, product.name, product.unitId, product.quantity, product.amount)).coAwait()
        val it = rows.iterator()
        return if (it.hasNext()) it.next().requireInt("Id") else product.id
    }

    suspend fun delete(id: Int): Int =
        pool.preparedQuery("EXEC sp_DeleteFinishedProduct ?").execute(Tuple.of(id)).coAwait().rowCount()
}
