package repositories

import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.Tuple
import models.RawMaterial

class RawMaterialRepository(private val pool: Pool) {

    suspend fun listAll(): List<RawMaterial> {
        val rows = pool.query("EXEC sp_ListRawMaterials").execute().coAwait()
        return rows.map { row ->
            RawMaterial(
                id = row.requireInt("Id"), name = row.requireString("Name"),
                unitId = row.requireInt("UnitID"), quantity = row.requireDouble("Quantity"),
                amount = row.requireDouble("Amount"), unitName = row.getStringSafe("UnitName")
            )
        }
    }

    suspend fun getById(id: Int): RawMaterial? {
        val rows = pool.preparedQuery("EXEC sp_GetRawMaterial ?").execute(Tuple.of(id)).coAwait()
        val it = rows.iterator()
        if (!it.hasNext()) return null
        val row = it.next()
        return RawMaterial(
            id = row.requireInt("Id"), name = row.requireString("Name"),
            unitId = row.requireInt("UnitID"), quantity = row.requireDouble("Quantity"),
            amount = row.requireDouble("Amount"), unitName = row.getStringSafe("UnitName")
        )
    }

    suspend fun create(name: String, unitId: Int, quantity: Double, amount: Double): Int {
        val rows = pool.preparedQuery("EXEC sp_SaveRawMaterial ?, ?, ?, ?, ?")
            .execute(Tuple.of(0, name, unitId, quantity, amount)).coAwait()
        return rows.iterator().next().requireInt("Id")
    }

    suspend fun update(material: RawMaterial): Int {
        val rows = pool.preparedQuery("EXEC sp_SaveRawMaterial ?, ?, ?, ?, ?")
            .execute(Tuple.of(material.id, material.name, material.unitId, material.quantity, material.amount)).coAwait()
        val it = rows.iterator()
        return if (it.hasNext()) it.next().requireInt("Id") else material.id
    }

    suspend fun delete(id: Int): Int =
        pool.preparedQuery("EXEC sp_DeleteRawMaterial ?").execute(Tuple.of(id)).coAwait().rowCount()
}
