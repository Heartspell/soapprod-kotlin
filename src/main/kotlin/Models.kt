import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

val MODULE_PERMISSION_LABELS: LinkedHashMap<String, String> = linkedMapOf(
    "units" to "Units",
    "positions" to "Positions",
    "employees" to "Employees",
    "salary" to "Salaries",
    "raw_materials" to "Raw materials",
    "products" to "Products",
    "ingredients" to "Ingredients",
    "purchase" to "Purchase",
    "production" to "Production",
    "sales" to "Sales",
    "budget" to "Budget",
    "users" to "Users"
)

data class ModulePermission(
    val moduleKey: String,
    val label: String,
    val canEdit: Boolean,
    val canDelete: Boolean
)

fun defaultModulePermissions(): LinkedHashMap<String, ModulePermission> =
    LinkedHashMap<String, ModulePermission>().apply {
        MODULE_PERMISSION_LABELS.forEach { (key, label) ->
            put(key, ModulePermission(key, label, canEdit = false, canDelete = false))
        }
    }

data class Budget(
    val id: Int,
    val budgetAmount: Double
)

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

data class RoleItem(
    val id: Int,
    val name: String,
    val permissions: List<ModulePermission>
)

data class UserWithRoles(
    val id: Int,
    val username: String,
    val isActive: Boolean,
    val roles: List<String>,
    val employeeId: Int?,
    val employeeName: String?
)

data class UserAuthInfo(
    val id: Int,
    val username: String,
    val passwordHash: ByteArray,
    val passwordSalt: ByteArray,
    val isActive: Boolean,
    val roles: List<String>,
    val modulePermissions: Map<String, ModulePermission>,
    val canEdit: Boolean,
    val canDelete: Boolean
)
