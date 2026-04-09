package repositories

import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.Tuple
import models.MeasurementUnit

class MeasurementUnitRepository(private val pool: Pool) {

    suspend fun listAll(): List<MeasurementUnit> {
        val rows = pool.query("EXEC sp_ListUnits").execute().coAwait()
        return rows.map { MeasurementUnit(it.requireInt("Id"), it.requireString("Name")) }
    }

    suspend fun getById(id: Int): MeasurementUnit? = listAll().firstOrNull { it.id == id }

    suspend fun create(name: String): Int {
        val rows = pool.preparedQuery("EXEC sp_SaveUnit ?, ?").execute(Tuple.of(0, name)).coAwait()
        return rows.iterator().next().requireInt("Id")
    }

    suspend fun update(unit: MeasurementUnit): Int {
        val rows = pool.preparedQuery("EXEC sp_SaveUnit ?, ?").execute(Tuple.of(unit.id, unit.name)).coAwait()
        val it = rows.iterator()
        return if (it.hasNext()) it.next().requireInt("Id") else unit.id
    }

    suspend fun delete(id: Int): Int =
        pool.preparedQuery("EXEC sp_DeleteUnit ?").execute(Tuple.of(id)).coAwait().rowCount()
}
