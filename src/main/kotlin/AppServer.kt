import io.vertx.core.Vertx
import io.vertx.core.http.HttpServer
import io.vertx.core.json.Json
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.Route
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

class AppServer(
    private val vertx: Vertx,
    private val db: DbClient
) {
    private enum class SessionPermission { EDIT, DELETE }

    private companion object {
        const val SALE_MARKUP = 0.15
        const val MODULE_UNITS = "units"
        const val MODULE_POSITIONS = "positions"
        const val MODULE_EMPLOYEES = "employees"
        const val MODULE_SALARY = "salary"
        const val MODULE_RAW_MATERIALS = "raw_materials"
        const val MODULE_PRODUCTS = "products"
        const val MODULE_INGREDIENTS = "ingredients"
        const val MODULE_PURCHASE = "purchase"
        const val MODULE_PRODUCTION = "production"
        const val MODULE_SALES = "sales"
        const val MODULE_BUDGET = "budget"
        const val MODULE_USERS = "users"
    }

    private val employees = EmployeeRepository(db.pool)
    private val positions = PositionRepository(db.pool)
    private val units = MeasurementUnitRepository(db.pool)
    private val products = FinishedProductRepository(db.pool)
    private val rawMaterials = RawMaterialRepository(db.pool)
    private val ingredients = IngredientRepository(db.pool)
    private val production = ProductProductionRepository(db.pool)
    private val purchases = PurchaseRawMaterialRepository(db.pool)
    private val productSales = ProductSalesRepository(db.pool)
    private val salesExtended = SalesExtendedViewRepository(db.pool)
    private val productInventory = ProductInventoryViewRepository(db.pool)
    private val rawInventory = RawMaterialInventoryViewRepository(db.pool)
    private val budgets = BudgetRepository(db.pool)
    private val salaries = SalaryPaymentRepository(db.pool)
    private val credits = CreditRepository(db.pool)

    private val authRepo = AuthRepository(db.pool)
    private val authService = AuthService(authRepo)
    private val sessions = mutableMapOf<String, AuthSession>()
    private val templates = TemplateRenderer(Paths.get("frontend"))
    private val creditBanks = listOf(
        "Aiyl Bank",
        "Optima Bank",
        "DemirBank",
        "KICB",
        "Bakai Bank",
        "MBank",
        "RSK Bank"
    )

    suspend fun start(port: Int = 8080): HttpServer {
        authService.ensureSeed()
        credits.ensureSchema()

        val router = Router.router(vertx)
        router.route().handler(BodyHandler.create())

        router.get("/style.css").handler { ctx ->
            serveStaticFile(ctx, Paths.get("frontend", "style.css"))
        }

        registerApiRoutes(router)

        router.get("/login").handler { ctx ->
            ctx.response()
                .putHeader("Content-Type", "text/html; charset=utf-8")
                .end(loginPage(null))
        }

        router.post("/login").coroutineHandler { ctx ->
            val username = ctx.request().getParam("username")?.trim().orEmpty()
            val password = ctx.request().getParam("password")?.trim().orEmpty()
            val session = authService.tryLogin(username, password)
            if (session == null) {
                redirectWithAlert(ctx, "/login", "Invalid username or password")
                return@coroutineHandler
            }
            val sid = UUID.randomUUID().toString()
            sessions[sid] = session
            setSessionCookie(ctx, sid)
            redirect(ctx, "/")
        }

        router.get("/logout").handler { ctx ->
            readSid(ctx)?.let { sessions.remove(it) }
            clearSessionCookie(ctx)
            ctx.response().setStatusCode(303).putHeader("Location", "/login").end()
        }

        router.get("/").coroutineHandler { ctx ->
            val session = requireAuth(ctx, null) ?: return@coroutineHandler
            ctx.response()
                .putHeader("Content-Type", "text/html; charset=utf-8")
                .end(dashboardPage(session))
        }

        router.get("/units").coroutineHandler { ctx ->
            val session = requireAuth(ctx, setOf("Admin", "Purchasing")) ?: return@coroutineHandler
            val list = units.listAll()
            val editId = ctx.request().getParam("editId")?.toIntOrNull()
            val edit = editId?.let { units.getById(it) }
            ctx.response()
                .putHeader("Content-Type", "text/html; charset=utf-8")
                .end(unitsPage(session, list, edit))
        }

        router.post("/units/save").coroutineHandler { ctx ->
            val session = requireAuth(ctx, setOf("Admin", "Purchasing"), SessionPermission.EDIT, MODULE_UNITS) ?: return@coroutineHandler
            val id = ctx.request().getParam("id")?.toIntOrNull() ?: 0
            val name = ctx.request().getParam("name")?.trim().orEmpty()
            if (name.isBlank()) {
                badRequest(ctx, "Name is required")
                return@coroutineHandler
            }
            if (id == 0) units.create(name) else units.update(MeasurementUnit(id, name))
            redirect(ctx, "/units")
        }

        router.post("/units/delete").coroutineHandler { ctx ->
            val session = requireAuth(ctx, setOf("Admin", "Purchasing"), SessionPermission.DELETE, MODULE_UNITS) ?: return@coroutineHandler
            val id = ctx.request().getParam("id")?.toIntOrNull() ?: 0
            if (id > 0) units.delete(id)
            redirect(ctx, "/units")
        }

        router.get("/positions").coroutineHandler { ctx ->
            val session = requireAuth(ctx, setOf("Admin")) ?: return@coroutineHandler
            val list = positions.listAll()
            val editId = ctx.request().getParam("editId")?.toShortOrNull()
            val edit = editId?.let { positions.getById(it) }
            ctx.response()
                .putHeader("Content-Type", "text/html; charset=utf-8")
                .end(positionsPage(session, list, edit))
        }

        router.post("/positions/save").coroutineHandler { ctx ->
            val session = requireAuth(ctx, setOf("Admin"), SessionPermission.EDIT, MODULE_POSITIONS) ?: return@coroutineHandler
            val id = ctx.request().getParam("id")?.toShortOrNull() ?: 0
            val title = ctx.request().getParam("title")?.trim().orEmpty()
            if (title.isBlank()) {
                badRequest(ctx, "Title is required")
                return@coroutineHandler
            }
            if (id == 0.toShort()) positions.create(title) else positions.update(Position(id, title))
            redirect(ctx, "/positions")
        }

        router.post("/positions/delete").coroutineHandler { ctx ->
            val session = requireAuth(ctx, setOf("Admin"), SessionPermission.DELETE, MODULE_POSITIONS) ?: return@coroutineHandler
            val id = ctx.request().getParam("id")?.toShortOrNull() ?: 0
            if (id > 0) positions.delete(id)
            redirect(ctx, "/positions")
        }

        router.get("/employees").coroutineHandler { ctx ->
            val session = requireAuth(ctx, setOf("Admin")) ?: return@coroutineHandler
            val list = employees.listAll()
            val positionsList = positions.listAll()
            val editId = ctx.request().getParam("editId")?.toIntOrNull()
            val edit = editId?.let { employees.getById(it) }
            ctx.response()
                .putHeader("Content-Type", "text/html; charset=utf-8")
                .end(employeesPage(session, list, positionsList, edit))
        }

        router.post("/employees/save").coroutineHandler { ctx ->
            val session = requireAuth(ctx, setOf("Admin"), SessionPermission.EDIT, MODULE_EMPLOYEES) ?: return@coroutineHandler
            val id = ctx.request().getParam("id")?.toIntOrNull() ?: 0
            val fullName = ctx.request().getParam("fullName")?.trim().orEmpty()
            val positionId = ctx.request().getParam("positionId")?.toShortOrNull()
            val salary = ctx.request().getParam("salary")?.toBigDecimalOrNull()
            val dateOfBirth = parseDateTimeNullable(ctx.request().getParam("dateOfBirth"))
            val homeAddress = ctx.request().getParam("homeAddress")?.trim()
            if (fullName.isBlank() || salary == null) {
                badRequest(ctx, "FullName and Salary are required")
                return@coroutineHandler
            }
            if (id == 0) {
                employees.create(fullName, dateOfBirth, positionId, salary, homeAddress)
            } else {
                employees.update(
                    Employee(
                        id = id,
                        fullName = fullName,
                        dateOfBirth = dateOfBirth,
                        positionId = positionId,
                        salary = salary,
                        homeAddress = homeAddress,
                        positionTitle = null
                    )
                )
            }
            redirect(ctx, "/employees")
        }

        router.post("/employees/delete").coroutineHandler { ctx ->
            val session = requireAuth(ctx, setOf("Admin"), SessionPermission.DELETE, MODULE_EMPLOYEES) ?: return@coroutineHandler
            val id = ctx.request().getParam("id")?.toIntOrNull() ?: 0
            if (id > 0) employees.delete(id)
            redirect(ctx, "/employees")
        }

        router.get("/salary").coroutineHandler { ctx ->
            val session = requireAuth(ctx, null) ?: return@coroutineHandler
            val list = salaries.listAll()
            val employeesList = employees.listAll()
            ctx.response()
                .putHeader("Content-Type", "text/html; charset=utf-8")
                .end(salaryPage(session, list, employeesList))
        }

        router.get("/salary/").coroutineHandler { ctx ->
            val session = requireAuth(ctx, null) ?: return@coroutineHandler
            redirect(ctx, "/salary")
        }

        router.post("/salary/save").coroutineHandler { ctx ->
            val session = requireAuth(ctx, null, SessionPermission.EDIT, MODULE_SALARY) ?: return@coroutineHandler
            val employeeId = ctx.request().getParam("employeeId")?.toIntOrNull()
            val amountParam = ctx.request().getParam("amount")?.toDoubleOrNull()
            val paymentDate = parseDateTime(ctx.request().getParam("paymentDate"))
            val note = ctx.request().getParam("note")?.trim()?.takeIf { it.isNotBlank() }
            if (employeeId == null) {
                badRequest(ctx, "Employee is required")
                return@coroutineHandler
            }
            salaries.create(employeeId, amountParam, paymentDate, note)
            redirect(ctx, "/salary")
        }

        router.post("/salary/delete").coroutineHandler { ctx ->
            val session = requireAuth(ctx, null, SessionPermission.DELETE, MODULE_SALARY) ?: return@coroutineHandler
            val id = ctx.request().getParam("id")?.toIntOrNull() ?: 0
            if (id > 0) salaries.delete(id)
            redirect(ctx, "/salary")
        }

        router.post("/salary/rollback").coroutineHandler { ctx ->
            val session = requireAuth(ctx, null, SessionPermission.DELETE, MODULE_SALARY) ?: return@coroutineHandler
            salaries.deleteLast()
            redirect(ctx, "/salary")
        }

        router.get("/raw-materials").coroutineHandler { ctx ->
            val session = requireAuth(ctx, setOf("Admin", "Purchasing")) ?: return@coroutineHandler
            val list = rawMaterials.listAll()
            val unitsList = units.listAll()
            val editId = ctx.request().getParam("editId")?.toIntOrNull()
            val edit = editId?.let { rawMaterials.getById(it) }
            ctx.response()
                .putHeader("Content-Type", "text/html; charset=utf-8")
                .end(rawMaterialsPage(session, list, unitsList, edit))
        }

        router.post("/raw-materials/save").coroutineHandler { ctx ->
            val session = requireAuth(ctx, setOf("Admin", "Purchasing"), SessionPermission.EDIT, MODULE_RAW_MATERIALS) ?: return@coroutineHandler
            val id = ctx.request().getParam("id")?.toIntOrNull() ?: 0
            val name = ctx.request().getParam("name")?.trim().orEmpty()
            val unitId = ctx.request().getParam("unitId")?.toIntOrNull()
            if (name.isBlank() || unitId == null) {
                badRequest(ctx, "Name and UnitId are required")
                return@coroutineHandler
            }
            if (id == 0) {
                rawMaterials.create(name, unitId, 0.0, 0.0)
            } else {
                val existing = rawMaterials.getById(id)
                if (existing == null) {
                    badRequest(ctx, "Raw material not found")
                    return@coroutineHandler
                }
                rawMaterials.update(
                    RawMaterial(id, name, unitId, existing.quantity, existing.amount, null)
                )
            }
            redirect(ctx, "/raw-materials")
        }

        router.post("/raw-materials/delete").coroutineHandler { ctx ->
            val session = requireAuth(ctx, setOf("Admin", "Purchasing"), SessionPermission.DELETE, MODULE_RAW_MATERIALS) ?: return@coroutineHandler
            val id = ctx.request().getParam("id")?.toIntOrNull() ?: 0
            if (id > 0) rawMaterials.delete(id)
            redirect(ctx, "/raw-materials")
        }

        router.get("/products").coroutineHandler { ctx ->
            val session = requireAuth(ctx, setOf("Admin", "Sales", "Production")) ?: return@coroutineHandler
            val list = products.listAll()
            val unitsList = units.listAll()
            val editId = ctx.request().getParam("editId")?.toIntOrNull()
            val edit = editId?.let { products.getById(it) }
            ctx.response()
                .putHeader("Content-Type", "text/html; charset=utf-8")
                .end(productsPage(session, list, unitsList, edit))
        }

        router.post("/products/save").coroutineHandler { ctx ->
            val session = requireAuth(ctx, setOf("Admin", "Sales", "Production"), SessionPermission.EDIT, MODULE_PRODUCTS) ?: return@coroutineHandler
            val id = ctx.request().getParam("id")?.toIntOrNull() ?: 0
            val name = ctx.request().getParam("name")?.trim().orEmpty()
            val unitId = ctx.request().getParam("unitId")?.toIntOrNull()
            val unitCost = ctx.request().getParam("unitCost")?.toDoubleOrNull()
            val amountParam = ctx.request().getParam("amount")?.toDoubleOrNull()
            if (name.isBlank() || unitId == null || (unitCost == null && amountParam == null)) {
                badRequest(ctx, "Name, UnitId and Cost are required")
                return@coroutineHandler
            }
            if (id == 0) {
                products.create(name, unitId, 0.0, amountParam ?: 0.0)
            } else {
                val existing = products.getById(id)
                if (existing == null) {
                    badRequest(ctx, "Product not found")
                    return@coroutineHandler
                }
                val amount = unitCost?.let { existing.quantity * it } ?: amountParam ?: existing.amount
                products.update(
                    FinishedProduct(id, name, unitId, existing.quantity, amount, null)
                )
            }
            redirect(ctx, "/products")
        }

        router.post("/products/delete").coroutineHandler { ctx ->
            val session = requireAuth(ctx, setOf("Admin", "Sales", "Production"), SessionPermission.DELETE, MODULE_PRODUCTS) ?: return@coroutineHandler
            val id = ctx.request().getParam("id")?.toIntOrNull() ?: 0
            if (id > 0) products.delete(id)
            redirect(ctx, "/products")
        }

        router.get("/ingredients").coroutineHandler { ctx ->
            val session = requireAuth(ctx, setOf("Admin", "Production")) ?: return@coroutineHandler
            val list = ingredients.listAll()
            val productsList = products.listAll()
            val rawList = rawMaterials.listAll()
            val editId = ctx.request().getParam("editId")?.toIntOrNull()
            val edit = editId?.let { ingredients.getById(it) }
            ctx.response()
                .putHeader("Content-Type", "text/html; charset=utf-8")
                .end(ingredientsPage(session, list, productsList, rawList, edit))
        }

        router.post("/ingredients/save").coroutineHandler { ctx ->
            val session = requireAuth(ctx, setOf("Admin", "Production"), SessionPermission.EDIT, MODULE_INGREDIENTS) ?: return@coroutineHandler
            val id = ctx.request().getParam("id")?.toIntOrNull() ?: 0
            val productId = ctx.request().getParam("productId")?.toIntOrNull()
            val rawMaterialId = ctx.request().getParam("rawMaterialId")?.toIntOrNull()
            val quantity = ctx.request().getParam("quantity")?.toDoubleOrNull()
            if (productId == null || rawMaterialId == null || quantity == null) {
                badRequest(ctx, "ProductId, RawMaterialId, Quantity are required")
                return@coroutineHandler
            }
            if (id == 0) {
                ingredients.create(productId, rawMaterialId, quantity)
            } else {
                ingredients.update(Ingredient(id, productId, rawMaterialId, quantity, null, null))
            }
            redirect(ctx, "/ingredients")
        }

        router.post("/ingredients/delete").coroutineHandler { ctx ->
            val session = requireAuth(ctx, setOf("Admin", "Production"), SessionPermission.DELETE, MODULE_INGREDIENTS) ?: return@coroutineHandler
            val id = ctx.request().getParam("id")?.toIntOrNull() ?: 0
            if (id > 0) ingredients.delete(id)
            redirect(ctx, "/ingredients")
        }
        router.get("/purchase").coroutineHandler { ctx ->
            val session = requireAuth(ctx, setOf("Admin", "Purchasing")) ?: return@coroutineHandler
            val list = purchases.listAll()
            val rawList = rawMaterials.listAll()
            val employeesList = employees.listAll()
            ctx.response()
                .putHeader("Content-Type", "text/html; charset=utf-8")
                .end(purchasePage(session, list, rawList, employeesList, null))
        }

        router.post("/purchase/save").coroutineHandler { ctx ->
            val session = requireAuth(ctx, setOf("Admin", "Purchasing"), SessionPermission.EDIT, MODULE_PURCHASE) ?: return@coroutineHandler
            val rawMaterialId = ctx.request().getParam("rawMaterialId")?.toIntOrNull()
            val quantity = ctx.request().getParam("quantity")?.toDoubleOrNull()
            val unitPrice = ctx.request().getParam("unitPrice")?.toDoubleOrNull()
            val employeeId = ctx.request().getParam("employeeId")?.toIntOrNull()
            val purchaseDate = parseDateTime(ctx.request().getParam("purchaseDate"))
            if (rawMaterialId == null || quantity == null || unitPrice == null || employeeId == null) {
                badRequest(ctx, "RawMaterialId, Quantity, UnitPrice, EmployeeId are required")
                return@coroutineHandler
            }
            if (quantity <= 0.0 || unitPrice <= 0.0) {
                badRequest(ctx, "Quantity and UnitPrice must be greater than 0")
                return@coroutineHandler
            }
            val amount = quantity * unitPrice
            purchases.create(rawMaterialId, quantity, amount, purchaseDate, employeeId)
            redirect(ctx, "/purchase")
        }

        router.post("/purchase/delete").coroutineHandler { ctx ->
            val session = requireAuth(ctx, setOf("Admin", "Purchasing"), SessionPermission.DELETE, MODULE_PURCHASE) ?: return@coroutineHandler
            val id = ctx.request().getParam("id")?.toIntOrNull() ?: 0
            if (id > 0) purchases.delete(id)
            redirect(ctx, "/purchase")
        }

        router.post("/purchase/rollback").coroutineHandler { ctx ->
            val session = requireAuth(ctx, setOf("Admin", "Purchasing"), SessionPermission.DELETE, MODULE_PURCHASE) ?: return@coroutineHandler
            purchases.deleteLast()
            redirect(ctx, "/purchase")
        }

        router.get("/production").coroutineHandler { ctx ->
            val session = requireAuth(ctx, setOf("Admin", "Production")) ?: return@coroutineHandler
            val list = production.listAll()
            val productsList = products.listAll()
            val employeesList = employees.listAll()
            ctx.response()
                .putHeader("Content-Type", "text/html; charset=utf-8")
                .end(productionPage(session, list, productsList, employeesList))
        }

        router.post("/production/save").coroutineHandler { ctx ->
            val session = requireAuth(ctx, setOf("Admin", "Production"), SessionPermission.EDIT, MODULE_PRODUCTION) ?: return@coroutineHandler
            val productId = ctx.request().getParam("productId")?.toIntOrNull()
            val quantity = ctx.request().getParam("quantity")?.toDoubleOrNull()
            val employeeId = ctx.request().getParam("employeeId")?.toIntOrNull()
            val productionDate = parseDateTime(ctx.request().getParam("productionDate"))
            if (productId == null || quantity == null || employeeId == null) {
                badRequest(ctx, "ProductId, Quantity, EmployeeId are required")
                return@coroutineHandler
            }
            production.create(productId, quantity, productionDate, employeeId)
            redirect(ctx, "/production")
        }

        router.post("/production/delete").coroutineHandler { ctx ->
            val session = requireAuth(ctx, setOf("Admin", "Production"), SessionPermission.DELETE, MODULE_PRODUCTION) ?: return@coroutineHandler
            val id = ctx.request().getParam("id")?.toIntOrNull() ?: 0
            if (id > 0) production.delete(id)
            redirect(ctx, "/production")
        }

        router.post("/production/rollback").coroutineHandler { ctx ->
            val session = requireAuth(ctx, setOf("Admin", "Production"), SessionPermission.DELETE, MODULE_PRODUCTION) ?: return@coroutineHandler
            production.deleteLast()
            redirect(ctx, "/production")
        }

        router.get("/sales").coroutineHandler { ctx ->
            val session = requireAuth(ctx, setOf("Admin", "Sales")) ?: return@coroutineHandler
            val list = productSales.listAll()
            val productsList = products.listAll()
            val employeesList = employees.listAll()
            ctx.response()
                .putHeader("Content-Type", "text/html; charset=utf-8")
                .end(salesPage(session, list, productsList, employeesList))
        }

        router.post("/sales/save").coroutineHandler { ctx ->
            val session = requireAuth(ctx, setOf("Admin", "Sales"), SessionPermission.EDIT, MODULE_SALES) ?: return@coroutineHandler
            val productId = ctx.request().getParam("productId")?.toIntOrNull()
            val quantity = ctx.request().getParam("quantity")?.toDoubleOrNull()
            val employeeId = ctx.request().getParam("employeeId")?.toIntOrNull()
            val saleDate = parseDateTime(ctx.request().getParam("saleDate"))
            if (productId == null || quantity == null || employeeId == null) {
                badRequest(ctx, "ProductId, Quantity, EmployeeId are required")
                return@coroutineHandler
            }
            if (quantity <= 0.0) {
                badRequest(ctx, "Quantity must be greater than 0")
                return@coroutineHandler
            }
            productSales.create(productId, quantity, saleDate, employeeId)
            redirect(ctx, "/sales")
        }

        router.post("/sales/delete").coroutineHandler { ctx ->
            val session = requireAuth(ctx, setOf("Admin", "Sales"), SessionPermission.DELETE, MODULE_SALES) ?: return@coroutineHandler
            val id = ctx.request().getParam("id")?.toIntOrNull() ?: 0
            if (id > 0) productSales.delete(id)
            redirect(ctx, "/sales")
        }

        router.post("/sales/rollback").coroutineHandler { ctx ->
            val session = requireAuth(ctx, setOf("Admin", "Sales"), SessionPermission.DELETE, MODULE_SALES) ?: return@coroutineHandler
            productSales.deleteLast()
            redirect(ctx, "/sales")
        }

        router.get("/reports").coroutineHandler { ctx ->
            val session = requireAuth(ctx, setOf("Admin", "Sales")) ?: return@coroutineHandler
            val raw = rawInventory.listAll()
            val prod = productInventory.listAll()
            val sales = salesExtended.listAll()
            ctx.response()
                .putHeader("Content-Type", "text/html; charset=utf-8")
                .end(reportsPage(session, raw, prod, sales))
        }

        router.get("/budget").coroutineHandler { ctx ->
            val session = requireAuth(ctx, setOf("Admin")) ?: return@coroutineHandler
            val list = budgets.listAll()
            ctx.response()
                .putHeader("Content-Type", "text/html; charset=utf-8")
                .end(budgetPage(session, list))
        }

        router.post("/budget/save").coroutineHandler { ctx ->
            val session = requireAuth(ctx, setOf("Admin"), SessionPermission.EDIT, MODULE_BUDGET) ?: return@coroutineHandler
            val amount = ctx.request().getParam("amount")?.toDoubleOrNull()
            if (amount == null) {
                badRequest(ctx, "Amount is required")
                return@coroutineHandler
            }
            budgets.create(amount)
            redirect(ctx, "/budget")
        }

        router.post("/credits/save").coroutineHandler { ctx ->
            requireAuth(ctx, setOf("Admin"), SessionPermission.EDIT, MODULE_BUDGET) ?: return@coroutineHandler
            val bankName = ctx.request().getParam("bank")?.trim().orEmpty()
            val amount = ctx.request().getParam("amount")?.toDoubleOrNull()
            val rate = ctx.request().getParam("rate")?.toDoubleOrNull()
            val termMonths = ctx.request().getParam("termMonths")?.toIntOrNull()
            val startDate = ctx.request().getParam("startDate")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

            if (bankName.isBlank() || amount == null || rate == null || termMonths == null || startDate == null) {
                badRequest(ctx, "Bank, Amount, Rate, Term and Start date are required")
                return@coroutineHandler
            }
            if (amount <= 0.0 || termMonths <= 0) {
                badRequest(ctx, "Amount and Term must be greater than 0")
                return@coroutineHandler
            }

            credits.create(bankName, amount, rate, termMonths, startDate)

            redirect(ctx, "/budget")
        }

        router.post("/credits/pay").coroutineHandler { ctx ->
            requireAuth(ctx, setOf("Admin"), SessionPermission.EDIT, MODULE_BUDGET) ?: return@coroutineHandler
            val id = ctx.request().getParam("id")?.toIntOrNull() ?: 0
            val paymentAmount = ctx.request().getParam("paymentAmount")?.toDoubleOrNull()
            if (id <= 0 || paymentAmount == null || paymentAmount <= 0.0) {
                badRequest(ctx, "Credit and Payment amount are required")
                return@coroutineHandler
            }

            credits.pay(id, paymentAmount)

            redirect(ctx, "/budget")
        }

        router.post("/credits/delete").coroutineHandler { ctx ->
            requireAuth(ctx, setOf("Admin"), SessionPermission.DELETE, MODULE_BUDGET) ?: return@coroutineHandler
            val id = ctx.request().getParam("id")?.toIntOrNull() ?: 0
            if (id > 0) credits.delete(id)
            redirect(ctx, "/budget")
        }

        router.get("/admin/users").coroutineHandler { ctx ->
            val session = requireAuth(ctx, setOf("Admin")) ?: return@coroutineHandler
            val users = authRepo.getUsersWithRoles()
            val roles = authRepo.getRoles()
            val employeesList = employees.listAll()
            val editId = ctx.request().getParam("editId")?.toIntOrNull()
            val edit = editId?.let { id -> users.firstOrNull { it.id == id } }
            ctx.response()
                .putHeader("Content-Type", "text/html; charset=utf-8")
                .end(adminUsersPage(session, users, roles, employeesList, edit, null))
        }

        router.post("/admin/users/save").coroutineHandler { ctx ->
            val session = requireAuth(ctx, setOf("Admin"), SessionPermission.EDIT, MODULE_USERS) ?: return@coroutineHandler
            val id = ctx.request().getParam("id")?.toIntOrNull() ?: 0
            val username = ctx.request().getParam("username")?.trim().orEmpty()
            val password = ctx.request().getParam("password")?.trim().orEmpty()
            val isActive = ctx.request().getParam("isActive") == "on"
            val employeeId = ctx.request().getParam("employeeId")?.toIntOrNull()
            val roles = authRepo.getRoles()
            val selectedRoleIds = roles.filter { ctx.request().getParam("role_${it.id}") == "on" }.map { it.id }

            if (username.isBlank()) {
                redirectWithAlert(ctx, "/admin/users", "Username is required")
                return@coroutineHandler
            }

            if (id == 0) {
                if (password.isBlank()) {
                    redirectWithAlert(ctx, "/admin/users", "Password is required for new user")
                    return@coroutineHandler
                }
                val (hash, salt) = authService.createPasswordHash(password)
                authRepo.saveUserWithRoles(0, username, isActive, hash, salt, selectedRoleIds, employeeId)
            } else {
                val hashSalt = if (password.isBlank()) null else authService.createPasswordHash(password)
                authRepo.saveUserWithRoles(id, username, isActive, hashSalt?.first, hashSalt?.second, selectedRoleIds, employeeId)
            }
            redirect(ctx, "/admin/users")
        }

        router.post("/admin/users/delete").coroutineHandler { ctx ->
            val session = requireAuth(ctx, setOf("Admin"), SessionPermission.DELETE, MODULE_USERS) ?: return@coroutineHandler
            val id = ctx.request().getParam("id")?.toIntOrNull() ?: 0
            if (id > 0) authRepo.deleteUser(id)
            redirect(ctx, "/admin/users")
        }

        router.post("/admin/roles/permissions/save").coroutineHandler { ctx ->
            requireAuth(ctx, setOf("Admin"), SessionPermission.EDIT, MODULE_USERS) ?: return@coroutineHandler
            val roleId = ctx.request().getParam("roleId")?.toIntOrNull() ?: 0
            if (roleId <= 0) {
                badRequest(ctx, "Role is required")
                return@coroutineHandler
            }
            MODULE_PERMISSION_LABELS.keys.forEach { moduleKey ->
                authRepo.saveRolePermission(
                    roleId = roleId,
                    moduleKey = moduleKey,
                    canEdit = ctx.request().getParam("${moduleKey}_edit") == "on",
                    canDelete = ctx.request().getParam("${moduleKey}_delete") == "on"
                )
            }
            redirect(ctx, "/admin/users")
        }

        router.route().last().handler { ctx ->
            ctx.response().setStatusCode(404)
                .putHeader("Content-Type", "text/html; charset=utf-8")
                .end(simplePage("Not found", "<h1>Page not found</h1><p><a href=\"/\">Back</a></p>"))
        }

        return vertx.createHttpServer()
            .requestHandler(router)
            .listen(port)
            .coAwait()
    }
    private fun Route.coroutineHandler(block: suspend (RoutingContext) -> Unit) {
        handler { ctx ->
            CoroutineScope(ctx.vertx().dispatcher()).launch {
                try {
                    block(ctx)
                } catch (ex: Exception) {
                    if (!ctx.response().ended()) {
                        val message = presentableErrorMessage(ex, ctx.normalizedPath())
                        if (ctx.normalizedPath().startsWith("/api/")) {
                            apiError(ctx, 500, message)
                        } else if (ctx.request().method().name() == "POST") {
                            redirectBackWithAlert(ctx, message)
                        } else {
                            ctx.response().setStatusCode(500).end(message)
                        }
                    }
                }
            }
        }
    }

    private fun registerApiRoutes(router: Router) {
        router.get("/api/session").handler { ctx ->
            val session = readSid(ctx)?.let { sessions[it] }
            if (session == null) {
                apiJson(ctx, mapOf("authenticated" to false))
            } else {
                apiJson(
                    ctx,
                    mapOf(
                        "authenticated" to true,
                        "user" to mapOf(
                            "id" to session.userId,
                            "username" to session.username,
                            "roles" to session.roles.sorted(),
                            "canEdit" to session.canEdit,
                            "canDelete" to session.canDelete,
                            "navigation" to navigationItems(session)
                        )
                    )
                )
            }
        }

        router.post("/api/login").coroutineHandler { ctx ->
            val body = jsonBody(ctx)
            val username = body.getString("username")?.trim().orEmpty()
            val password = body.getString("password")?.trim().orEmpty()
            val session = authService.tryLogin(username, password)
            if (session == null) {
                apiError(ctx, 401, "Invalid username or password")
                return@coroutineHandler
            }
            val sid = UUID.randomUUID().toString()
            sessions[sid] = session
            setSessionCookie(ctx, sid)
            apiJson(
                ctx,
                mapOf(
                    "ok" to true,
                    "user" to mapOf(
                        "id" to session.userId,
                        "username" to session.username,
                        "roles" to session.roles.sorted(),
                        "canEdit" to session.canEdit,
                        "canDelete" to session.canDelete,
                        "navigation" to navigationItems(session)
                    )
                )
            )
        }

        router.post("/api/logout").handler { ctx ->
            readSid(ctx)?.let { sessions.remove(it) }
            clearSessionCookie(ctx)
            apiJson(ctx, mapOf("ok" to true))
        }

        router.get("/api/dashboard").coroutineHandler { ctx ->
            val session = requireApiAuth(ctx, null) ?: return@coroutineHandler
            apiJson(
                ctx,
                mapOf(
                    "user" to mapOf(
                        "username" to session.username,
                        "roles" to session.roles.sorted(),
                        "canEdit" to session.canEdit,
                        "canDelete" to session.canDelete
                    ),
                    "summary" to mapOf(
                        "employees" to employees.listAll().size,
                        "rawMaterials" to rawMaterials.listAll().size,
                        "products" to products.listAll().size,
                        "sales" to productSales.listAll().size
                    )
                )
            )
        }

        router.get("/api/lookups").coroutineHandler { ctx ->
            requireApiAuth(ctx, null) ?: return@coroutineHandler
            apiJson(
                ctx,
                mapOf(
                    "units" to safeLookup { units.listAll() },
                    "positions" to safeLookup { positions.listAll() },
                    "employees" to safeLookup {
                        employees.listAll().map { mapOf("id" to it.id, "name" to it.fullName) }
                    },
                    "rawMaterials" to safeLookup {
                        rawMaterials.listAll().map { mapOf("id" to it.id, "name" to it.name) }
                    },
                    "products" to safeLookup {
                        products.listAll().map { mapOf("id" to it.id, "name" to it.name) }
                    },
                    "roles" to safeLookup { authRepo.getRoles() }
                )
            )
        }

        router.get("/api/units").coroutineHandler { ctx ->
            requireApiAuth(ctx, setOf("Admin", "Purchasing")) ?: return@coroutineHandler
            apiJson(ctx, units.listAll())
        }
        router.post("/api/units").coroutineHandler { ctx ->
            requireApiAuth(ctx, setOf("Admin", "Purchasing"), SessionPermission.EDIT, MODULE_UNITS) ?: return@coroutineHandler
            val body = jsonBody(ctx)
            val id = body.getInteger("id") ?: 0
            val name = body.getString("name")?.trim().orEmpty()
            if (name.isBlank()) {
                apiError(ctx, 400, "Name is required")
                return@coroutineHandler
            }
            if (id == 0) units.create(name) else units.update(MeasurementUnit(id, name))
            apiJson(ctx, mapOf("ok" to true))
        }
        router.delete("/api/units/:id").coroutineHandler { ctx ->
            requireApiAuth(ctx, setOf("Admin", "Purchasing"), SessionPermission.DELETE, MODULE_UNITS) ?: return@coroutineHandler
            ctx.pathParam("id")?.toIntOrNull()?.takeIf { it > 0 }?.let { units.delete(it) }
            apiJson(ctx, mapOf("ok" to true))
        }

        router.get("/api/positions").coroutineHandler { ctx ->
            requireApiAuth(ctx, setOf("Admin")) ?: return@coroutineHandler
            apiJson(ctx, positions.listAll())
        }
        router.post("/api/positions").coroutineHandler { ctx ->
            requireApiAuth(ctx, setOf("Admin"), SessionPermission.EDIT, MODULE_POSITIONS) ?: return@coroutineHandler
            val body = jsonBody(ctx)
            val id = (body.getInteger("id") ?: 0).toShort()
            val title = body.getString("title")?.trim().orEmpty()
            if (title.isBlank()) {
                apiError(ctx, 400, "Title is required")
                return@coroutineHandler
            }
            if (id == 0.toShort()) positions.create(title) else positions.update(Position(id, title))
            apiJson(ctx, mapOf("ok" to true))
        }
        router.delete("/api/positions/:id").coroutineHandler { ctx ->
            requireApiAuth(ctx, setOf("Admin"), SessionPermission.DELETE, MODULE_POSITIONS) ?: return@coroutineHandler
            ctx.pathParam("id")?.toShortOrNull()?.takeIf { it > 0 }?.let { positions.delete(it) }
            apiJson(ctx, mapOf("ok" to true))
        }

        router.get("/api/employees").coroutineHandler { ctx ->
            requireApiAuth(ctx, setOf("Admin")) ?: return@coroutineHandler
            apiJson(ctx, employees.listAll())
        }
        router.post("/api/employees").coroutineHandler { ctx ->
            requireApiAuth(ctx, setOf("Admin"), SessionPermission.EDIT, MODULE_EMPLOYEES) ?: return@coroutineHandler
            val body = jsonBody(ctx)
            val id = body.getInteger("id") ?: 0
            val fullName = body.getString("fullName")?.trim().orEmpty()
            val positionId = body.getInteger("positionId")?.toShort()
            val salary = body.getString("salary")?.toBigDecimalOrNull()
            val dateOfBirth = parseDateTimeNullable(body.getString("dateOfBirth"))
            val homeAddress = body.getString("homeAddress")?.trim()
            if (fullName.isBlank() || salary == null) {
                apiError(ctx, 400, "Full name and salary are required")
                return@coroutineHandler
            }
            if (id == 0) {
                employees.create(fullName, dateOfBirth, positionId, salary, homeAddress)
            } else {
                employees.update(Employee(id, fullName, dateOfBirth, positionId, salary, homeAddress, null))
            }
            apiJson(ctx, mapOf("ok" to true))
        }
        router.delete("/api/employees/:id").coroutineHandler { ctx ->
            requireApiAuth(ctx, setOf("Admin"), SessionPermission.DELETE, MODULE_EMPLOYEES) ?: return@coroutineHandler
            ctx.pathParam("id")?.toIntOrNull()?.takeIf { it > 0 }?.let { employees.delete(it) }
            apiJson(ctx, mapOf("ok" to true))
        }

        router.get("/api/raw-materials").coroutineHandler { ctx ->
            requireApiAuth(ctx, setOf("Admin", "Purchasing")) ?: return@coroutineHandler
            apiJson(ctx, rawMaterials.listAll())
        }
        router.post("/api/raw-materials").coroutineHandler { ctx ->
            requireApiAuth(ctx, setOf("Admin", "Purchasing"), SessionPermission.EDIT, MODULE_RAW_MATERIALS) ?: return@coroutineHandler
            val body = jsonBody(ctx)
            val id = body.getInteger("id") ?: 0
            val name = body.getString("name")?.trim().orEmpty()
            val unitId = body.getInteger("unitId")
            if (name.isBlank() || unitId == null) {
                apiError(ctx, 400, "Name and unit are required")
                return@coroutineHandler
            }
            if (id == 0) {
                rawMaterials.create(name, unitId, 0.0, 0.0)
            } else {
                val existing = rawMaterials.getById(id) ?: run {
                    apiError(ctx, 404, "Raw material not found")
                    return@coroutineHandler
                }
                rawMaterials.update(existing.copy(name = name, unitId = unitId))
            }
            apiJson(ctx, mapOf("ok" to true))
        }
        router.delete("/api/raw-materials/:id").coroutineHandler { ctx ->
            requireApiAuth(ctx, setOf("Admin", "Purchasing"), SessionPermission.DELETE, MODULE_RAW_MATERIALS) ?: return@coroutineHandler
            ctx.pathParam("id")?.toIntOrNull()?.takeIf { it > 0 }?.let { rawMaterials.delete(it) }
            apiJson(ctx, mapOf("ok" to true))
        }

        router.get("/api/products").coroutineHandler { ctx ->
            requireApiAuth(ctx, setOf("Admin", "Sales", "Production")) ?: return@coroutineHandler
            apiJson(ctx, products.listAll())
        }
        router.post("/api/products").coroutineHandler { ctx ->
            requireApiAuth(ctx, setOf("Admin", "Sales", "Production"), SessionPermission.EDIT, MODULE_PRODUCTS) ?: return@coroutineHandler
            val body = jsonBody(ctx)
            val id = body.getInteger("id") ?: 0
            val name = body.getString("name")?.trim().orEmpty()
            val unitId = body.getInteger("unitId")
            val unitCost = body.getDouble("unitCost")
            val amountParam = body.getDouble("amount")
            if (name.isBlank() || unitId == null || (unitCost == null && amountParam == null)) {
                apiError(ctx, 400, "Name, unit, and cost are required")
                return@coroutineHandler
            }
            if (id == 0) {
                products.create(name, unitId, 0.0, amountParam ?: 0.0)
            } else {
                val existing = products.getById(id) ?: run {
                    apiError(ctx, 404, "Product not found")
                    return@coroutineHandler
                }
                val amount = unitCost?.let { existing.quantity * it } ?: amountParam ?: existing.amount
                products.update(existing.copy(name = name, unitId = unitId, amount = amount))
            }
            apiJson(ctx, mapOf("ok" to true))
        }
        router.delete("/api/products/:id").coroutineHandler { ctx ->
            requireApiAuth(ctx, setOf("Admin", "Sales", "Production"), SessionPermission.DELETE, MODULE_PRODUCTS) ?: return@coroutineHandler
            ctx.pathParam("id")?.toIntOrNull()?.takeIf { it > 0 }?.let { products.delete(it) }
            apiJson(ctx, mapOf("ok" to true))
        }

        router.get("/api/ingredients").coroutineHandler { ctx ->
            requireApiAuth(ctx, setOf("Admin", "Production")) ?: return@coroutineHandler
            apiJson(ctx, ingredients.listAll())
        }
        router.post("/api/ingredients").coroutineHandler { ctx ->
            requireApiAuth(ctx, setOf("Admin", "Production"), SessionPermission.EDIT, MODULE_INGREDIENTS) ?: return@coroutineHandler
            val body = jsonBody(ctx)
            val id = body.getInteger("id") ?: 0
            val productId = body.getInteger("productId")
            val rawMaterialId = body.getInteger("rawMaterialId")
            val quantity = body.getDouble("quantity")
            if (productId == null || rawMaterialId == null || quantity == null) {
                apiError(ctx, 400, "Product, raw material, and quantity are required")
                return@coroutineHandler
            }
            if (id == 0) ingredients.create(productId, rawMaterialId, quantity)
            else ingredients.update(Ingredient(id, productId, rawMaterialId, quantity, null, null))
            apiJson(ctx, mapOf("ok" to true))
        }
        router.delete("/api/ingredients/:id").coroutineHandler { ctx ->
            requireApiAuth(ctx, setOf("Admin", "Production"), SessionPermission.DELETE, MODULE_INGREDIENTS) ?: return@coroutineHandler
            ctx.pathParam("id")?.toIntOrNull()?.takeIf { it > 0 }?.let { ingredients.delete(it) }
            apiJson(ctx, mapOf("ok" to true))
        }

        router.get("/api/purchases").coroutineHandler { ctx ->
            requireApiAuth(ctx, setOf("Admin", "Purchasing")) ?: return@coroutineHandler
            apiJson(ctx, purchases.listAll())
        }
        router.post("/api/purchases").coroutineHandler { ctx ->
            requireApiAuth(ctx, setOf("Admin", "Purchasing"), SessionPermission.EDIT, MODULE_PURCHASE) ?: return@coroutineHandler
            val body = jsonBody(ctx)
            val rawMaterialId = body.getInteger("rawMaterialId")
            val employeeId = body.getInteger("employeeId")
            val quantity = body.getDouble("quantity")
            val unitPrice = body.getDouble("unitPrice")
            val purchaseDate = parseDateTime(body.getString("purchaseDate"))
            if (rawMaterialId == null || employeeId == null || quantity == null || unitPrice == null) {
                apiError(ctx, 400, "Raw material, employee, quantity, and unit price are required")
                return@coroutineHandler
            }
            if (quantity <= 0.0 || unitPrice <= 0.0) {
                apiError(ctx, 400, "Quantity and unit price must be greater than 0")
                return@coroutineHandler
            }
            purchases.create(rawMaterialId, quantity, quantity * unitPrice, purchaseDate, employeeId)
            apiJson(ctx, mapOf("ok" to true))
        }
        router.delete("/api/purchases/:id").coroutineHandler { ctx ->
            requireApiAuth(ctx, setOf("Admin", "Purchasing"), SessionPermission.DELETE, MODULE_PURCHASE) ?: return@coroutineHandler
            ctx.pathParam("id")?.toIntOrNull()?.takeIf { it > 0 }?.let { purchases.delete(it) }
            apiJson(ctx, mapOf("ok" to true))
        }
        router.post("/api/purchases/rollback").coroutineHandler { ctx ->
            requireApiAuth(ctx, setOf("Admin", "Purchasing"), SessionPermission.DELETE, MODULE_PURCHASE) ?: return@coroutineHandler
            purchases.deleteLast()
            apiJson(ctx, mapOf("ok" to true))
        }

        router.get("/api/production").coroutineHandler { ctx ->
            requireApiAuth(ctx, setOf("Admin", "Production")) ?: return@coroutineHandler
            apiJson(ctx, production.listAll())
        }
        router.post("/api/production").coroutineHandler { ctx ->
            requireApiAuth(ctx, setOf("Admin", "Production"), SessionPermission.EDIT, MODULE_PRODUCTION) ?: return@coroutineHandler
            val body = jsonBody(ctx)
            val productId = body.getInteger("productId")
            val employeeId = body.getInteger("employeeId")
            val quantity = body.getDouble("quantity")
            val productionDate = parseDateTime(body.getString("productionDate"))
            if (productId == null || employeeId == null || quantity == null) {
                apiError(ctx, 400, "Product, employee, and quantity are required")
                return@coroutineHandler
            }
            production.create(productId, quantity, productionDate, employeeId)
            apiJson(ctx, mapOf("ok" to true))
        }
        router.delete("/api/production/:id").coroutineHandler { ctx ->
            requireApiAuth(ctx, setOf("Admin", "Production"), SessionPermission.DELETE, MODULE_PRODUCTION) ?: return@coroutineHandler
            ctx.pathParam("id")?.toIntOrNull()?.takeIf { it > 0 }?.let { production.delete(it) }
            apiJson(ctx, mapOf("ok" to true))
        }
        router.post("/api/production/rollback").coroutineHandler { ctx ->
            requireApiAuth(ctx, setOf("Admin", "Production"), SessionPermission.DELETE, MODULE_PRODUCTION) ?: return@coroutineHandler
            production.deleteLast()
            apiJson(ctx, mapOf("ok" to true))
        }

        router.get("/api/sales").coroutineHandler { ctx ->
            requireApiAuth(ctx, setOf("Admin", "Sales")) ?: return@coroutineHandler
            apiJson(ctx, productSales.listAll())
        }
        router.post("/api/sales").coroutineHandler { ctx ->
            requireApiAuth(ctx, setOf("Admin", "Sales"), SessionPermission.EDIT, MODULE_SALES) ?: return@coroutineHandler
            val body = jsonBody(ctx)
            val productId = body.getInteger("productId")
            val employeeId = body.getInteger("employeeId")
            val quantity = body.getDouble("quantity")
            val saleDate = parseDateTime(body.getString("saleDate"))
            if (productId == null || employeeId == null || quantity == null) {
                apiError(ctx, 400, "Product, employee, and quantity are required")
                return@coroutineHandler
            }
            if (quantity <= 0.0) {
                apiError(ctx, 400, "Quantity must be greater than 0")
                return@coroutineHandler
            }
            productSales.create(productId, quantity, saleDate, employeeId)
            apiJson(ctx, mapOf("ok" to true))
        }
        router.delete("/api/sales/:id").coroutineHandler { ctx ->
            requireApiAuth(ctx, setOf("Admin", "Sales"), SessionPermission.DELETE, MODULE_SALES) ?: return@coroutineHandler
            ctx.pathParam("id")?.toIntOrNull()?.takeIf { it > 0 }?.let { productSales.delete(it) }
            apiJson(ctx, mapOf("ok" to true))
        }
        router.post("/api/sales/rollback").coroutineHandler { ctx ->
            requireApiAuth(ctx, setOf("Admin", "Sales"), SessionPermission.DELETE, MODULE_SALES) ?: return@coroutineHandler
            productSales.deleteLast()
            apiJson(ctx, mapOf("ok" to true))
        }

        router.get("/api/salaries").coroutineHandler { ctx ->
            requireApiAuth(ctx, null) ?: return@coroutineHandler
            apiJson(ctx, salaries.listAll())
        }
        router.post("/api/salaries").coroutineHandler { ctx ->
            requireApiAuth(ctx, null, SessionPermission.EDIT, MODULE_SALARY) ?: return@coroutineHandler
            val body = jsonBody(ctx)
            val employeeId = body.getInteger("employeeId")
            val amountParam = body.getDouble("amount")
            val paymentDate = parseDateTime(body.getString("paymentDate"))
            val note = body.getString("note")?.trim()?.takeIf { it.isNotBlank() }
            if (employeeId == null) {
                apiError(ctx, 400, "Employee is required")
                return@coroutineHandler
            }
            salaries.create(employeeId, amountParam, paymentDate, note)
            apiJson(ctx, mapOf("ok" to true))
        }
        router.delete("/api/salaries/:id").coroutineHandler { ctx ->
            requireApiAuth(ctx, null, SessionPermission.DELETE, MODULE_SALARY) ?: return@coroutineHandler
            ctx.pathParam("id")?.toIntOrNull()?.takeIf { it > 0 }?.let { salaries.delete(it) }
            apiJson(ctx, mapOf("ok" to true))
        }
        router.post("/api/salaries/rollback").coroutineHandler { ctx ->
            requireApiAuth(ctx, null, SessionPermission.DELETE, MODULE_SALARY) ?: return@coroutineHandler
            salaries.deleteLast()
            apiJson(ctx, mapOf("ok" to true))
        }

        router.get("/api/reports").coroutineHandler { ctx ->
            requireApiAuth(ctx, setOf("Admin", "Sales")) ?: return@coroutineHandler
            apiJson(
                ctx,
                mapOf(
                    "rawInventory" to rawInventory.listAll(),
                    "productInventory" to productInventory.listAll(),
                    "sales" to salesExtended.listAll()
                )
            )
        }

        router.get("/api/budget").coroutineHandler { ctx ->
            requireApiAuth(ctx, setOf("Admin")) ?: return@coroutineHandler
            apiJson(ctx, budgets.listAll())
        }
        router.post("/api/budget").coroutineHandler { ctx ->
            requireApiAuth(ctx, setOf("Admin"), SessionPermission.EDIT, MODULE_BUDGET) ?: return@coroutineHandler
            val amount = jsonBody(ctx).getDouble("amount")
            if (amount == null) {
                apiError(ctx, 400, "Amount is required")
                return@coroutineHandler
            }
            budgets.create(amount)
            apiJson(ctx, mapOf("ok" to true))
        }

        router.get("/api/users").coroutineHandler { ctx ->
            requireApiAuth(ctx, setOf("Admin")) ?: return@coroutineHandler
            apiJson(
                ctx,
                mapOf(
                    "users" to authRepo.getUsersWithRoles(),
                    "roles" to authRepo.getRoles(),
                    "employees" to employees.listAll().map { mapOf("id" to it.id, "name" to it.fullName) }
                )
            )
        }
        router.post("/api/users").coroutineHandler { ctx ->
            requireApiAuth(ctx, setOf("Admin"), SessionPermission.EDIT, MODULE_USERS) ?: return@coroutineHandler
            val body = jsonBody(ctx)
            val id = body.getInteger("id") ?: 0
            val username = body.getString("username")?.trim().orEmpty()
            val password = body.getString("password")?.trim().orEmpty()
            val isActive = body.getBoolean("isActive") ?: false
            val employeeId = body.getInteger("employeeId")
            val roles = authRepo.getRoles()
            val selectedRoleIds = body.getJsonArray("roleIds")?.mapNotNull { (it as? Number)?.toInt() } ?: emptyList()
            if (username.isBlank()) {
                apiError(ctx, 400, "Username is required")
                return@coroutineHandler
            }
            if (id == 0) {
                if (password.isBlank()) {
                    apiError(ctx, 400, "Password is required for new user")
                    return@coroutineHandler
                }
                val (hash, salt) = authService.createPasswordHash(password)
                authRepo.saveUserWithRoles(0, username, isActive, hash, salt, selectedRoleIds.filter { roleId -> roles.any { it.id == roleId } }, employeeId)
            } else {
                val hashAndSalt = if (password.isBlank()) null else authService.createPasswordHash(password)
                authRepo.saveUserWithRoles(
                    id,
                    username,
                    isActive,
                    hashAndSalt?.first,
                    hashAndSalt?.second,
                    selectedRoleIds.filter { roleId -> roles.any { it.id == roleId } },
                    employeeId
                )
            }
            apiJson(ctx, mapOf("ok" to true))
        }
        router.post("/api/roles/permissions").coroutineHandler { ctx ->
            requireApiAuth(ctx, setOf("Admin"), SessionPermission.EDIT, MODULE_USERS) ?: return@coroutineHandler
            val body = jsonBody(ctx)
            val roleId = body.getInteger("roleId") ?: 0
            if (roleId <= 0) {
                apiError(ctx, 400, "Role is required")
                return@coroutineHandler
            }
            val permissions = body.getJsonArray("permissions") ?: JsonArray()
            if (permissions.isEmpty) {
                MODULE_PERMISSION_LABELS.keys.forEach { moduleKey ->
                    authRepo.saveRolePermission(roleId, moduleKey, false, false)
                }
            } else {
                permissions.forEach { item ->
                    val json = item as? JsonObject ?: return@forEach
                    val moduleKey = json.getString("moduleKey")?.trim().orEmpty()
                    if (!MODULE_PERMISSION_LABELS.containsKey(moduleKey)) return@forEach
                    authRepo.saveRolePermission(
                        roleId = roleId,
                        moduleKey = moduleKey,
                        canEdit = json.getBoolean("canEdit") ?: false,
                        canDelete = json.getBoolean("canDelete") ?: false
                    )
                }
            }
            apiJson(ctx, mapOf("ok" to true))
        }
        router.delete("/api/users/:id").coroutineHandler { ctx ->
            requireApiAuth(ctx, setOf("Admin"), SessionPermission.DELETE, MODULE_USERS) ?: return@coroutineHandler
            ctx.pathParam("id")?.toIntOrNull()?.takeIf { it > 0 }?.let { authRepo.deleteUser(it) }
            apiJson(ctx, mapOf("ok" to true))
        }
    }

    private fun requireAuth(
        ctx: RoutingContext,
        roles: Set<String>?,
        permission: SessionPermission? = null,
        moduleKey: String? = null
    ): AuthSession? {
        val session = readSid(ctx)?.let { sessions[it] }
        if (session == null) {
            ctx.response().setStatusCode(303).putHeader("Location", "/login").end()
            return null
        }
        if (roles != null && roles.isNotEmpty()) {
            val ok = session.roles.any { role -> roles.any { it.equals(role, ignoreCase = true) } }
            if (!ok) {
                redirectWithAlert(ctx, "/", "Access denied")
                return null
            }
        }
        if (permission != null && !hasPermission(session, moduleKey, permission)) {
            redirectWithAlert(ctx, "/", "Permission denied")
            return null
        }
        return session
    }

    private fun requireApiAuth(
        ctx: RoutingContext,
        roles: Set<String>?,
        permission: SessionPermission? = null,
        moduleKey: String? = null
    ): AuthSession? {
        val session = readSid(ctx)?.let { sessions[it] }
        if (session == null) {
            apiError(ctx, 401, "Unauthorized")
            return null
        }
        if (roles != null && roles.isNotEmpty()) {
            val ok = session.roles.any { role -> roles.any { it.equals(role, ignoreCase = true) } }
            if (!ok) {
                apiError(ctx, 403, "Access denied")
                return null
            }
        }
        if (permission != null && !hasPermission(session, moduleKey, permission)) {
            apiError(ctx, 403, "Permission denied")
            return null
        }
        return session
    }

    private fun hasPermission(session: AuthSession, moduleKey: String?, permission: SessionPermission): Boolean {
        if (moduleKey.isNullOrBlank()) {
            return when (permission) {
                SessionPermission.EDIT -> session.canEdit
                SessionPermission.DELETE -> session.canDelete
            }
        }
        val modulePermission = session.modulePermissions[moduleKey] ?: return false
        return when (permission) {
            SessionPermission.EDIT -> modulePermission.canEdit
            SessionPermission.DELETE -> modulePermission.canDelete
        }
    }

    private fun redirect(ctx: RoutingContext, path: String) {
        ctx.response().setStatusCode(303).putHeader("Location", path).end()
    }

    private fun badRequest(ctx: RoutingContext, message: String) {
        redirectBackWithAlert(ctx, message)
    }

    private fun redirectWithAlert(ctx: RoutingContext, path: String, message: String) {
        val separator = if (path.contains("?")) "&" else "?"
        val encoded = URLEncoder.encode(message, StandardCharsets.UTF_8)
        redirect(ctx, "$path${separator}error=$encoded")
    }

    private fun redirectBackWithAlert(ctx: RoutingContext, message: String) {
        val referer = ctx.request().getHeader("Referer")
        val target = runCatching {
            if (referer.isNullOrBlank()) null else URI(referer).path
        }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: ctx.normalizedPath()
        redirectWithAlert(ctx, target, message)
    }

    private fun presentableErrorMessage(ex: Throwable, path: String = ""): String {
        val raw = ex.message?.trim().orEmpty()
        val lowered = raw.lowercase()
        if ("delete" in lowered && ("reference" in lowered || "fk_" in lowered || "constraint" in lowered)) {
            return when {
                path.contains("/units") -> "Cannot delete this unit because it is used in raw materials or products."
                path.contains("/positions") -> "Cannot delete this position because it is assigned to employees."
                path.contains("/employees") -> "Cannot delete this employee because it is used in salaries, purchases, production or sales."
                path.contains("/raw-materials") -> "Cannot delete this raw material because it is used in ingredients or purchases."
                path.contains("/products") -> "Cannot delete this product because it is used in ingredients, production or sales."
                path.contains("/ingredients") -> "Cannot delete this ingredient because it is linked to a product recipe."
                path.contains("/purchase") -> "Cannot delete this purchase because it is linked to inventory history."
                path.contains("/production") -> "Cannot delete this production record because it is linked to inventory history."
                path.contains("/sales") -> "Cannot delete this sale because it is linked to inventory or finance history."
                path.contains("/admin/users") -> "Cannot delete this user because it is still linked to roles or audit data."
                else -> "Cannot delete this record because it is used in other data."
            }
        }
        if ("reference" in lowered || "fk_" in lowered) {
            return "Operation failed because this record is linked to other data."
        }
        return if (raw.isBlank()) "Server error" else raw
    }

    private fun readSid(ctx: RoutingContext): String? {
        val cookieHeader = ctx.request().getHeader("Cookie") ?: return null
        val cookies = cookieHeader.split(";")
        for (cookie in cookies) {
            val parts = cookie.trim().split("=", limit = 2)
            if (parts.size == 2 && parts[0] == "SID") {
                return parts[1]
            }
        }
        return null
    }

    private fun setSessionCookie(ctx: RoutingContext, sid: String) {
        ctx.response().putHeader("Set-Cookie", "SID=$sid; HttpOnly; Path=/")
    }

    private fun clearSessionCookie(ctx: RoutingContext) {
        ctx.response().putHeader("Set-Cookie", "SID=; Max-Age=0; Path=/")
    }

    private fun apiJson(ctx: RoutingContext, payload: Any, statusCode: Int = 200) {
        ctx.response()
            .setStatusCode(statusCode)
            .putHeader("Content-Type", "application/json; charset=utf-8")
            .end(Json.encode(payload))
    }

    private fun apiError(ctx: RoutingContext, statusCode: Int, message: String) {
        apiJson(ctx, mapOf("error" to message), statusCode)
    }

    private inline fun <T> safeLookup(block: () -> List<T>): List<T> =
        runCatching(block).getOrElse { emptyList() }

    private fun jsonBody(ctx: RoutingContext): JsonObject =
        runCatching { ctx.body().asJsonObject() }.getOrElse { JsonObject() } ?: JsonObject()

    private fun serveStaticFile(ctx: RoutingContext, path: Path) {
        val normalized = path.normalize()
        val frontendRoot = Paths.get("frontend").normalize()
        if (!normalized.startsWith(frontendRoot) || !Files.exists(normalized) || Files.isDirectory(normalized)) {
            ctx.response().setStatusCode(404).end()
            return
        }

        val contentType = when (normalized.fileName.toString().substringAfterLast('.', "").lowercase()) {
            "css" -> "text/css; charset=utf-8"
            "js" -> "application/javascript; charset=utf-8"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "svg" -> "image/svg+xml"
            else -> "application/octet-stream"
        }

        ctx.response()
            .putHeader("Content-Type", contentType)
            .sendFile(normalized.toString())
    }

    private fun navigationItems(session: AuthSession): List<Map<String, String>> {
        val items = mutableListOf(
            mapOf("to" to "/", "label" to "Dashboard", "group" to "Workspace")
        )
        if (hasRole(session, "Admin") || hasRole(session, "Purchasing")) {
            items.add(mapOf("to" to "/purchase", "label" to "Purchase", "group" to "Operations"))
            items.add(mapOf("to" to "/units", "label" to "Units", "group" to "Directories"))
            items.add(mapOf("to" to "/raw-materials", "label" to "Raw materials", "group" to "Directories"))
        }
        if (hasRole(session, "Admin")) {
            items.add(mapOf("to" to "/positions", "label" to "Positions", "group" to "Directories"))
            items.add(mapOf("to" to "/employees", "label" to "Employees", "group" to "Directories"))
        }
        items.add(mapOf("to" to "/salary", "label" to "Salaries", "group" to "Finance"))
        if (hasRole(session, "Admin") || hasRole(session, "Sales") || hasRole(session, "Production")) {
            items.add(mapOf("to" to "/products", "label" to "Products", "group" to "Recipes and stock"))
        }
        if (hasRole(session, "Admin") || hasRole(session, "Production")) {
            items.add(mapOf("to" to "/ingredients", "label" to "Ingredients list", "group" to "Recipes and stock"))
            items.add(mapOf("to" to "/production", "label" to "Production", "group" to "Operations"))
        }
        if (hasRole(session, "Admin") || hasRole(session, "Sales")) {
            items.add(mapOf("to" to "/sales", "label" to "Sales", "group" to "Operations"))
            items.add(mapOf("to" to "/reports", "label" to "Reports", "group" to "Analytics"))
        }
        if (hasRole(session, "Admin")) {
            items.add(mapOf("to" to "/budget", "label" to "Budget", "group" to "Finance"))
            items.add(mapOf("to" to "/admin/users", "label" to "Users", "group" to "Administration"))
        }
        return items
    }

    private fun loginPage(error: String?): String {
        val errorBlock = if (error.isNullOrBlank()) "" else "<div class=\"alert alert-error\">${html(error)}</div>"
        return templates.render(
            "login.html",
            mapOf("error" to errorBlock)
        )
    }

    private suspend fun layoutPage(title: String, session: AuthSession, content: String): String {
        val budget = budgets.listAll().firstOrNull()?.budgetAmount
        val budgetLabel = budget?.toString() ?: "-"
        val creditFunds = credits.listAll().filter { it.isActive }.sumOf { it.remainingAmount }
        val creditLabel = if (creditFunds > 0.0) {
            "<span class=\"budget-credit-note\">Credit (${format2(creditFunds)})</span>"
        } else {
            ""
        }
        val nav = buildNav(session)
        return templates.render(
            "layout.html",
            mapOf(
                "title" to html(title),
                "nav" to nav,
                "username" to html(session.username),
                "roles" to html(session.roles.sorted().joinToString(", ")),
                "budget" to budgetLabel,
                "budgetCredit" to creditLabel,
                "content" to content
            )
        )
    }

    private fun buildNav(session: AuthSession): String {
        val grouped = linkedMapOf<String, MutableList<Map<String, String>>>()
        for (item in navigationItems(session)) {
            grouped.getOrPut(item.getValue("group")) { mutableListOf() }.add(item)
        }

        return grouped.entries.joinToString("\n") { (group, items) ->
            val links = items.joinToString("\n") { item ->
                "<li><a href=\"${item.getValue("to")}\">${html(item.getValue("label"))}</a></li>"
            }
            "<div class=\"nav-group\"><div class=\"nav-group-title\">${html(group)}</div><ul>$links</ul></div>"
        }
    }

    private fun hasRole(session: AuthSession, role: String): Boolean =
        session.roles.any { it.equals(role, ignoreCase = true) }

    private fun canEdit(session: AuthSession, moduleKey: String): Boolean =
        session.modulePermissions[moduleKey]?.canEdit == true

    private fun canDelete(session: AuthSession, moduleKey: String): Boolean =
        session.modulePermissions[moduleKey]?.canDelete == true

    private fun permissionNotice(message: String): String =
        "<section class=\"section-card permission-note\"><p>${html(message)}</p></section>"

    private fun editFormOrNotice(session: AuthSession, moduleKey: String, content: String, message: String): String =
        if (canEdit(session, moduleKey)) content else permissionNotice(message)

    private fun cancelEditLink(path: String, isEditing: Boolean): String =
        if (isEditing) "<a href=\"$path\" class=\"secondary-link\">Cancel</a>" else ""

    private fun rowActions(
        session: AuthSession,
        moduleKey: String,
        editHref: String? = null,
        deleteAction: String? = null,
        id: Number? = null,
        deleteLabel: String = "Delete"
    ): String {
        val parts = mutableListOf<String>()
        if (editHref != null && canEdit(session, moduleKey)) {
            parts += "<a href=\"$editHref\" class=\"action-link action-link-edit\">Edit</a>"
        }
        if (deleteAction != null && id != null && canDelete(session, moduleKey)) {
            parts += "<form method=\"post\" action=\"$deleteAction\" class=\"action-form\">" +
                "<input type=\"hidden\" name=\"id\" value=\"$id\"/>" +
                "<button type=\"submit\" class=\"action-link action-link-delete\">${html(deleteLabel)}</button></form>"
        }
        return if (parts.isEmpty()) "" else "<div class=\"action-row\">${parts.joinToString("")}</div>"
    }

    private suspend fun dashboardPage(session: AuthSession): String {
        val navGroups = navigationItems(session)
            .groupBy { it.getValue("group") }
            .entries
            .joinToString("\n") { (group, items) ->
                val links = items.joinToString("\n") { item ->
                    "<li><a href=\"${item.getValue("to")}\">${html(item.getValue("label"))}</a></li>"
                }
                """
                <section class="section-card dashboard-section">
                    <h2>${html(group)}</h2>
                    <ul class="dashboard-link-list">
                        $links
                    </ul>
                </section>
                """.trimIndent()
            }
        val content = """
            <h1>Dashboard</h1>
            <div class="dashboard-sections">$navGroups</div>
        """.trimIndent()
        return layoutPage("Dashboard", session, content)
    }

    private suspend fun unitsPage(
        session: AuthSession,
        list: List<MeasurementUnit>,
        edit: MeasurementUnit?
    ): String {
        val rows = list.joinToString("\n") { u ->
            "<tr><td>${u.id}</td><td>${html(u.name)}</td><td>${rowActions(session, MODULE_UNITS, "/units?editId=${u.id}", "/units/delete", u.id)}</td></tr>"
        }
        val formSection = editFormOrNotice(
            session,
            MODULE_UNITS,
            """
            <section class="section-card compact-card">
                <h2>Measurement unit</h2>
                <form method="post" action="/units/save" class="managed-form compact-form">
                    <input type="hidden" name="id" value="${edit?.id ?: 0}" />
                    <div>
                        <label>Name</label>
                        <input name="name" value="${html(edit?.name ?: "")}" />
                    </div>
                    <div class="form-actions">
                        <button type="submit">Save unit</button>
                        ${cancelEditLink("/units", edit != null)}
                    </div>
                </form>
            </section>
            """.trimIndent(),
            "Editing measurement units is not allowed for your roles."
        )
        val content = templates.render(
            "pages/units.html",
            mapOf(
                "formSection" to formSection,
                "rows" to rows
            )
        )
        return layoutPage("Units", session, content)
    }

    private suspend fun positionsPage(
        session: AuthSession,
        list: List<Position>,
        edit: Position?
    ): String {
        val rows = list.joinToString("\n") { p ->
            "<tr><td>${p.id}</td><td>${html(p.title)}</td><td>${rowActions(session, MODULE_POSITIONS, "/positions?editId=${p.id}", "/positions/delete", p.id)}</td></tr>"
        }
        val formSection = editFormOrNotice(
            session,
            MODULE_POSITIONS,
            """
            <section class="section-card compact-card">
                <h2>Position</h2>
                <form method="post" action="/positions/save" class="managed-form compact-form">
                    <input type="hidden" name="id" value="${edit?.id ?: 0}" />
                    <div>
                        <label>Title</label>
                        <input name="title" value="${html(edit?.title ?: "")}" />
                    </div>
                    <div class="form-actions">
                        <button type="submit">Save position</button>
                        ${cancelEditLink("/positions", edit != null)}
                    </div>
                </form>
            </section>
            """.trimIndent(),
            "Editing positions is not allowed for your roles."
        )
        val content = templates.render(
            "pages/positions.html",
            mapOf(
                "formSection" to formSection,
                "rows" to rows
            )
        )
        return layoutPage("Positions", session, content)
    }

    private suspend fun employeesPage(
        session: AuthSession,
        list: List<Employee>,
        positionsList: List<Position>,
        edit: Employee?
    ): String {
        val rows = list.joinToString("\n") { e ->
            "<tr><td>${e.id}</td><td>${html(e.fullName)}</td><td>${e.positionTitle ?: e.positionId}</td>" +
                "<td>${e.dateOfBirth ?: ""}</td><td>${e.salary}</td><td>${html(e.homeAddress ?: "")}</td><td>" +
                "${rowActions(session, MODULE_EMPLOYEES, "/employees?editId=${e.id}", "/employees/delete", e.id)}</td></tr>"
        }
        val positionOptions = positionsList.joinToString("\n") { p ->
            val selected = if (edit?.positionId == p.id) "selected" else ""
            "<option value=\"${p.id}\" $selected>${html(p.title)}</option>"
        }
        val formSection = editFormOrNotice(
            session,
            MODULE_EMPLOYEES,
            """
            <section class="section-card">
                <h2>Employee card</h2>
                <form method="post" action="/employees/save" class="managed-form">
                    <input type="hidden" name="id" value="${edit?.id ?: 0}" />
                    <div>
                        <label>Full name</label>
                        <input name="fullName" value="${html(edit?.fullName ?: "")}" />
                    </div>
                    <div>
                        <label>Position</label>
                        <select name="positionId">$positionOptions</select>
                    </div>
                    <div>
                        <label>Date of birth</label>
                        <input type="date" name="dateOfBirth" value="${edit?.dateOfBirth?.toLocalDate()?.toString() ?: ""}" />
                    </div>
                    <div>
                        <label>Salary</label>
                        <input name="salary" value="${edit?.salary?.toPlainString() ?: ""}" />
                    </div>
                    <div>
                        <label>Home address</label>
                        <input name="homeAddress" value="${html(edit?.homeAddress ?: "")}" />
                    </div>
                    <div class="form-actions">
                        <button type="submit">Save employee</button>
                        ${cancelEditLink("/employees", edit != null)}
                    </div>
                </form>
            </section>
            """.trimIndent(),
            "Editing employees is not allowed for your roles."
        )
        val content = templates.render(
            "pages/employees.html",
            mapOf(
                "formSection" to formSection,
                "rows" to rows
            )
        )
        return layoutPage("Employees", session, content)
    }

    private suspend fun salaryPage(
        session: AuthSession,
        list: List<SalaryPayment>,
        employeesList: List<Employee>
    ): String {
        val rows = list.joinToString("\n") { s ->
            "<tr><td>${s.id}</td><td>${html(s.employeeName ?: "")}</td>" +
                "<td>${s.amount}</td><td>${s.paymentDate}</td><td>${html(s.note ?: "")}</td><td>" +
                "${rowActions(session, MODULE_SALARY, deleteAction = "/salary/delete", id = s.id)}</td></tr>"
        }
        val employeeOptions = employeesList.joinToString("\n") { e ->
            "<option value=\"${e.id}\">${html(e.fullName)}</option>"
        }
        val formSection = if (canEdit(session, MODULE_SALARY) || canDelete(session, MODULE_SALARY)) {
            buildString {
                if (canEdit(session, MODULE_SALARY)) {
                    append(
                        """
                        <section class="section-card">
                            <h2>Salary payment</h2>
                            <form method="post" action="/salary/save" class="managed-form">
                                <div>
                                    <label>Employee</label>
                                    <select name="employeeId">$employeeOptions</select>
                                </div>
                                <div>
                                    <label>Amount</label>
                                    <input name="amount" placeholder="Leave empty to use employee salary" />
                                </div>
                                <div>
                                    <label>Payment date</label>
                                    <input type="date" name="paymentDate" />
                                </div>
                                <div>
                                    <label>Note</label>
                                    <input name="note" />
                                </div>
                                <button type="submit">Post salary payment</button>
                            </form>
                        """.trimIndent()
                    )
                } else {
                    append(permissionNotice("Creating salary payments is not allowed for your roles."))
                }
                if (canDelete(session, MODULE_SALARY)) {
                    append(
                        """
                            <form method="post" action="/salary/rollback" class="secondary-actions">
                                <button type="submit">Rollback last payment</button>
                            </form>
                        </section>
                        """.trimIndent()
                    )
                } else if (canEdit(session, MODULE_SALARY)) {
                    append("</section>")
                }
            }
        } else {
            permissionNotice("Changing salary records is not allowed for your roles.")
        }
        val content = templates.render(
            "pages/salary.html",
            mapOf(
                "formSection" to formSection,
                "rows" to rows
            )
        )
        return layoutPage("Salaries", session, content)
    }

    private suspend fun rawMaterialsPage(
        session: AuthSession,
        list: List<RawMaterial>,
        unitsList: List<MeasurementUnit>,
        edit: RawMaterial?
    ): String {
        val rows = list.joinToString("\n") { r ->
            "<tr><td>${r.id}</td><td>${html(r.name)}</td><td>${r.unitName ?: r.unitId}</td>" +
                "<td>${format3(r.quantity)}</td><td>${format2(r.amount)}</td><td>" +
                "${rowActions(session, MODULE_RAW_MATERIALS, "/raw-materials?editId=${r.id}", "/raw-materials/delete", r.id)}</td></tr>"
        }
        val unitOptions = unitsList.joinToString("\n") { u ->
            val selected = if (edit?.unitId == u.id) "selected" else ""
            "<option value=\"${u.id}\" $selected>${html(u.name)}</option>"
        }
        val formSection = editFormOrNotice(
            session,
            MODULE_RAW_MATERIALS,
            """
            <section class="section-card">
                <h2>Material card</h2>
                <form method="post" action="/raw-materials/save" class="managed-form">
                    <input type="hidden" name="id" value="${edit?.id ?: 0}" />
                    <div>
                        <label>Name</label>
                        <input name="name" value="${html(edit?.name ?: "")}" />
                    </div>
                    <div>
                        <label>Unit</label>
                        <select name="unitId">$unitOptions</select>
                    </div>
                    <div>
                        <label>Quantity in stock</label>
                        <input name="quantity" value="${format3(edit?.quantity ?: 0.0)}" readonly />
                        <small class="field-hint">Readonly. Changes only through purchase documents.</small>
                    </div>
                    <div>
                        <label>Total amount</label>
                        <input name="amount" value="${format2(edit?.amount ?: 0.0)}" readonly />
                        <small class="field-hint">Readonly. Recalculated from purchase operations.</small>
                    </div>
                    <div class="form-actions">
                        <button type="submit">Save material</button>
                        ${cancelEditLink("/raw-materials", edit != null)}
                    </div>
                </form>
            </section>
            """.trimIndent(),
            "Editing raw materials is not allowed for your roles."
        )
        val content = templates.render(
            "pages/raw-materials.html",
            mapOf(
                "formSection" to formSection,
                "rows" to rows
            )
        )
        return layoutPage("Raw materials", session, content)
    }

    private suspend fun productsPage(
        session: AuthSession,
        list: List<FinishedProduct>,
        unitsList: List<MeasurementUnit>,
        edit: FinishedProduct?
    ): String {
        val rows = list.joinToString("\n") { p ->
            "<tr><td>${p.id}</td><td>${html(p.name)}</td><td>${p.unitName ?: p.unitId}</td>" +
                "<td>${format3(p.quantity)}</td><td>${format2(unitCost(p.amount, p.quantity))}</td><td>" +
                "${rowActions(session, MODULE_PRODUCTS, "/products?editId=${p.id}", "/products/delete", p.id)}</td></tr>"
        }
        val unitOptions = unitsList.joinToString("\n") { u ->
            val selected = if (edit?.unitId == u.id) "selected" else ""
            "<option value=\"${u.id}\" $selected>${html(u.name)}</option>"
        }
        val formSection = editFormOrNotice(
            session,
            MODULE_PRODUCTS,
            """
            <section class="section-card">
                <h2>Product card</h2>
                <form method="post" action="/products/save" class="managed-form" id="products-form">
                    <input type="hidden" name="id" value="${edit?.id ?: 0}" />
                    <div>
                        <label>Name</label>
                        <input name="name" value="${html(edit?.name ?: "")}" />
                    </div>
                    <div>
                        <label>Unit</label>
                        <select name="unitId">$unitOptions</select>
                    </div>
                    <div>
                        <label>Quantity in stock</label>
                        <input id="product-quantity" name="quantity" value="${format3(edit?.quantity ?: 0.0)}" readonly />
                        <small class="field-hint">Readonly. Updated by production and sales.</small>
                    </div>
                    <div>
                        <label>Cost</label>
                        <input id="product-unit-cost" name="unitCost" value="${format2(unitCost(edit?.amount ?: 0.0, edit?.quantity ?: 0.0))}" />
                        <small class="field-hint">Enter the product cost price. Sale price is suggested automatically.</small>
                    </div>
                    <div>
                        <label>Sale price (+15%)</label>
                        <input id="product-sale-price" value="${format2(calculateSaleUnitPrice(edit?.amount ?: 0.0, edit?.quantity ?: 0.0))}" readonly />
                        <small class="field-hint">Reference only. Used as a visual recommendation.</small>
                    </div>
                    <input id="product-amount" name="amount" type="hidden" value="${format2(edit?.amount ?: 0.0)}" />
                    <div class="form-actions">
                        <button type="submit">Save product</button>
                        ${cancelEditLink("/products", edit != null)}
                    </div>
                </form>
            </section>
            """.trimIndent(),
            "Editing products is not allowed for your roles."
        )
        val content = templates.render(
            "pages/products.html",
            mapOf(
                "formSection" to formSection,
                "rows" to rows
            )
        )
        return layoutPage("Products", session, content)
    }

    private suspend fun ingredientsPage(
        session: AuthSession,
        list: List<Ingredient>,
        productsList: List<FinishedProduct>,
        rawList: List<RawMaterial>,
        edit: Ingredient?
    ): String {
        val groupedIngredients = if (list.isEmpty()) {
            """
            <section class="section-card ingredient-group-card">
                <div class="empty-state">No ingredients added yet.</div>
            </section>
            """.trimIndent()
        } else {
            list.groupBy { it.productName ?: it.productId.toString() }
                .entries
                .sortedBy { it.key.lowercase() }
                .joinToString("\n") { (productName, ingredients) ->
                    val ingredientItems = ingredients
                        .sortedBy { (it.rawMaterialName ?: it.rawMaterialId.toString()).lowercase() }
                        .joinToString("\n") { ingredient ->
                            val editingClass = if (edit?.id == ingredient.id) " is-editing-ingredient" else ""
                            """
                            <div class="ingredient-item$editingClass">
                                <div class="ingredient-item-main">
                                    <div class="ingredient-raw-name">${html(ingredient.rawMaterialName ?: ingredient.rawMaterialId.toString())}</div>
                                    <div class="ingredient-meta">
                                        <span>ID ${ingredient.id}</span>
                                        <span>${format3(ingredient.quantity)} per unit</span>
                                    </div>
                                </div>
                                <div class="ingredient-item-actions">
                                    ${rowActions(session, MODULE_INGREDIENTS, "/ingredients?editId=${ingredient.id}", "/ingredients/delete", ingredient.id)}
                                </div>
                            </div>
                            """.trimIndent()
                        }
                    """
                    <section class="section-card ingredient-group-card">
                        <div class="ingredient-group-head">
                            <h3>${html(productName)}</h3>
                            <span class="ingredient-count">${ingredients.size} item${if (ingredients.size == 1) "" else "s"}</span>
                        </div>
                        <div class="ingredient-group-list">
                            $ingredientItems
                        </div>
                    </section>
                    """.trimIndent()
                }
        }
        val tableRows = if (list.isEmpty()) {
            """
            <tr>
                <td colspan="5" class="empty-state-cell">No ingredients added yet.</td>
            </tr>
            """.trimIndent()
        } else {
            list.joinToString("\n") { i ->
                val editingClass = if (edit?.id == i.id) " class=\"is-editing-row\"" else ""
                "<tr$editingClass><td>${i.id}</td><td>${html(i.productName ?: i.productId.toString())}</td><td>${html(i.rawMaterialName ?: i.rawMaterialId.toString())}</td>" +
                    "<td>${format3(i.quantity)}</td><td>" +
                    "${rowActions(session, MODULE_INGREDIENTS, "/ingredients?editId=${i.id}", "/ingredients/delete", i.id)}</td></tr>"
            }
        }
        val productOptions = productsList.joinToString("\n") { p ->
            val selected = if (edit?.productId == p.id) "selected" else ""
            "<option value=\"${p.id}\" $selected>${html(p.name)}</option>"
        }
        val rawOptions = rawList.joinToString("\n") { r ->
            val selected = if (edit?.rawMaterialId == r.id) "selected" else ""
            "<option value=\"${r.id}\" $selected>${html(r.name)}</option>"
        }
        val formTitle = if (edit == null) "Add ingredient" else "Edit ingredient #${edit.id}"
        val formActionLabel = if (edit == null) "Save ingredient" else "Update ingredient"
        val cancelAction = cancelEditLink("/ingredients", edit != null)
        val formSection = editFormOrNotice(
            session,
            MODULE_INGREDIENTS,
            """
            <section class="section-card">
                <h2>$formTitle</h2>
                <form method="post" action="/ingredients/save" class="managed-form">
                    <input type="hidden" name="id" value="${edit?.id ?: 0}" />
                    <div>
                        <label>Product</label>
                        <select name="productId">$productOptions</select>
                        <small class="field-hint">Choose the finished product whose recipe you are editing.</small>
                    </div>
                    <div>
                        <label>Raw material</label>
                        <select name="rawMaterialId">$rawOptions</select>
                        <small class="field-hint">Choose the material that will be consumed in this recipe.</small>
                    </div>
                    <div>
                        <label>Quantity per unit</label>
                        <input name="quantity" type="number" step="0.001" min="0" value="${edit?.quantity?.toString() ?: ""}" />
                        <small class="field-hint">How much material is needed to produce one unit of product.</small>
                    </div>
                    <div class="form-actions">
                        <button type="submit">$formActionLabel</button>
                        $cancelAction
                    </div>
                </form>
            </section>
            """.trimIndent(),
            "Editing ingredients is not allowed for your roles."
        )
        val content = templates.render(
            "pages/ingredients.html",
            mapOf(
                "formSection" to formSection,
                "rows" to tableRows,
                "groupedIngredients" to groupedIngredients
            )
        )
        return layoutPage("Ingredients", session, content)
    }

    private suspend fun purchasePage(
        session: AuthSession,
        list: List<PurchaseRawMaterial>,
        rawList: List<RawMaterial>,
        employeesList: List<Employee>,
        message: String?
    ): String {
        val rawOptions = rawList.joinToString("\n") { r ->
            "<option value=\"${r.id}\">${html(r.name)}</option>"
        }
        val employeeOptions = employeesList.joinToString("\n") { e ->
            "<option value=\"${e.id}\">${html(e.fullName)}</option>"
        }
        val rows = list.joinToString("\n") { p ->
            val statusBadge = "<span class=\"record-status is-archived\">Archived</span>"
            "<tr><td>${p.purchaseDate.toLocalDate()}</td><td>${p.rawMaterialName ?: p.rawMaterialId}</td>" +
                "<td>${format3(p.quantity)}</td><td>${format2(p.amount)}</td><td>${p.employeeName ?: p.employeeId}</td><td>$statusBadge</td><td>" +
                "${rowActions(session, MODULE_PURCHASE, deleteAction = "/purchase/delete", id = p.id)}</td></tr>"
        }
        val messageBlock = if (message.isNullOrBlank()) "" else "<div class=\"alert alert-info\">${html(message)}</div>"
        val formSection = buildString {
            if (canEdit(session, MODULE_PURCHASE)) {
                append(
                    """
                    <section class="section-card">
                        <h2>New purchase</h2>
                        <form method="post" action="/purchase/save" class="managed-form" id="purchase-form">
                            <div>
                                <label>Raw material</label>
                                <select name="rawMaterialId">$rawOptions</select>
                            </div>
                            <div>
                                <label>Employee</label>
                                <select name="employeeId">$employeeOptions</select>
                            </div>
                            <div>
                                <label>Date</label>
                                <input type="date" name="purchaseDate" />
                            </div>
                            <div>
                                <label>Quantity</label>
                                <input id="purchase-quantity" name="quantity" />
                            </div>
                            <div>
                                <label>Unit price</label>
                                <input id="purchase-unit-price" name="unitPrice" />
                            </div>
                            <div>
                                <label>Total amount</label>
                                <input id="purchase-amount" name="amount" readonly />
                                <small class="field-hint">Calculated automatically from quantity and unit price.</small>
                            </div>
                            <button type="submit">Post purchase</button>
                        </form>
                    """.trimIndent()
                )
                if (canDelete(session, MODULE_PURCHASE)) {
                    append(
                        """
                            <form method="post" action="/purchase/rollback" class="secondary-actions">
                                <button type="submit">Rollback last purchase</button>
                            </form>
                        </section>
                        """.trimIndent()
                    )
                } else {
                    append("</section>")
                }
            } else if (canDelete(session, MODULE_PURCHASE)) {
                append(permissionNotice("Creating purchases is not allowed for your roles."))
                append(
                    """
                    <section class="section-card">
                        <form method="post" action="/purchase/rollback" class="secondary-actions">
                            <button type="submit">Rollback last purchase</button>
                        </form>
                    </section>
                    """.trimIndent()
                )
            } else {
                append(permissionNotice("Changing purchase records is not allowed for your roles."))
            }
        }
        val content = templates.render(
            "pages/purchase.html",
            mapOf(
                "formSection" to formSection,
                "rows" to rows,
                "message" to messageBlock
            )
        )
        return layoutPage("Purchase", session, content)
    }

    private suspend fun productionPage(
        session: AuthSession,
        list: List<ProductProduction>,
        productsList: List<FinishedProduct>,
        employeesList: List<Employee>
    ): String {
        val productOptions = productsList.joinToString("\n") { p ->
            "<option value=\"${p.id}\">${html(p.name)}</option>"
        }
        val employeeOptions = employeesList.joinToString("\n") { e ->
            "<option value=\"${e.id}\">${html(e.fullName)}</option>"
        }
        val rows = list.joinToString("\n") { p ->
            "<tr><td>${p.productionDate.toLocalDate()}</td><td>${p.productName ?: p.productId}</td>" +
                "<td>${p.quantity}</td><td>${p.employeeName ?: p.employeeId}</td><td>" +
                "${rowActions(session, MODULE_PRODUCTION, deleteAction = "/production/delete", id = p.id)}</td></tr>"
        }
        val formSection = buildString {
            if (canEdit(session, MODULE_PRODUCTION)) {
                append(
                    """
                    <section class="section-card">
                        <h2>Production entry</h2>
                        <form method="post" action="/production/save" class="managed-form">
                            <div>
                                <label>Product</label>
                                <select name="productId">$productOptions</select>
                            </div>
                            <div>
                                <label>Employee</label>
                                <select name="employeeId">$employeeOptions</select>
                            </div>
                            <div>
                                <label>Date</label>
                                <input type="date" name="productionDate" />
                            </div>
                            <div>
                                <label>Quantity</label>
                                <input name="quantity" />
                            </div>
                            <button type="submit">Post production</button>
                        </form>
                    """.trimIndent()
                )
                if (canDelete(session, MODULE_PRODUCTION)) {
                    append(
                        """
                            <form method="post" action="/production/rollback" class="secondary-actions">
                                <button type="submit">Rollback last production</button>
                            </form>
                        </section>
                        """.trimIndent()
                    )
                } else {
                    append("</section>")
                }
            } else if (canDelete(session, MODULE_PRODUCTION)) {
                append(permissionNotice("Creating production records is not allowed for your roles."))
                append(
                    """
                    <section class="section-card">
                        <form method="post" action="/production/rollback" class="secondary-actions">
                            <button type="submit">Rollback last production</button>
                        </form>
                    </section>
                    """.trimIndent()
                )
            } else {
                append(permissionNotice("Changing production records is not allowed for your roles."))
            }
        }
        val content = templates.render(
            "pages/production.html",
            mapOf(
                "formSection" to formSection,
                "rows" to rows,
                "message" to ""
            )
        )
        return layoutPage("Production", session, content)
    }

    private suspend fun salesPage(
        session: AuthSession,
        list: List<ProductSale>,
        productsList: List<FinishedProduct>,
        employeesList: List<Employee>
    ): String {
        val productOptions = productsList.joinToString("\n") { p ->
            "<option value=\"${p.id}\" data-available=\"${format3(p.quantity)}\" " +
                "data-unit-cost=\"${format2(unitCost(p.amount, p.quantity))}\" " +
                "data-sale-price=\"${format2(calculateSaleUnitPrice(p.amount, p.quantity))}\">${html(p.name)}</option>"
        }
        val employeeOptions = employeesList.joinToString("\n") { e ->
            "<option value=\"${e.id}\">${html(e.fullName)}</option>"
        }
        val rows = list.joinToString("\n") { s ->
            "<tr><td>${s.saleDate.toLocalDate()}</td><td>${s.productName ?: s.productId}</td>" +
                "<td>${format3(s.quantity)}</td><td>${format2(s.amount)}</td><td>${s.employeeName ?: s.employeeId}</td><td>" +
                "${rowActions(session, MODULE_SALES, deleteAction = "/sales/delete", id = s.id)}</td></tr>"
        }
        val formSection = buildString {
            if (canEdit(session, MODULE_SALES)) {
                append(
                    """
                    <section class="section-card">
                        <h2>New sale</h2>
                        <form method="post" action="/sales/save" class="managed-form" id="sales-form">
                            <div>
                                <label>Product</label>
                                <select id="sales-product" name="productId">$productOptions</select>
                            </div>
                            <div>
                                <label>Employee</label>
                                <select name="employeeId">$employeeOptions</select>
                            </div>
                            <div>
                                <label>Date</label>
                                <input type="date" name="saleDate" />
                            </div>
                            <div>
                                <label>Available in stock</label>
                                <input id="sales-available" readonly />
                            </div>
                            <div>
                                <label>Quantity</label>
                                <input id="sales-quantity" name="quantity" />
                            </div>
                            <div>
                                <label>Cost per unit</label>
                                <input id="sales-unit-cost" readonly />
                            </div>
                            <div>
                                <label>Sale price (+15%)</label>
                                <input id="sales-unit-price" readonly />
                            </div>
                            <div>
                                <label>Total amount</label>
                                <input id="sales-amount" name="amount" readonly />
                                <small class="field-hint">Calculated automatically.</small>
                            </div>
                            <button type="submit">Post sale</button>
                        </form>
                    """.trimIndent()
                )
                if (canDelete(session, MODULE_SALES)) {
                    append(
                        """
                            <form method="post" action="/sales/rollback" class="secondary-actions">
                                <button type="submit">Rollback last sale</button>
                            </form>
                        </section>
                        """.trimIndent()
                    )
                } else {
                    append("</section>")
                }
            } else if (canDelete(session, MODULE_SALES)) {
                append(permissionNotice("Creating sales is not allowed for your roles."))
                append(
                    """
                    <section class="section-card">
                        <form method="post" action="/sales/rollback" class="secondary-actions">
                            <button type="submit">Rollback last sale</button>
                        </form>
                    </section>
                    """.trimIndent()
                )
            } else {
                append(permissionNotice("Changing sales records is not allowed for your roles."))
            }
        }
        val content = templates.render(
            "pages/sales.html",
            mapOf(
                "formSection" to formSection,
                "rows" to rows,
                "message" to ""
            )
        )
        return layoutPage("Sales", session, content)
    }

    private suspend fun reportsPage(
        session: AuthSession,
        raw: List<RawMaterialInventoryView>,
        products: List<ProductInventoryView>,
        sales: List<SalesExtendedView>
    ): String {
        val rawRows = raw.joinToString("\n") { r ->
            val unitCost = unitCost(r.currentAmount, r.currentQuantity)
            "<tr><td>${html(r.rawMaterialName)}</td><td>${r.unitName}</td><td>${r.currentQuantity}</td>" +
                "<td>${format2(unitCost)}</td><td>${format2(r.currentAmount)}</td></tr>"
        }
        val productRows = products.joinToString("\n") { p ->
            val unitCost = unitCost(p.currentAmount, p.currentQuantity)
            "<tr><td>${html(p.productName)}</td><td>${p.unitName}</td><td>${p.currentQuantity}</td>" +
                "<td>${format2(unitCost)}</td><td>${format2(p.currentAmount)}</td></tr>"
        }
        val salesRows = sales.joinToString("\n") { s ->
            val price = s.pricePerUnit ?: unitCost(s.amount, s.quantity)
            "<tr><td>${s.saleDate.toLocalDate()}</td><td>${html(s.productName)}</td>" +
                "<td>${s.quantity}</td><td>${format2(price)}</td><td>${format2(s.amount)}</td>" +
                "<td>${html(s.employeeName)}</td></tr>"
        }
        val content = templates.render(
            "pages/reports.html",
            mapOf(
                "rawRows" to rawRows,
                "productRows" to productRows,
                "salesRows" to salesRows
            )
        )
        return layoutPage("Reports", session, content)
    }

    private suspend fun budgetPage(session: AuthSession, list: List<Budget>): String {
        val creditList = credits.listAll()
        val rows = list.joinToString("\n") { b ->
            "<tr><td>${b.id}</td><td>${format2(b.budgetAmount)}</td></tr>"
        }
        val creditRows = creditList.joinToString("\n") { credit ->
            val status = if (credit.isActive) "Active" else "Closed"
            val payForm = if (canEdit(session, MODULE_BUDGET)) {
                "<form method=\"post\" action=\"/credits/pay\" style=\"display:inline\">" +
                    "<input type=\"hidden\" name=\"id\" value=\"${credit.id}\"/>" +
                    "<input name=\"paymentAmount\" placeholder=\"Pay\" style=\"width:84px\"/>" +
                    "<button type=\"submit\">Pay</button></form>"
            } else {
                ""
            }
            val deleteForm = if (canDelete(session, MODULE_BUDGET)) {
                "<form method=\"post\" action=\"/credits/delete\" style=\"display:inline\">" +
                    "<input type=\"hidden\" name=\"id\" value=\"${credit.id}\"/>" +
                    "<button type=\"submit\">Delete</button></form>"
            } else {
                ""
            }
            "<tr><td>${html(credit.bankName)}</td><td>${format2(credit.amount)}</td>" +
                "<td>${format2(credit.rate)}%</td><td>${credit.termMonths}</td><td>${credit.startDate}</td>" +
                "<td>${format2(credit.monthlyPayment)}</td><td>${format2(credit.remainingAmount)}</td><td>$status</td><td>" +
                listOf(payForm, deleteForm).filter { it.isNotBlank() }.joinToString(" ") +
                "</td></tr>"
        }
        val current = list.firstOrNull()?.budgetAmount ?: ""
        val currentValue = list.firstOrNull()?.budgetAmount ?: 0.0
        val creditFunds = creditList.filter { it.isActive }.sumOf { it.remainingAmount }
        val nonCreditFunds = (currentValue - creditFunds).coerceAtLeast(0.0)
        val bankOptions = creditBanks.joinToString("\n") { bank ->
            "<option value=\"${html(bank)}\">${html(bank)}</option>"
        }
        val budgetFormSection = editFormOrNotice(
            session,
            MODULE_BUDGET,
            """
            <section class="section-card">
                <h2>Operating budget</h2>
                <form method="post" action="/budget/save" class="managed-form compact-form">
                    <div>
                        <label>Amount</label>
                        <input name="amount" value="$current" />
                    </div>
                    <button type="submit">Save budget</button>
                </form>
            </section>
            """.trimIndent(),
            "Changing the budget is not allowed for your roles."
        )
        val creditFormSection = editFormOrNotice(
            session,
            MODULE_BUDGET,
            """
            <section class="section-card">
                <h2>Credit line</h2>
                <form method="post" action="/credits/save" class="managed-form">
                    <div>
                        <label>Bank</label>
                        <select name="bank">
                            $bankOptions
                        </select>
                    </div>
                    <div>
                        <label>Amount</label>
                        <input name="amount" />
                    </div>
                    <div>
                        <label>Rate (%)</label>
                        <input name="rate" />
                    </div>
                    <div>
                        <label>Term (months)</label>
                        <input name="termMonths" />
                    </div>
                    <div>
                        <label>Start date</label>
                        <input type="date" name="startDate" />
                    </div>
                    <button type="submit">Create credit</button>
                </form>
            </section>
            """.trimIndent(),
            "Creating credits is not allowed for your roles."
        )
        val content = templates.render(
            "pages/budget.html",
            mapOf(
                "budgetFormSection" to budgetFormSection,
                "creditFunds" to format2(creditFunds),
                "nonCreditFunds" to format2(nonCreditFunds),
                "rows" to rows,
                "creditFormSection" to creditFormSection,
                "creditRows" to creditRows
            )
        )
        return layoutPage("Budget", session, content)
    }

    private suspend fun adminUsersPage(
        session: AuthSession,
        users: List<UserWithRoles>,
        roles: List<RoleItem>,
        employeesList: List<Employee>,
        edit: UserWithRoles?,
        error: String?
    ): String {
        val rows = if (users.isEmpty()) {
            """
            <article class="users-empty-state">
                <strong>No users</strong>
            </article>
            """.trimIndent()
        } else {
            users.joinToString("\n") { u ->
                val roleLabel = if (u.roles.isEmpty()) "-" else html(u.roles.joinToString(", "))
                val statusLabel = if (u.isActive) "Active" else "Disabled"
                val employeeLabel = u.employeeName?.let { html(it) } ?: "No employee linked"
                val actions = rowActions(session, MODULE_USERS, "/admin/users?editId=${u.id}", "/admin/users/delete", u.id)
                """
                <article class="user-card">
                    <h3>${html(u.username)}</h3>
                    <div class="user-simple-line">$statusLabel</div>
                    <div class="user-simple-line">$employeeLabel</div>
                    <div class="user-simple-line">$roleLabel</div>
                    <div class="user-card-actions">${if (actions.isBlank()) "<span class=\"user-actions-muted\">No actions available</span>" else actions}</div>
                </article>
                """.trimIndent()
            }
        }
        val employeeOptions = buildString {
            append("<option value=\"\">Not linked</option>")
            employeesList.forEach { employee ->
                val selected = if (edit?.employeeId == employee.id) "selected" else ""
                append("<option value=\"${employee.id}\" $selected>${html(employee.fullName)}</option>")
            }
        }
        val roleChecks = roles.joinToString("\n") { r ->
            val checked = if (edit?.roles?.contains(r.name) == true) "checked" else ""
            "<label><input type=\"checkbox\" name=\"role_${r.id}\" $checked/> ${html(r.name)}</label><br/>"
        }
        val permissionCards = roles.joinToString("\n") { role ->
            val grantedCount = role.permissions.count { it.canEdit || it.canDelete }
            val permissionRows = role.permissions.joinToString("\n") { permission ->
                """
                <div class="role-permission-row">
                    <div class="role-permission-module">${html(permission.label)}</div>
                    <label class="role-permission-cell">
                        <input type="checkbox" name="${permission.moduleKey}_edit" ${if (permission.canEdit) "checked" else ""}/>
                    </label>
                    <label class="role-permission-cell">
                        <input type="checkbox" name="${permission.moduleKey}_delete" ${if (permission.canDelete) "checked" else ""}/>
                    </label>
                </div>
                """.trimIndent()
            }
            """
            <details class="role-permission-card role-permission-accordion" ${if (role.name.equals("Admin", ignoreCase = true)) "open" else ""}>
                <summary class="role-permission-summary">
                    <span class="role-permission-title">${html(role.name)}</span>
                    <span class="role-permission-count">$grantedCount/${role.permissions.size}</span>
                </summary>
                <form method="post" action="/admin/roles/permissions/save" class="role-permission-form role-permission-form-stacked">
                    <input type="hidden" name="roleId" value="${role.id}" />
                    <div class="role-permission-table">
                        <div class="role-permission-row role-permission-row-head">
                            <div class="role-permission-module">Module</div>
                            <div class="role-permission-col-head">Edit</div>
                            <div class="role-permission-col-head">Delete</div>
                        </div>
                        $permissionRows
                    </div>
                    ${if (canEdit(session, MODULE_USERS)) "<div class=\"role-permission-actions\"><button type=\"submit\" class=\"role-permission-save\">Save</button></div>" else ""}
                </form>
            </details>
            """.trimIndent()
        }
        val userFormSection = editFormOrNotice(
            session,
            MODULE_USERS,
            """
            <section class="section-card">
                <h2>User account</h2>
                <form method="post" action="/admin/users/save" class="managed-form">
                    <input type="hidden" name="id" value="${edit?.id ?: 0}" />
                    <div>
                        <label>Username</label>
                        <input name="username" value="${html(edit?.username ?: "")}" />
                    </div>
                    <div>
                        <label>Password</label>
                        <input name="password" type="password" />
                        <small class="field-hint">Leave empty when editing if the password should remain unchanged.</small>
                    </div>
                    <div>
                        <label>Employee</label>
                        <select name="employeeId">$employeeOptions</select>
                        <small class="field-hint">Link this user to one employee record from the directory.</small>
                    </div>
                    <div class="checkbox-field">
                        <label>Active</label>
                        <label class="inline-check"><input type="checkbox" name="isActive" ${if (edit?.isActive != false) "checked" else ""} /> Account is active</label>
                    </div>
                    <div class="roles-field">
                        <label>Roles</label>
                        <div class="role-checklist">$roleChecks</div>
                        <small class="field-hint">Select only the roles this user should actually have.</small>
                    </div>
                    <div class="form-actions">
                        <button type="submit">Save user</button>
                        ${cancelEditLink("/admin/users", edit != null)}
                    </div>
                </form>
            </section>
            """.trimIndent(),
            "Editing users is not allowed for your roles."
        )
        val rolePermissionsSection = if (roles.isEmpty()) {
            ""
        } else {
            """
            <section class="section-card role-permissions-section">
                <div class="section-head">
                    <h2>Role permissions</h2>
                </div>
                <p class="field-hint">Open one role and change access only for the modules that it should manage.</p>
                <div class="role-permissions-grid">
                    $permissionCards
                </div>
            </section>
            """.trimIndent()
        }
        val errorBlock = if (error.isNullOrBlank()) "" else "<p style=\"color:red\">${html(error)}</p>"
        val content = templates.render(
            "pages/users.html",
            mapOf(
                "error" to errorBlock,
                "userFormSection" to userFormSection,
                "rolePermissionsSection" to rolePermissionsSection,
                "rows" to rows
            )
        )
        return layoutPage("Users", session, content)
    }

    private fun simplePage(title: String, content: String): String {
        return """
            <html>
            <head><title>${html(title)}</title></head>
            <body>
                $content
            </body>
            </html>
        """.trimIndent()
    }

    private fun parseDateTime(value: String?): LocalDateTime {
        if (value.isNullOrBlank()) return LocalDateTime.now()
        val trimmed = value.trim()
        return runCatching {
            if (trimmed.length == 10) {
                LocalDate.parse(trimmed, DateTimeFormatter.ISO_LOCAL_DATE).atStartOfDay()
            } else {
                LocalDateTime.parse(trimmed, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            }
        }.getOrElse { LocalDateTime.now() }
    }

    private fun parseDateTimeNullable(value: String?): LocalDateTime? {
        if (value.isNullOrBlank()) return null
        val trimmed = value.trim()
        return runCatching {
            if (trimmed.length == 10) {
                LocalDate.parse(trimmed, DateTimeFormatter.ISO_LOCAL_DATE).atStartOfDay()
            } else {
                LocalDateTime.parse(trimmed, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            }
        }.getOrNull()
    }

    private fun unitCost(total: Double, quantity: Double): Double {
        if (quantity <= 0) return 0.0
        return total / quantity
    }

    private fun calculateSaleUnitPrice(total: Double, quantity: Double): Double {
        val cost = unitCost(total, quantity)
        return if (cost <= 0.0) 0.0 else cost * (1 + SALE_MARKUP)
    }

    private fun format2(value: Double): String = String.format("%.2f", value)
    private fun format3(value: Double): String = String.format("%.3f", value)

    private fun html(value: String): String {
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
    }
}
