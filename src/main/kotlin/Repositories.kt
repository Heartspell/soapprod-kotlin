import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.Tuple
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

private fun Row.getValueAny(column: String): Any? {
    return runCatching { getValue(column) }.getOrNull()
        ?: runCatching { getValue(column.lowercase()) }.getOrNull()
        ?: runCatching { getValue(column.uppercase()) }.getOrNull()
}

private fun Row.requireInt(column: String): Int {
    val value = getValueAny(column) ?: error("Missing $column")
    return when (value) {
        is Int -> value
        is Short -> value.toInt()
        is Long -> value.toInt()
        is Number -> value.toInt()
        else -> error("Invalid $column")
    }
}

private fun Row.requireShort(column: String): Short {
    val value = getValueAny(column) ?: error("Missing $column")
    return when (value) {
        is Short -> value
        is Int -> value.toShort()
        is Long -> value.toShort()
        is Number -> value.toShort()
        else -> error("Invalid $column")
    }
}

private fun Row.requireString(column: String): String =
    (getValueAny(column) as? String) ?: error("Missing $column")

private fun Row.requireDouble(column: String): Double {
    val value = getValueAny(column) ?: error("Missing $column")
    return when (value) {
        is Double -> value
        is Float -> value.toDouble()
        is Number -> value.toDouble()
        else -> error("Invalid $column")
    }
}

private fun Row.requireLocalDateTime(column: String): LocalDateTime =
    (getValueAny(column) as? LocalDateTime) ?: error("Missing $column")

private fun Row.requireLocalDate(column: String): LocalDate =
    (getValueAny(column) as? LocalDate) ?: error("Missing $column")

private fun Row.getStringSafe(column: String): String? =
    getValueAny(column) as? String

private fun Row.getShortSafe(column: String): Short? {
    val value = getValueAny(column) ?: return null
    return when (value) {
        is Short -> value
        is Int -> value.toShort()
        is Long -> value.toShort()
        is Number -> value.toShort()
        else -> null
    }
}

private fun Row.getBigDecimalSafe(column: String): BigDecimal? {
    val value = getValueAny(column) ?: return null
    return when (value) {
        is BigDecimal -> value
        is Number -> BigDecimal(value.toString())
        else -> null
    }
}

private fun Row.getLocalDateTimeSafe(column: String): LocalDateTime? =
    getValueAny(column) as? LocalDateTime

private fun Row.getLocalDateSafe(column: String): LocalDate? =
    getValueAny(column) as? LocalDate

private fun Row.getDoubleSafe(column: String): Double? {
    val value = getValueAny(column) ?: return null
    return when (value) {
        is Double -> value
        is Float -> value.toDouble()
        is Number -> value.toDouble()
        else -> null
    }
}

class BudgetRepository(private val pool: Pool) {
    suspend fun listAll(): List<Budget> {
        val rows = pool.query("EXEC sp_GetBudget").execute().coAwait()
        return rows.map { Budget(it.requireInt("Id"), it.requireDouble("BudgetAmount")) }
    }

    suspend fun getById(id: Int): Budget? {
        return listAll().firstOrNull { it.id == id }
    }

    suspend fun create(budgetAmount: Double): Int {
        val rows = pool.preparedQuery("EXEC sp_SaveBudget ?")
                .execute(Tuple.of(budgetAmount)).coAwait()
        return rows.iterator().next().requireInt("Id")
    }

    suspend fun update(id: Int, budgetAmount: Double): Int {
        val rows = pool.preparedQuery("EXEC sp_SaveBudget ?")
                .execute(Tuple.of(budgetAmount)).coAwait()
        val it = rows.iterator()
        return if (it.hasNext()) it.next().requireInt("Id") else id
    }

    suspend fun delete(id: Int): Int {
        val result = pool.preparedQuery("EXEC sp_DeleteBudget ?")
            .execute(Tuple.of(id)).coAwait()
        return result.rowCount()
    }
}

class EmployeeRepository(private val pool: Pool) {
    suspend fun listAll(): List<Employee> {
        val rows = pool.query("EXEC sp_ListEmployees").execute().coAwait()
        return rows.map { row ->
            Employee(
                id = row.requireInt("Id"),
                fullName = row.requireString("FullName"),
                dateOfBirth = row.getLocalDateTimeSafe("DateOfBirth"),
                positionId = row.getShortSafe("PositionID"),
                salary = row.getBigDecimalSafe("Salary"),
                homeAddress = row.getStringSafe("HomeAddress"),
                positionTitle = row.getStringSafe("PositionTitle")
            )
        }
    }

