package models

import java.time.LocalDate
import java.time.LocalDateTime

data class Budget(
    val id: Int,
    val budgetAmount: Double
)

enum class CreditType(val label: String) {
    SALARY("Salary credit"),
    PRODUCTION("Production credit")
}

data class Credit(
    val id: Int,
    val bankName: String,
    val amount: Double,
    val rate: Double,
    val termMonths: Int,
    val startDate: LocalDate,
    val monthlyPayment: Double,
    val remainingAmount: Double,
    val isActive: Boolean,
    val creditType: String = CreditType.PRODUCTION.name,
    val debit: Double = 0.0,
    val balance: Double = remainingAmount
)

data class LookupItem(
    val id: Int,
    val name: String
)

enum class TransactionType {
    SALARY_PAYMENT,
    PURCHASE,
    PRODUCTION,
    SALES,
    CREDIT_PAYMENT,
    CREDIT_PAYMENT_MONTHLY,
    CREDIT_CREATED
}

data class Transaction(
    val id: Int,
    val type: TransactionType,
    val description: String,
    val amount: Double,
    val debit: Double,
    val balance: Double,
    val createdAt: LocalDateTime,
    val relatedEntityId: Int?,
    val documentId: Int?
)

enum class DocumentType {
    PURCHASE,
    PRODUCTION,
    SALES,
    CREDIT
}

data class Document(
    val id: Int,
    val type: DocumentType,
    val title: String,
    val amount: Double,
    val description: String,
    val createdAt: LocalDateTime,
    val transactionId: Int
)
