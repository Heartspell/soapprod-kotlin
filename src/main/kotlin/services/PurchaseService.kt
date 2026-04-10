package services

import models.PurchaseRawMaterial
import repositories.PurchaseRawMaterialRepository
import java.time.LocalDateTime

class PurchaseService(private val repo: PurchaseRawMaterialRepository) {

    suspend fun listAll(): List<PurchaseRawMaterial> {
        return repo.listAll()
    }

    suspend fun create(rawMaterialId: Int, quantity: Double, unitPrice: Double, date: LocalDateTime, employeeId: Int) {
        if (quantity <= 0.0) throw ValidationException("Quantity must be greater than 0")
        if (unitPrice <= 0.0) throw ValidationException("Unit price must be greater than 0")
        val amount = quantity * unitPrice
        repo.create(rawMaterialId, quantity, amount, date, employeeId)
    }

    suspend fun delete(id: Int) {
        repo.delete(id)
    }

    suspend fun rollback() {
        repo.deleteLast()
    }
}