    suspend fun getById(id: Int): Employee? {
        val rows = pool.preparedQuery("EXEC sp_GetEmployee ?").execute(Tuple.of(id)).coAwait()
        val it = rows.iterator()
        return if (it.hasNext()) {
            val row = it.next()
            Employee(
                id = row.requireInt("Id"),
                fullName = row.requireString("FullName"),
                dateOfBirth = row.getLocalDateTimeSafe("DateOfBirth"),
                positionId = row.getShortSafe("PositionID"),
                salary = row.getBigDecimalSafe("Salary"),
                homeAddress = row.getStringSafe("HomeAddress"),
                positionTitle = row.getStringSafe("PositionTitle")
            )
        } else {
            null
        }
    }

    suspend fun create(
        fullName: String,
        dateOfBirth: LocalDateTime?,
        positionId: Short?,
        salary: BigDecimal?,
        homeAddress: String?
    ): Int {
        val rows = pool.preparedQuery("EXEC sp_SaveEmployee ?, ?, ?, ?, ?, ?")
            .execute(
                Tuple.of(0, fullName, dateOfBirth, positionId?.toInt(), salary, homeAddress)
            ).coAwait()
        return rows.iterator().next().requireInt("Id")
    }

    suspend fun update(employee: Employee): Int {
        val rows = pool.preparedQuery("EXEC sp_SaveEmployee ?, ?, ?, ?, ?, ?")
            .execute(
                Tuple.of(
                    employee.id,
                    employee.fullName,
                    employee.dateOfBirth,
                    employee.positionId?.toInt(),
                    employee.salary,
                    employee.homeAddress
                )
            ).coAwait()
        val it = rows.iterator()
        return if (it.hasNext()) it.next().requireInt("Id") else employee.id
    }

    suspend fun delete(id: Int): Int {
        val result = pool.preparedQuery("EXEC sp_DeleteEmployee ?")
            .execute(Tuple.of(id)).coAwait()
        return result.rowCount()
    }
}

class PositionRepository(private val pool: Pool) {
    suspend fun listAll(): List<Position> {
        val rows = pool.query("EXEC sp_ListPositions").execute().coAwait()
        return rows.map { Position(it.requireShort("Id"), it.requireString("Title")) }
    }

    suspend fun getById(id: Short): Position? {
        return listAll().firstOrNull { it.id == id }
    }

    suspend fun create(title: String): Int {
        val rows = pool.preparedQuery("EXEC sp_SavePosition ?, ?")
            .execute(Tuple.of(0, title)).coAwait()
        return rows.iterator().next().requireInt("Id")
    }

    suspend fun update(position: Position): Int {
        val rows = pool.preparedQuery("EXEC sp_SavePosition ?, ?")
            .execute(Tuple.of(position.id.toInt(), position.title)).coAwait()
        val it = rows.iterator()
        return if (it.hasNext()) it.next().requireInt("Id") else position.id.toInt()
    }

    suspend fun delete(id: Short): Int {
        val result = pool.preparedQuery("EXEC sp_DeletePosition ?")
            .execute(Tuple.of(id.toInt())).coAwait()
        return result.rowCount()
    }
}

class MeasurementUnitRepository(private val pool: Pool) {
    suspend fun listAll(): List<MeasurementUnit> {
        val rows = pool.query("EXEC sp_ListUnits").execute().coAwait()
        return rows.map { MeasurementUnit(it.requireInt("Id"), it.requireString("Name")) }
    }

    suspend fun getById(id: Int): MeasurementUnit? {
        val rows = pool.preparedQuery("EXEC sp_ListUnits").execute().coAwait()
        return rows.map { MeasurementUnit(it.requireInt("Id"), it.requireString("Name")) }
            .firstOrNull { it.id == id }
    }

    suspend fun create(name: String): Int {
        val rows = pool.preparedQuery("EXEC sp_SaveUnit ?, ?")
            .execute(Tuple.of(0, name)).coAwait()
        return rows.iterator().next().requireInt("Id")
    }

