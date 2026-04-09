package repositories

import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.Tuple
import models.Ingredient

class IngredientRepository(private val pool: Pool) {

    suspend fun listAll(): List<Ingredient> {
        val rows = pool.query("EXEC sp_ListIngredients").execute().coAwait()
        return rows.map { row ->
            Ingredient(
                id = row.requireInt("Id"), productId = row.requireInt("ProductID"),
                rawMaterialId = row.requireInt("RawMaterialID"), quantity = row.requireDouble("Quantity"),
                productName = row.getStringSafe("ProductName"), rawMaterialName = row.getStringSafe("RawMaterialName")
            )
        }
    }

    suspend fun getById(id: Int): Ingredient? {
        val rows = pool.preparedQuery("EXEC sp_GetIngredient ?").execute(Tuple.of(id)).coAwait()
        val it = rows.iterator()
        if (!it.hasNext()) return null
        val row = it.next()
        return Ingredient(
            id = row.requireInt("Id"), productId = row.requireInt("ProductID"),
            rawMaterialId = row.requireInt("RawMaterialID"), quantity = row.requireDouble("Quantity"),
            productName = row.getStringSafe("ProductName"), rawMaterialName = row.getStringSafe("RawMaterialName")
        )
    }

    suspend fun create(productId: Int, rawMaterialId: Int, quantity: Double): Int {
        val rows = pool.preparedQuery("EXEC sp_SaveIngredient ?, ?, ?, ?")
            .execute(Tuple.of(0, productId, rawMaterialId, quantity)).coAwait()
        return rows.iterator().next().requireInt("Id")
    }

    suspend fun update(ingredient: Ingredient): Int {
        val rows = pool.preparedQuery("EXEC sp_SaveIngredient ?, ?, ?, ?")
            .execute(Tuple.of(ingredient.id, ingredient.productId, ingredient.rawMaterialId, ingredient.quantity)).coAwait()
        val it = rows.iterator()
        return if (it.hasNext()) it.next().requireInt("Id") else ingredient.id
    }

    suspend fun delete(id: Int): Int =
        pool.preparedQuery("EXEC sp_DeleteIngredient ?").execute(Tuple.of(id)).coAwait().rowCount()
}
