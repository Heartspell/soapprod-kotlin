package repositories

import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.Tuple
import models.Document
import models.DocumentType

class DocumentRepository(private val pool: Pool) {

    suspend fun listAll(typeFilter: DocumentType? = null): List<Document> {
        val query = if (typeFilter != null) {
            pool.preparedQuery("EXEC sp_ListDocuments ?")
                .execute(Tuple.of(typeFilter.name))
        } else {
            pool.preparedQuery("EXEC sp_ListDocuments NULL").execute()
        }
        val rows = query.coAwait()
        return rows.map { row ->
            Document(
                id = row.requireInt("Id"),
                type = DocumentType.valueOf(row.requireString("Type")),
                title = row.requireString("Title"),
                amount = row.requireDouble("Amount"),
                description = row.requireString("Description"),
                createdAt = row.requireLocalDateTime("CreatedAt"),
                transactionId = row.requireInt("TransactionId")
            )
        }
    }

    suspend fun create(
        type: DocumentType,
        title: String,
        amount: Double,
        description: String,
        transactionId: Int
    ): Int {
        val rows = pool.preparedQuery("EXEC sp_CreateDocument ?, ?, ?, ?, ?")
            .execute(Tuple.of(type.name, title, amount, description, transactionId))
            .coAwait()
        val it = rows.iterator()
        return if (it.hasNext()) it.next().requireInt("Id") else 0
    }
}
