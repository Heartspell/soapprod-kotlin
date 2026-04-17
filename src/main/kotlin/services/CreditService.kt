package services

import models.Credit
import models.CreditType
import models.TransactionType
import repositories.CreditRepository
import repositories.TransactionRepository
import java.time.LocalDate

class CreditService(
    private val repo: CreditRepository,
    private val transactionRepo: TransactionRepository? = null
) {

    suspend fun ensureSchema() {
        repo.ensureSchema()
    }

    suspend fun listAll(): List<Credit> {
        return repo.listAll()
    }

    suspend fun create(bankName: String, amount: Double, rate: Double, termMonths: Int, startDate: LocalDate, creditType: String) {
        if (amount <= 0.0) throw ValidationException("Amount must be greater than 0")
        if (termMonths <= 0) throw ValidationException("Term must be greater than 0")
        val validType = CreditType.entries.map { it.name }
        if (creditType !in validType) throw ValidationException("Invalid credit type")
        val creditId = repo.create(bankName, amount, rate, termMonths, startDate, creditType)
        // Log transaction for credit creation
        transactionRepo?.create(
            TransactionType.CREDIT_CREATED,
            "Credit created: $bankName - $creditType",
            amount,
            0.0,
            amount,
            relatedEntityId = creditId
        )
    }

    suspend fun pay(id: Int, paymentAmount: Double) {
        if (id <= 0) throw ValidationException("Credit ID is required")
        if (paymentAmount <= 0.0) throw ValidationException("Payment amount must be greater than 0")
        repo.pay(id, paymentAmount)
        repo.updateDebit(id, paymentAmount)
        val credit = repo.getById(id)
        if (credit != null) {
            // Log transaction for credit payment
            transactionRepo?.create(
                TransactionType.CREDIT_PAYMENT,
                "Credit payment: ${credit.bankName}",
                paymentAmount,
                paymentAmount,
                credit.balance,
                relatedEntityId = id
            )
        }
    }

    suspend fun payMonthly(id: Int) {
        if (id <= 0) throw ValidationException("Credit ID is required")
        val credit = repo.getById(id) ?: throw ValidationException("Credit not found")
        if (!credit.isActive) throw ValidationException("Credit is already closed")
        repo.payMonthly(id)
        repo.updateDebit(id, credit.monthlyPayment)
        val updatedCredit = repo.getById(id)
        if (updatedCredit != null) {
            // Log transaction for monthly payment
            transactionRepo?.create(
                TransactionType.CREDIT_PAYMENT_MONTHLY,
                "Monthly credit payment: ${credit.bankName}",
                credit.monthlyPayment,
                credit.monthlyPayment,
                updatedCredit.balance,
                relatedEntityId = id
            )
        }
    }

    suspend fun delete(id: Int) {
        repo.delete(id)
    }
}
