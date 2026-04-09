package models

import java.time.LocalDateTime

data class ProductProduction(
    val id: Int,
    val productId: Int,
    val quantity: Double,
    val productionDate: LocalDateTime,
    val employeeId: Int,
    val productName: String?,
    val employeeName: String?
)

data class ProductSale(
    val id: Int,
    val productId: Int,
    val quantity: Double,
    val amount: Double,
    val saleDate: LocalDateTime,
    val employeeId: Int,
    val productName: String?,
    val employeeName: String?
)

data class PurchaseRawMaterial(
    val id: Int,
    val rawMaterialId: Int,
    val quantity: Double,
    val amount: Double,
    val purchaseDate: LocalDateTime,
    val employeeId: Int,
    val rawMaterialName: String?,
    val employeeName: String?
)

data class SalaryPayment(
    val id: Int,
    val employeeId: Int,
    val amount: Double,
    val paymentDate: LocalDateTime,
    val note: String?,
    val employeeName: String?
)
