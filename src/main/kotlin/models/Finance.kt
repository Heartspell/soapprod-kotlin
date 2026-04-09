package models

import java.time.LocalDate

data class Budget(
    val id: Int,
    val budgetAmount: Double
)

data class Credit(
    val id: Int,
    val bankName: String,
    val amount: Double,
    val rate: Double,
    val termMonths: Int,
    val startDate: LocalDate,
    val monthlyPayment: Double,
    val remainingAmount: Double,
    val isActive: Boolean
)

data class LookupItem(
    val id: Int,
    val name: String
)
