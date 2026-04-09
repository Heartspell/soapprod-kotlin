package models

import java.time.LocalDateTime

data class ProductInventoryView(
    val productId: Int,
    val productName: String,
    val unitName: String,
    val currentQuantity: Double,
    val currentAmount: Double,
    val totalProduced: Double?,
    val totalSold: Double?,
    val totalSalesAmount: Double?,
    val lastProductionDate: LocalDateTime?,
    val lastSaleDate: LocalDateTime?
)

data class RawMaterialInventoryView(
    val rawMaterialId: Int,
    val rawMaterialName: String,
    val unitName: String,
    val currentQuantity: Double,
    val currentAmount: Double,
    val totalPurchased: Double?,
    val totalPurchaseAmount: Double?,
    val lastPurchaseDate: LocalDateTime?,
    val totalConsumed: Double?,
    val lastConsumptionDate: LocalDateTime?
)

data class SalesExtendedView(
    val saleId: Int,
    val saleDate: LocalDateTime,
    val quantity: Double,
    val amount: Double,
    val pricePerUnit: Double?,
    val productId: Int,
    val productName: String,
    val unitName: String,
    val employeeId: Int,
    val employeeName: String,
    val employeePosition: String?
)

data class CurrentBudgetView(
    val id: Int,
    val budgetAmount: Double
)

data class PurchaseView(
    val id: Int,
    val rawMaterialId: Int,
    val quantity: Double,
    val amount: Double,
    val purchaseDate: LocalDateTime,
    val employeeId: Int,
    val rawMaterialName: String,
    val employeeName: String
)

data class ProductionView(
    val id: Int,
    val productId: Int,
    val quantity: Double,
    val productionDate: LocalDateTime,
    val employeeId: Int,
    val productName: String,
    val employeeName: String
)
