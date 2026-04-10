package services

import models.Credit
import repositories.CreditRepository
import java.time.LocalDate

class CreditService(private val repo: CreditRepository) {

    suspend fun ensureSchema() {
        repo.ensureSchema()
    }

    suspend fun listAll(): List<Credit> {
        return repo.listAll()
    }

    suspend fun create(bankName: String, amount: Double, rate: Double, termMonths: Int, startDate: LocalDate) {
        if (amount <= 0.0) throw ValidationException("Amount must be greater than 0")
        if (termMonths <= 0) throw ValidationException("Term must be greater than 0")
        repo.create(bankName, amount, rate, termMonths, startDate)
    }

    suspend fun pay(id: Int, paymentAmount: Double) {
        if (id <= 0) throw ValidationException("Credit ID is required")
        if (paymentAmount <= 0.0) throw ValidationException("Payment amount must be greater than 0")
        repo.pay(id, paymentAmount)
    }

    suspend fun delete(id: Int) {
        repo.delete(id)
    }
}
