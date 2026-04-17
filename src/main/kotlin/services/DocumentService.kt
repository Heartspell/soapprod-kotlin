package services

import models.DocumentType
import repositories.DocumentRepository

class DocumentService(private val repo: DocumentRepository) {

    suspend fun listAll(typeFilter: DocumentType? = null) = repo.listAll(typeFilter)

    suspend fun createDocument(
        type: DocumentType,
        title: String,
        amount: Double,
        description: String,
        transactionId: Int
    ): Int {
        if (title.isBlank()) throw ValidationException("Document title is required")
        if (amount < 0) throw ValidationException("Document amount cannot be negative")
        return repo.create(type, title, amount, description, transactionId)
    }
}
