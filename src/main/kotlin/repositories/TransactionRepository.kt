package repositories

import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.Tuple
import models.Transaction
import models.TransactionType

class TransactionRepository(private val pool: Pool) {

    suspend fun listAll(typeFilter: TransactionType? = null): List<Transaction> {
        val query = if (typeFilter != null) {
            pool.preparedQuery("EXEC sp_ListTransactions ?")
                .execute(Tuple.of(typeFilter.name))
        } else {
            pool.preparedQuery("EXEC sp_ListTransactions NULL").execute()
        }
        val rows = query.coAwait()
        return rows.map { row ->
            Transaction(
                id = row.requireInt("Id"),
                type = TransactionType.valueOf(row.requireString("Type")),
                description = row.requireString("Description"),
                amount = row.requireDouble("Amount"),
                debit = row.requireDouble("Debit"),
                balance = row.requireDouble("Balance"),
                createdAt = row.requireLocalDateTime("CreatedAt"),
                relatedEntityId = row.getInteger("RelatedEntityId"),
                documentId = row.getInteger("DocumentId")
            )
        }
    }

    suspend fun create(
        type: TransactionType,
        description: String,
        amount: Double,
        debit: Double,
        balance: Double,
        relatedEntityId: Int? = null,
        documentId: Int? = null
    ): Int {
        val rows = pool.preparedQuery("EXEC sp_CreateTransaction ?, ?, ?, ?, ?, ?, ?")
            .execute(Tuple.of(type.name, description, amount, debit, balance, relatedEntityId, documentId))
            .coAwait()
        val it = rows.iterator()
        return if (it.hasNext()) it.next().requireInt("Id") else 0
    }
}
