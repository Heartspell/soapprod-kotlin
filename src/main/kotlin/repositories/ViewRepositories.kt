package repositories

import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.Pool
import models.*

class ProductInventoryViewRepository(private val pool: Pool) {
    suspend fun listAll(): List<ProductInventoryView> {
        val rows = pool.query("EXEC sp_ListProductInventory").execute().coAwait()
        return rows.map { row ->
            ProductInventoryView(
                productId = row.requireInt("ProductID"), productName = row.requireString("ProductName"),
                unitName = row.requireString("UnitName"), currentQuantity = row.requireDouble("CurrentQuantity"),
                currentAmount = row.requireDouble("CurrentAmount"), totalProduced = row.getDoubleSafe("TotalProduced"),
                totalSold = row.getDoubleSafe("TotalSold"), totalSalesAmount = row.getDoubleSafe("TotalSalesAmount"),
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
                rawMaterialId = row.requireInt("RawMaterialID"), rawMaterialName = row.requireString("RawMaterialName"),
                unitName = row.requireString("UnitName"), currentQuantity = row.requireDouble("CurrentQuantity"),
                currentAmount = row.requireDouble("CurrentAmount"), totalPurchased = row.getDoubleSafe("TotalPurchased"),
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
                saleId = row.requireInt("SaleID"), saleDate = row.requireLocalDateTime("SaleDate"),
                quantity = row.requireDouble("Quantity"), amount = row.requireDouble("Amount"),
                pricePerUnit = row.getDoubleSafe("PricePerUnit"), productId = row.requireInt("ProductID"),
                productName = row.requireString("ProductName"), unitName = row.requireString("UnitName"),
                employeeId = row.requireInt("EmployeeID"), employeeName = row.requireString("EmployeeName"),
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
