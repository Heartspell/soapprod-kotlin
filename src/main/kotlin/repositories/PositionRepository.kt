package repositories

import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.Tuple
import models.Position

class PositionRepository(private val pool: Pool) {

    suspend fun listAll(): List<Position> {
        val rows = pool.query("EXEC sp_ListPositions").execute().coAwait()
        return rows.map { Position(it.requireShort("Id"), it.requireString("Title")) }
    }

    suspend fun getById(id: Short): Position? = listAll().firstOrNull { it.id == id }

    suspend fun create(title: String): Int {
        val rows = pool.preparedQuery("EXEC sp_SavePosition ?, ?").execute(Tuple.of(0, title)).coAwait()
        return rows.iterator().next().requireInt("Id")
    }

    suspend fun update(position: Position): Int {
        val rows = pool.preparedQuery("EXEC sp_SavePosition ?, ?")
            .execute(Tuple.of(position.id.toInt(), position.title)).coAwait()
        val it = rows.iterator()
        return if (it.hasNext()) it.next().requireInt("Id") else position.id.toInt()
    }

    suspend fun delete(id: Short): Int =
        pool.preparedQuery("EXEC sp_DeletePosition ?").execute(Tuple.of(id.toInt())).coAwait().rowCount()
}
