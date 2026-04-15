package models

import java.math.BigDecimal
import java.time.LocalDateTime

data class ProductionRequest(
    val id: Int,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
    val status: String,
    val applicantName: String,
    val productId: Int,
    val quantity: Double,
    val rejectionReason: String?,
    val productName: String?
)

object RequestStatus {
    const val CREATED = "Created"
    const val RAW_MATERIAL_CHECK = "Under raw material availability check"
    const val PROCUREMENT = "In the process of raw material procurement"
    const val PRODUCTION = "In the production process"
    const val SALES = "In the sales process"
    const val COMPLETED = "Completed"
    const val ERROR = "Error"
}

data class Employee(
    val id: Int,
    val fullName: String,
    val dateOfBirth: LocalDateTime?,
    val positionId: Short?,
    val salary: BigDecimal?,
    val homeAddress: String?,
    val positionTitle: String?
)

data class Position(
    val id: Short,
    val title: String
)

data class MeasurementUnit(
    val id: Int,
    val name: String
)

data class FinishedProduct(
    val id: Int,
    val name: String,
    val unitId: Int,
    val quantity: Double,
    val amount: Double,
    val unitName: String?
)

data class RawMaterial(
    val id: Int,
    val name: String,
    val unitId: Int,
    val quantity: Double,
    val amount: Double,
    val unitName: String?
)

data class Ingredient(
    val id: Int,
    val productId: Int,
    val rawMaterialId: Int,
    val quantity: Double,
    val productName: String?,
    val rawMaterialName: String?
)
