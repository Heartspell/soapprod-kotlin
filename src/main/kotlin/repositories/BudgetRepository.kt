package repositories

import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.Tuple
import models.Budget

class BudgetRepository(private val pool: Pool) {

    suspend fun listAll(): List<Budget> {
        val rows = pool.query("EXEC sp_GetBudget").execute().coAwait()
        return rows.map { Budget(it.requireInt("Id"), it.requireDouble("BudgetAmount")) }
    }

    suspend fun getById(id: Int): Budget? = listAll().firstOrNull { it.id == id }

    suspend fun create(budgetAmount: Double): Int {
        val rows = pool.preparedQuery("EXEC sp_SaveBudget ?").execute(Tuple.of(budgetAmount)).coAwait()
        return rows.iterator().next().requireInt("Id")
    }

    suspend fun update(id: Int, budgetAmount: Double): Int {
        val rows = pool.preparedQuery("EXEC sp_SaveBudget ?").execute(Tuple.of(budgetAmount)).coAwait()
        val it = rows.iterator()
        return if (it.hasNext()) it.next().requireInt("Id") else id
    }

    suspend fun delete(id: Int): Int =
        pool.preparedQuery("EXEC sp_DeleteBudget ?").execute(Tuple.of(id)).coAwait().rowCount()
}