    suspend fun update(unit: MeasurementUnit): Int {
        val rows = pool.preparedQuery("EXEC sp_SaveUnit ?, ?")
            .execute(Tuple.of(unit.id, unit.name)).coAwait()
        val it = rows.iterator()
        return if (it.hasNext()) it.next().requireInt("Id") else unit.id
    }

    suspend fun delete(id: Int): Int {
        val result = pool.preparedQuery("EXEC sp_DeleteUnit ?")
            .execute(Tuple.of(id)).coAwait()
        return result.rowCount()
    }
}

class FinishedProductRepository(private val pool: Pool) {
    suspend fun listAll(): List<FinishedProduct> {
        val rows = pool.query("EXEC sp_ListFinishedProducts").execute().coAwait()
        return rows.map { row ->
            FinishedProduct(
                id = row.requireInt("Id"),
                name = row.requireString("Name"),
                unitId = row.requireInt("UnitID"),
                quantity = row.requireDouble("Quantity"),
                amount = row.requireDouble("Amount"),
                unitName = row.getStringSafe("UnitName")
            )
        }
    }

    suspend fun getById(id: Int): FinishedProduct? {
        val rows = pool.preparedQuery("EXEC sp_GetFinishedProduct ?").execute(Tuple.of(id)).coAwait()
        val it = rows.iterator()
        return if (it.hasNext()) {
            val row = it.next()
            FinishedProduct(
                id = row.requireInt("Id"),
                name = row.requireString("Name"),
                unitId = row.requireInt("UnitID"),
                quantity = row.requireDouble("Quantity"),
                amount = row.requireDouble("Amount"),
                unitName = row.getStringSafe("UnitName")
            )
        } else {
            null
        }
    }

    suspend fun create(name: String, unitId: Int, quantity: Double, amount: Double): Int {
        val rows = pool.preparedQuery("EXEC sp_SaveFinishedProduct ?, ?, ?, ?, ?")
            .execute(Tuple.of(0, name, unitId, quantity, amount)).coAwait()
        return rows.iterator().next().requireInt("Id")
    }

    suspend fun update(product: FinishedProduct): Int {
        val rows = pool.preparedQuery("EXEC sp_SaveFinishedProduct ?, ?, ?, ?, ?")
            .execute(Tuple.of(product.id, product.name, product.unitId, product.quantity, product.amount)).coAwait()
        val it = rows.iterator()
        return if (it.hasNext()) it.next().requireInt("Id") else product.id
    }

    suspend fun delete(id: Int): Int {
        val result = pool.preparedQuery("EXEC sp_DeleteFinishedProduct ?")
            .execute(Tuple.of(id)).coAwait()
        return result.rowCount()
    }
}

class RawMaterialRepository(private val pool: Pool) {
    suspend fun listAll(): List<RawMaterial> {
        val rows = pool.query("EXEC sp_ListRawMaterials").execute().coAwait()
        return rows.map { row ->
            RawMaterial(
                id = row.requireInt("Id"),
                name = row.requireString("Name"),
                unitId = row.requireInt("UnitID"),
                quantity = row.requireDouble("Quantity"),
                amount = row.requireDouble("Amount"),
                unitName = row.getStringSafe("UnitName")
            )
        }
    }

    suspend fun getById(id: Int): RawMaterial? {
        val rows = pool.preparedQuery("EXEC sp_GetRawMaterial ?").execute(Tuple.of(id)).coAwait()
        val it = rows.iterator()
        return if (it.hasNext()) {
            val row = it.next()
            RawMaterial(
                id = row.requireInt("Id"),
                name = row.requireString("Name"),
                unitId = row.requireInt("UnitID"),
                quantity = row.requireDouble("Quantity"),
                amount = row.requireDouble("Amount"),
                unitName = row.getStringSafe("UnitName")
            )
        } else {
            null
        }
    }

    suspend fun create(name: String, unitId: Int, quantity: Double, amount: Double): Int {
        val rows = pool.preparedQuery("EXEC sp_SaveRawMaterial ?, ?, ?, ?, ?")
            .execute(Tuple.of(0, name, unitId, quantity, amount)).coAwait()
        return rows.iterator().next().requireInt("Id")
    }

    suspend fun update(material: RawMaterial): Int {
        val rows = pool.preparedQuery("EXEC sp_SaveRawMaterial ?, ?, ?, ?, ?")
            .execute(Tuple.of(material.id, material.name, material.unitId, material.quantity, material.amount)).coAwait()
        val it = rows.iterator()
        return if (it.hasNext()) it.next().requireInt("Id") else material.id
    }

