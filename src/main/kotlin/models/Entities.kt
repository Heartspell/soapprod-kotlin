package models

import java.math.BigDecimal
import java.time.LocalDateTime

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
