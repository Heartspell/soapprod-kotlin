package services

import models.ProductSale
import repositories.ProductSalesRepository
import java.time.LocalDateTime

class SaleService(private val repo: ProductSalesRepository) {

    suspend fun listAll(): List<ProductSale> {
        return repo.listAll()
    }

    suspend fun create(productId: Int, quantity: Double, date: LocalDateTime, employeeId: Int): Int {
        if (quantity <= 0.0) throw ValidationException("Quantity must be greater than 0")
        return repo.create(productId, quantity, date, employeeId)
    }

    suspend fun delete(id: Int) {
        repo.delete(id)
    }

    suspend fun rollback() {
        repo.deleteLast()
    }
}