    suspend fun delete(id: Int): Int {
        val result = pool.preparedQuery("EXEC sp_DeleteRawMaterial ?")
            .execute(Tuple.of(id)).coAwait()
        return result.rowCount()
    }
}

class IngredientRepository(private val pool: Pool) {
    suspend fun listAll(): List<Ingredient> {
        val rows = pool.query("EXEC sp_ListIngredients").execute().coAwait()
        return rows.map { row ->
            Ingredient(
                id = row.requireInt("Id"),
                productId = row.requireInt("ProductID"),
                rawMaterialId = row.requireInt("RawMaterialID"),
                quantity = row.requireDouble("Quantity"),
                productName = row.getStringSafe("ProductName"),
                rawMaterialName = row.getStringSafe("RawMaterialName")
            )
        }
    }

    suspend fun getById(id: Int): Ingredient? {
        val rows = pool.preparedQuery("EXEC sp_GetIngredient ?").execute(Tuple.of(id)).coAwait()
        val it = rows.iterator()
        return if (it.hasNext()) {
            val row = it.next()
            Ingredient(
                id = row.requireInt("Id"),
                productId = row.requireInt("ProductID"),
                rawMaterialId = row.requireInt("RawMaterialID"),
                quantity = row.requireDouble("Quantity"),
                productName = row.getStringSafe("ProductName"),
                rawMaterialName = row.getStringSafe("RawMaterialName")
            )
        } else {
            null
        }
    }

    suspend fun create(productId: Int, rawMaterialId: Int, quantity: Double): Int {
        val rows = pool.preparedQuery("EXEC sp_SaveIngredient ?, ?, ?, ?")
            .execute(Tuple.of(0, productId, rawMaterialId, quantity)).coAwait()
        return rows.iterator().next().requireInt("Id")
    }

    suspend fun update(ingredient: Ingredient): Int {
        val rows = pool.preparedQuery("EXEC sp_SaveIngredient ?, ?, ?, ?")
            .execute(Tuple.of(ingredient.id, ingredient.productId, ingredient.rawMaterialId, ingredient.quantity)).coAwait()
        val it = rows.iterator()
        return if (it.hasNext()) it.next().requireInt("Id") else ingredient.id
    }

    suspend fun delete(id: Int): Int {
        val result = pool.preparedQuery("EXEC sp_DeleteIngredient ?")
            .execute(Tuple.of(id)).coAwait()
        return result.rowCount()
    }
}

class ProductProductionRepository(private val pool: Pool) {
    suspend fun listAll(): List<ProductProduction> {
        val rows = pool.query("EXEC sp_ListProduction").execute().coAwait()
        return rows.map { row ->
            ProductProduction(
                id = row.requireInt("Id"),
                productId = row.requireInt("ProductID"),
                quantity = row.requireDouble("Quantity"),
                productionDate = row.requireLocalDateTime("ProductionDate"),
                employeeId = row.requireInt("EmployeeID"),
                productName = row.getStringSafe("ProductName"),
                employeeName = row.getStringSafe("EmployeeName")
            )
        }
    }

    suspend fun getById(id: Int): ProductProduction? {
        val rows = pool.preparedQuery("EXEC sp_GetProduction ?").execute(Tuple.of(id)).coAwait()
        val it = rows.iterator()
        return if (it.hasNext()) {
            val row = it.next()
            ProductProduction(
                id = row.requireInt("Id"),
                productId = row.requireInt("ProductID"),
                quantity = row.requireDouble("Quantity"),
                productionDate = row.requireLocalDateTime("ProductionDate"),
                employeeId = row.requireInt("EmployeeID"),
                productName = row.getStringSafe("ProductName"),
                employeeName = row.getStringSafe("EmployeeName")
            )
        } else {
            null
        }
    }

    suspend fun create(
        productId: Int,
        quantity: Double,
        productionDate: LocalDateTime,
        employeeId: Int
    ): Int {
        val rows = pool.preparedQuery("EXEC sp_AddProductProduction ?, ?, ?, ?")
            .execute(Tuple.of(productId, quantity, productionDate, employeeId)).coAwait()
        val it = rows.iterator()
        return if (it.hasNext()) it.next().getInteger("Result") ?: 0 else 0
    }

