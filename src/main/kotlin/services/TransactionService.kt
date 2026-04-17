package services

import models.TransactionType
import repositories.TransactionRepository

class TransactionService(private val repo: TransactionRepository) {

    suspend fun listAll(typeFilter: TransactionType? = null) = repo.listAll(typeFilter)

    suspend fun logTransaction(
        type: TransactionType,
        description: String,
        amount: Double,
        debit: Double,
        balance: Double,
        relatedEntityId: Int? = null,
        documentId: Int? = null
    ): Int {
        if (description.isBlank()) throw ValidationException("Description is required")
        return repo.create(type, description, amount, debit, balance, relatedEntityId, documentId)
    }
}