    suspend fun update(production: ProductProduction): Int {
        val rows = pool.preparedQuery("EXEC sp_UpdateProduction ?, ?, ?, ?, ?")
            .execute(
                Tuple.of(
                    production.id,
                    production.productId,
                    production.quantity,
                    production.productionDate,
                    production.employeeId
                )
            ).coAwait()
        val it = rows.iterator()
        return if (it.hasNext()) it.next().requireInt("Id") else production.id
    }

    suspend fun delete(id: Int): Int {
        val result = pool.preparedQuery("EXEC sp_DeleteProduction ?")
            .execute(Tuple.of(id)).coAwait()
        return result.rowCount()
    }

    suspend fun deleteLast(): Int? {
        pool.query("EXEC sp_DeleteLastProduction").execute().coAwait()
        return null
    }
}

class ProductSalesRepository(private val pool: Pool) {
    suspend fun listAll(): List<ProductSale> {
        val rows = pool.query("EXEC sp_ListSales").execute().coAwait()
        return rows.map { row ->
            ProductSale(
                id = row.requireInt("SaleID"),
                productId = row.requireInt("ProductID"),
                quantity = row.requireDouble("Quantity"),
                amount = row.requireDouble("Amount"),
                saleDate = row.requireLocalDateTime("SaleDate"),
                employeeId = row.requireInt("EmployeeID"),
                productName = row.requireString("ProductName"),
                employeeName = row.requireString("EmployeeName")
            )
        }
    }

    suspend fun getById(id: Int): ProductSale? {
        val rows = pool.preparedQuery("EXEC sp_GetSale ?").execute(Tuple.of(id)).coAwait()
        val it = rows.iterator()
        return if (it.hasNext()) {
            val row = it.next()
            ProductSale(
                id = row.requireInt("SaleID"),
                productId = row.requireInt("ProductID"),
                quantity = row.requireDouble("Quantity"),
                amount = row.requireDouble("Amount"),
                saleDate = row.requireLocalDateTime("SaleDate"),
                employeeId = row.requireInt("EmployeeID"),
                productName = row.requireString("ProductName"),
                employeeName = row.requireString("EmployeeName")
            )
        } else {
            null
        }
    }

    suspend fun create(
        productId: Int,
        quantity: Double,
        saleDate: LocalDateTime,
        employeeId: Int
    ): Int {
        val rows = pool.preparedQuery("EXEC sp_AddProductSale ?, ?, ?, ?")
            .execute(Tuple.of(productId, quantity, saleDate, employeeId)).coAwait()
        val it = rows.iterator()
        return if (it.hasNext()) it.next().getInteger("Result") ?: 0 else 0
    }

    suspend fun update(sale: ProductSale): Int {
        val rows = pool.preparedQuery("EXEC sp_UpdateSale ?, ?, ?, ?, ?, ?")
            .execute(
                Tuple.of(
                    sale.id,
                    sale.productId,
                    sale.quantity,
                    sale.amount,
                    sale.saleDate,
                    sale.employeeId
                )
            ).coAwait()
        val it = rows.iterator()
        return if (it.hasNext()) it.next().requireInt("Id") else sale.id
    }

    suspend fun delete(id: Int): Int {
        val result = pool.preparedQuery("EXEC sp_DeleteSale ?")
            .execute(Tuple.of(id)).coAwait()
        return result.rowCount()
    }

    suspend fun deleteLast(): Int? {
        pool.query("EXEC sp_DeleteLastSale").execute().coAwait()
        return null
    }
}

class PurchaseRawMaterialRepository(private val pool: Pool) {
    suspend fun listAll(): List<PurchaseRawMaterial> {
        val rows = pool.query("EXEC sp_ListPurchases").execute().coAwait()
        return rows.map { row ->
            PurchaseRawMaterial(
                id = row.requireInt("Id"),
                rawMaterialId = row.requireInt("RawMaterialID"),
                quantity = row.requireDouble("Quantity"),
                amount = row.requireDouble("Amount"),
                purchaseDate = row.requireLocalDateTime("PurchaseDate"),
                employeeId = row.requireInt("EmployeeID"),
                rawMaterialName = row.getStringSafe("RawMaterialName"),
                employeeName = row.getStringSafe("EmployeeName")
            )
        }
    }

    suspend fun getById(id: Int): PurchaseRawMaterial? {
        val rows = pool.preparedQuery("EXEC sp_GetPurchase ?").execute(Tuple.of(id)).coAwait()
        val it = rows.iterator()
        return if (it.hasNext()) {
            val row = it.next()
            PurchaseRawMaterial(
                id = row.requireInt("Id"),
                rawMaterialId = row.requireInt("RawMaterialID"),
                quantity = row.requireDouble("Quantity"),
                amount = row.requireDouble("Amount"),
                purchaseDate = row.requireLocalDateTime("PurchaseDate"),
                employeeId = row.requireInt("EmployeeID"),
                rawMaterialName = row.getStringSafe("RawMaterialName"),
                employeeName = row.getStringSafe("EmployeeName")
            )
        } else {
            null
        }
    }

    suspend fun create(
        rawMaterialId: Int,
        quantity: Double,
        amount: Double,
        purchaseDate: LocalDateTime,
        employeeId: Int
    ): Int {
        pool.preparedQuery("EXEC sp_AddRawMaterialPurchase ?, ?, ?, ?, ?")
            .execute(Tuple.of(rawMaterialId, quantity, amount, purchaseDate, employeeId)).coAwait()
        return 0
    }

    suspend fun update(purchase: PurchaseRawMaterial): Int {
        val rows = pool.preparedQuery("EXEC sp_UpdatePurchase ?, ?, ?, ?, ?, ?")
            .execute(
                Tuple.of(
                    purchase.id,
                    purchase.rawMaterialId,
                    purchase.quantity,
                    purchase.amount,
                    purchase.purchaseDate,
                    purchase.employeeId
                )
            ).coAwait()
        val it = rows.iterator()
        return if (it.hasNext()) it.next().requireInt("Id") else purchase.id
    }

    suspend fun delete(id: Int): Int {
        val result = pool.preparedQuery("EXEC sp_DeletePurchase ?")
            .execute(Tuple.of(id)).coAwait()
        return result.rowCount()
    }

    suspend fun deleteLast(): Int? {
        pool.query("EXEC sp_DeleteLastPurchase").execute().coAwait()
        return null
    }
}

class SalaryPaymentRepository(private val pool: Pool) {
    suspend fun listAll(): List<SalaryPayment> {
        val rows = pool.query("EXEC sp_ListSalaryPayments").execute().coAwait()
        return rows.map { row ->
            SalaryPayment(
                id = row.requireInt("Id"),
                employeeId = row.requireInt("EmployeeID"),
                amount = row.requireDouble("Amount"),
                paymentDate = row.requireLocalDateTime("PaymentDate"),
                note = row.getStringSafe("Note"),
                employeeName = row.getStringSafe("EmployeeName")
            )
        }
    }

    suspend fun create(
        employeeId: Int,
        amount: Double?,
        paymentDate: LocalDateTime,
        note: String?
    ): Int {
        val rows = pool.preparedQuery("EXEC sp_AddSalaryPayment ?, ?, ?, ?")
            .execute(Tuple.of(employeeId, amount, paymentDate, note)).coAwait()
        val it = rows.iterator()
        return if (it.hasNext()) it.next().requireInt("Id") else 0
    }

    suspend fun delete(id: Int): Int {
        val result = pool.preparedQuery("EXEC sp_DeleteSalaryPayment ?")
            .execute(Tuple.of(id)).coAwait()
        return result.rowCount()
    }

    suspend fun deleteLast(): Int? {
        pool.query("EXEC sp_DeleteLastSalaryPayment").execute().coAwait()
        return null
    }
}

class ProductInventoryViewRepository(private val pool: Pool) {
    suspend fun listAll(): List<ProductInventoryView> {
        val rows = pool.query("EXEC sp_ListProductInventory").execute().coAwait()
        return rows.map { row ->
            ProductInventoryView(
                productId = row.requireInt("ProductID"),
                productName = row.requireString("ProductName"),
                unitName = row.requireString("UnitName"),
                currentQuantity = row.requireDouble("CurrentQuantity"),
                currentAmount = row.requireDouble("CurrentAmount"),
                totalProduced = row.getDoubleSafe("TotalProduced"),
                totalSold = row.getDoubleSafe("TotalSold"),
                totalSalesAmount = row.getDoubleSafe("TotalSalesAmount"),
                lastProductionDate = row.getLocalDateTimeSafe("LastProductionDate"),
                lastSaleDate = row.getLocalDateTimeSafe("LastSaleDate")
            )
        }
    }
}

class RawMaterialInventoryViewRepository(private val pool: Pool) {
    suspend fun listAll(): List<RawMaterialInventoryView> {
        val rows = pool.query("EXEC sp_ListRawMaterialInventory").execute().coAwait()
        return rows.map { row ->
            RawMaterialInventoryView(
                rawMaterialId = row.requireInt("RawMaterialID"),
                rawMaterialName = row.requireString("RawMaterialName"),
                unitName = row.requireString("UnitName"),
                currentQuantity = row.requireDouble("CurrentQuantity"),
                currentAmount = row.requireDouble("CurrentAmount"),
                totalPurchased = row.getDoubleSafe("TotalPurchased"),
                totalPurchaseAmount = row.getDoubleSafe("TotalPurchaseAmount"),
                lastPurchaseDate = row.getLocalDateTimeSafe("LastPurchaseDate"),
                totalConsumed = row.getDoubleSafe("TotalConsumed"),
                lastConsumptionDate = row.getLocalDateTimeSafe("LastConsumptionDate")
            )
        }
    }
}

class SalesExtendedViewRepository(private val pool: Pool) {
    suspend fun listAll(): List<SalesExtendedView> {
        val rows = pool.query("EXEC sp_ListSales").execute().coAwait()
        return rows.map { row ->
            SalesExtendedView(
                saleId = row.requireInt("SaleID"),
                saleDate = row.requireLocalDateTime("SaleDate"),
                quantity = row.requireDouble("Quantity"),
                amount = row.requireDouble("Amount"),
                pricePerUnit = row.getDoubleSafe("PricePerUnit"),
                productId = row.requireInt("ProductID"),
                productName = row.requireString("ProductName"),
                unitName = row.requireString("UnitName"),
                employeeId = row.requireInt("EmployeeID"),
                employeeName = row.requireString("EmployeeName"),
                employeePosition = row.getStringSafe("EmployeePosition")
            )
        }
    }
}

class CurrentBudgetViewRepository(private val pool: Pool) {
    suspend fun listAll(): List<CurrentBudgetView> {
        val rows = pool.query("EXEC sp_ListCurrentBudget").execute().coAwait()
        return rows.map { CurrentBudgetView(it.requireInt("Id"), it.requireDouble("BudgetAmount")) }
    }
}

class CreditRepository(private val pool: Pool) {
    suspend fun ensureSchema() {
        pool.query("EXEC sp_EnsureCreditSchema").execute().coAwait()
    }

    suspend fun listAll(): List<Credit> {
        val rows = pool.query("EXEC sp_ListCredits").execute().coAwait()
        return rows.map { row ->
            Credit(
                id = row.requireInt("Id"),
                bankName = row.requireString("BankName"),
                amount = row.requireDouble("Amount"),
                rate = row.requireDouble("Rate"),
                termMonths = row.requireInt("TermMonths"),
                startDate = row.requireLocalDate("StartDate"),
                monthlyPayment = row.requireDouble("MonthlyPayment"),
                remainingAmount = row.requireDouble("RemainingAmount"),
                isActive = when (val value = row.getValueAny("IsActive")) {
                    is Boolean -> value
                    is Number -> value.toInt() != 0
                    else -> false
                }
            )
        }
    }

    suspend fun getById(id: Int): Credit? {
        return listAll().firstOrNull { it.id == id }
    }

    suspend fun create(
        bankName: String,
        amount: Double,
        rate: Double,
        termMonths: Int,
        startDate: LocalDate
    ): Int {
        val rows = pool.preparedQuery("EXEC sp_AddCredit ?, ?, ?, ?, ?")
            .execute(Tuple.of(bankName, amount, rate, termMonths, startDate)).coAwait()
        val it = rows.iterator()
        return if (it.hasNext()) it.next().requireInt("Id") else 0
    }

    suspend fun pay(id: Int, paymentAmount: Double): Int {
        val rows = pool.preparedQuery("EXEC sp_PayCredit ?, ?")
                .execute(Tuple.of(id, paymentAmount)).coAwait()
        val it = rows.iterator()
        return if (it.hasNext()) it.next().requireInt("Id") else id
    }

    suspend fun delete(id: Int): Int {
        val result = pool.preparedQuery("EXEC sp_DeleteCredit ?")
                .execute(Tuple.of(id)).coAwait()
        return result.rowCount()
    }
}
