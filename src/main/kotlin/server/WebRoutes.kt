package server

import io.vertx.ext.web.Router
import models.Employee
import models.FinishedProduct
import services.ValidationException
import models.Ingredient
import models.MeasurementUnit
import models.Position
import models.RawMaterial
import java.time.LocalDate
import java.util.UUID

internal fun AppServer.registerWebRoutes(router: Router) {
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
        val sid = readSid(ctx)
        if (sid != null) sessions.remove(sid)
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
        val edit = if (editId != null) units.getById(editId) else null
        ctx.response()
            .putHeader("Content-Type", "text/html; charset=utf-8")
            .end(unitsPage(session, list, edit))
    }

    router.post("/units/save").coroutineHandler { ctx ->
        val session = requireAuth(ctx, setOf("Admin", "Purchasing"), SessionPermission.EDIT, MODULE_UNITS) ?: return@coroutineHandler
        val id = ctx.request().getParam("id")?.toIntOrNull() ?: 0
        val name = ctx.request().getParam("name")?.trim().orEmpty()
        if (name.isBlank()) { badRequest(ctx, "Name is required"); return@coroutineHandler }
        if (id == 0) units.create(name) else units.update(MeasurementUnit(id, name))
        redirect(ctx, "/units")
    }

    router.post("/units/delete").coroutineHandler { ctx ->
        requireAuth(ctx, setOf("Admin", "Purchasing"), SessionPermission.DELETE, MODULE_UNITS) ?: return@coroutineHandler
        val id = ctx.request().getParam("id")?.toIntOrNull() ?: 0
        if (id > 0) units.delete(id)
        redirect(ctx, "/units")
    }

    router.get("/positions").coroutineHandler { ctx ->
        val session = requireAuth(ctx, setOf("Admin")) ?: return@coroutineHandler
        val list = positions.listAll()
        val editId = ctx.request().getParam("editId")?.toShortOrNull()
        val edit = if (editId != null) positions.getById(editId) else null
        ctx.response()
            .putHeader("Content-Type", "text/html; charset=utf-8")
            .end(positionsPage(session, list, edit))
    }

    router.post("/positions/save").coroutineHandler { ctx ->
        val session = requireAuth(ctx, setOf("Admin"), SessionPermission.EDIT, MODULE_POSITIONS) ?: return@coroutineHandler
        val id = ctx.request().getParam("id")?.toShortOrNull() ?: 0
        val title = ctx.request().getParam("title")?.trim().orEmpty()
        if (title.isBlank()) { badRequest(ctx, "Title is required"); return@coroutineHandler }
        if (id == 0.toShort()) positions.create(title) else positions.update(Position(id, title))
        redirect(ctx, "/positions")
    }

    router.post("/positions/delete").coroutineHandler { ctx ->
        requireAuth(ctx, setOf("Admin"), SessionPermission.DELETE, MODULE_POSITIONS) ?: return@coroutineHandler
        val id = ctx.request().getParam("id")?.toShortOrNull() ?: 0
        if (id > 0) positions.delete(id)
        redirect(ctx, "/positions")
    }

    router.get("/employees").coroutineHandler { ctx ->
        val session = requireAuth(ctx, setOf("Admin")) ?: return@coroutineHandler
        val list = employees.listAll()
        val positionsList = positions.listAll()
        val editId = ctx.request().getParam("editId")?.toIntOrNull()
        val edit = if (editId != null) employees.getById(editId) else null
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
            badRequest(ctx, "FullName and Salary are required"); return@coroutineHandler
        }
        if (id == 0) {
            employees.create(fullName, dateOfBirth, positionId, salary, homeAddress)
        } else {
            employees.update(Employee(id, fullName, dateOfBirth, positionId, salary, homeAddress, null))
        }
        redirect(ctx, "/employees")
    }

    router.post("/employees/delete").coroutineHandler { ctx ->
        requireAuth(ctx, setOf("Admin"), SessionPermission.DELETE, MODULE_EMPLOYEES) ?: return@coroutineHandler
        val id = ctx.request().getParam("id")?.toIntOrNull() ?: 0
        if (id > 0) employees.delete(id)
        redirect(ctx, "/employees")
    }

    router.get("/salary").coroutineHandler { ctx ->
        val session = requireAuth(ctx, null) ?: return@coroutineHandler
        ctx.response()
            .putHeader("Content-Type", "text/html; charset=utf-8")
            .end(salaryPage(session, salaries.listAll(), employees.listAll()))
    }

    router.get("/salary/").coroutineHandler { ctx ->
        requireAuth(ctx, null) ?: return@coroutineHandler
        redirect(ctx, "/salary")
    }

    router.post("/salary/save").coroutineHandler { ctx ->
        requireAuth(ctx, null, SessionPermission.EDIT, MODULE_SALARY) ?: return@coroutineHandler
        val employeeId = ctx.request().getParam("employeeId")?.toIntOrNull()
        if (employeeId == null) { badRequest(ctx, "Employee is required"); return@coroutineHandler }
        val amountParam = ctx.request().getParam("amount")?.toDoubleOrNull()
        val paymentDate = parseDateTime(ctx.request().getParam("paymentDate"))
        val noteRaw = ctx.request().getParam("note")?.trim()
        val note = if (noteRaw.isNullOrBlank()) null else noteRaw
        salaries.create(employeeId, amountParam, paymentDate, note)
        redirect(ctx, "/salary")
    }

    router.post("/salary/delete").coroutineHandler { ctx ->
        requireAuth(ctx, null, SessionPermission.DELETE, MODULE_SALARY) ?: return@coroutineHandler
        val id = ctx.request().getParam("id")?.toIntOrNull() ?: 0
        if (id > 0) salaries.delete(id)
        redirect(ctx, "/salary")
    }

    router.post("/salary/rollback").coroutineHandler { ctx ->
        requireAuth(ctx, null, SessionPermission.DELETE, MODULE_SALARY) ?: return@coroutineHandler
        salaries.deleteLast()
        redirect(ctx, "/salary")
    }

    router.get("/raw-materials").coroutineHandler { ctx ->
        val session = requireAuth(ctx, setOf("Admin", "Purchasing")) ?: return@coroutineHandler
        val list = rawMaterials.listAll()
        val unitsList = units.listAll()
        val editId = ctx.request().getParam("editId")?.toIntOrNull()
        val edit = if (editId != null) rawMaterials.getById(editId) else null
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
            badRequest(ctx, "Name and UnitId are required"); return@coroutineHandler
        }
        if (id == 0) {
            rawMaterials.create(name, unitId, 0.0, 0.0)
        } else {
            val existing = rawMaterials.getById(id) ?: run { badRequest(ctx, "Raw material not found"); return@coroutineHandler }
            rawMaterials.update(RawMaterial(id, name, unitId, existing.quantity, existing.amount, null))
        }
        redirect(ctx, "/raw-materials")
    }

    router.post("/raw-materials/delete").coroutineHandler { ctx ->
        requireAuth(ctx, setOf("Admin", "Purchasing"), SessionPermission.DELETE, MODULE_RAW_MATERIALS) ?: return@coroutineHandler
        val id = ctx.request().getParam("id")?.toIntOrNull() ?: 0
        if (id > 0) rawMaterials.delete(id)
        redirect(ctx, "/raw-materials")
    }

    router.get("/products").coroutineHandler { ctx ->
        val session = requireAuth(ctx, setOf("Admin", "Sales", "Production")) ?: return@coroutineHandler
        val list = products.listAll()
        val unitsList = units.listAll()
        val editId = ctx.request().getParam("editId")?.toIntOrNull()
        val edit = if (editId != null) products.getById(editId) else null
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
            badRequest(ctx, "Name, UnitId and Cost are required"); return@coroutineHandler
        }
        if (id == 0) {
            products.create(name, unitId, 0.0, amountParam ?: 0.0)
        } else {
            val existing = products.getById(id) ?: run { badRequest(ctx, "Product not found"); return@coroutineHandler }
            val amount = if (unitCost != null) existing.quantity * unitCost else amountParam ?: existing.amount
            products.update(FinishedProduct(id, name, unitId, existing.quantity, amount, null))
        }
        redirect(ctx, "/products")
    }

    router.post("/products/delete").coroutineHandler { ctx ->
        requireAuth(ctx, setOf("Admin", "Sales", "Production"), SessionPermission.DELETE, MODULE_PRODUCTS) ?: return@coroutineHandler
        val id = ctx.request().getParam("id")?.toIntOrNull() ?: 0
        if (id > 0) products.delete(id)
        redirect(ctx, "/products")
    }

    router.get("/ingredients").coroutineHandler { ctx ->
        val session = requireAuth(ctx, setOf("Admin", "Production")) ?: return@coroutineHandler
        val list = ingredients.listAll()
        val editId = ctx.request().getParam("editId")?.toIntOrNull()
        val edit = if (editId != null) ingredients.getById(editId) else null
        ctx.response()
            .putHeader("Content-Type", "text/html; charset=utf-8")
            .end(ingredientsPage(session, list, products.listAll(), rawMaterials.listAll(), edit))
    }

    router.post("/ingredients/save").coroutineHandler { ctx ->
        requireAuth(ctx, setOf("Admin", "Production"), SessionPermission.EDIT, MODULE_INGREDIENTS) ?: return@coroutineHandler
        val id = ctx.request().getParam("id")?.toIntOrNull() ?: 0
        val productId = ctx.request().getParam("productId")?.toIntOrNull()
        val rawMaterialId = ctx.request().getParam("rawMaterialId")?.toIntOrNull()
        val quantity = ctx.request().getParam("quantity")?.toDoubleOrNull()
        if (productId == null || rawMaterialId == null || quantity == null) {
            badRequest(ctx, "ProductId, RawMaterialId, Quantity are required"); return@coroutineHandler
        }
        if (id == 0) ingredients.create(productId, rawMaterialId, quantity)
        else ingredients.update(Ingredient(id, productId, rawMaterialId, quantity, null, null))
        redirect(ctx, "/ingredients")
    }

    router.post("/ingredients/delete").coroutineHandler { ctx ->
        requireAuth(ctx, setOf("Admin", "Production"), SessionPermission.DELETE, MODULE_INGREDIENTS) ?: return@coroutineHandler
        val id = ctx.request().getParam("id")?.toIntOrNull() ?: 0
        if (id > 0) ingredients.delete(id)
        redirect(ctx, "/ingredients")
    }

    router.get("/purchase").coroutineHandler { ctx ->
        val session = requireAuth(ctx, setOf("Admin", "Purchasing")) ?: return@coroutineHandler
        ctx.response()
            .putHeader("Content-Type", "text/html; charset=utf-8")
            .end(purchasePage(session, purchaseService.listAll(), rawMaterials.listAll(), employees.listAll(), null))
    }

    router.post("/purchase/save").coroutineHandler { ctx ->
        requireAuth(ctx, setOf("Admin", "Purchasing"), SessionPermission.EDIT, MODULE_PURCHASE) ?: return@coroutineHandler
        val rawMaterialId = ctx.request().getParam("rawMaterialId")?.toIntOrNull()
        val quantity = ctx.request().getParam("quantity")?.toDoubleOrNull()
        val unitPrice = ctx.request().getParam("unitPrice")?.toDoubleOrNull()
        val employeeId = ctx.request().getParam("employeeId")?.toIntOrNull()
        val purchaseDate = parseDateTime(ctx.request().getParam("purchaseDate"))
        if (rawMaterialId == null || quantity == null || unitPrice == null || employeeId == null) {
            badRequest(ctx, "RawMaterialId, Quantity, UnitPrice, EmployeeId are required"); return@coroutineHandler
        }
        purchaseService.create(rawMaterialId, quantity, unitPrice, purchaseDate, employeeId)
        redirect(ctx, "/purchase")
    }

    router.post("/purchase/delete").coroutineHandler { ctx ->
        requireAuth(ctx, setOf("Admin", "Purchasing"), SessionPermission.DELETE, MODULE_PURCHASE) ?: return@coroutineHandler
        val id = ctx.request().getParam("id")?.toIntOrNull() ?: 0
        if (id > 0) purchaseService.delete(id)
        redirect(ctx, "/purchase")
    }

    router.post("/purchase/rollback").coroutineHandler { ctx ->
        requireAuth(ctx, setOf("Admin", "Purchasing"), SessionPermission.DELETE, MODULE_PURCHASE) ?: return@coroutineHandler
        purchaseService.rollback()
        redirect(ctx, "/purchase")
    }

    router.get("/production").coroutineHandler { ctx ->
        val session = requireAuth(ctx, setOf("Admin", "Production")) ?: return@coroutineHandler
        ctx.response()
            .putHeader("Content-Type", "text/html; charset=utf-8")
            .end(productionPage(session, production.listAll(), products.listAll(), employees.listAll()))
    }

    router.post("/production/save").coroutineHandler { ctx ->
        requireAuth(ctx, setOf("Admin", "Production"), SessionPermission.EDIT, MODULE_PRODUCTION) ?: return@coroutineHandler
        val productId = ctx.request().getParam("productId")?.toIntOrNull()
        val quantity = ctx.request().getParam("quantity")?.toDoubleOrNull()
        val employeeId = ctx.request().getParam("employeeId")?.toIntOrNull()
        val productionDate = parseDateTime(ctx.request().getParam("productionDate"))
        if (productId == null || quantity == null || employeeId == null) {
            badRequest(ctx, "ProductId, Quantity, EmployeeId are required"); return@coroutineHandler
        }
        production.create(productId, quantity, productionDate, employeeId)
        redirect(ctx, "/production")
    }

    router.post("/production/delete").coroutineHandler { ctx ->
        requireAuth(ctx, setOf("Admin", "Production"), SessionPermission.DELETE, MODULE_PRODUCTION) ?: return@coroutineHandler
        val id = ctx.request().getParam("id")?.toIntOrNull() ?: 0
        if (id > 0) production.delete(id)
        redirect(ctx, "/production")
    }

    router.post("/production/rollback").coroutineHandler { ctx ->
        requireAuth(ctx, setOf("Admin", "Production"), SessionPermission.DELETE, MODULE_PRODUCTION) ?: return@coroutineHandler
        production.deleteLast()
        redirect(ctx, "/production")
    }

    router.get("/sales").coroutineHandler { ctx ->
        val session = requireAuth(ctx, setOf("Admin", "Sales")) ?: return@coroutineHandler
        ctx.response()
            .putHeader("Content-Type", "text/html; charset=utf-8")
            .end(salesPage(session, saleService.listAll(), products.listAll(), employees.listAll()))
    }

    router.post("/sales/save").coroutineHandler { ctx ->
        requireAuth(ctx, setOf("Admin", "Sales"), SessionPermission.EDIT, MODULE_SALES) ?: return@coroutineHandler
        val productId = ctx.request().getParam("productId")?.toIntOrNull()
        val quantity = ctx.request().getParam("quantity")?.toDoubleOrNull()
        val employeeId = ctx.request().getParam("employeeId")?.toIntOrNull()
        val saleDate = parseDateTime(ctx.request().getParam("saleDate"))
        if (productId == null || quantity == null || employeeId == null) {
            badRequest(ctx, "ProductId, Quantity, EmployeeId are required"); return@coroutineHandler
        }
        saleService.create(productId, quantity, saleDate, employeeId)
        redirect(ctx, "/sales")
    }

    router.post("/sales/delete").coroutineHandler { ctx ->
        requireAuth(ctx, setOf("Admin", "Sales"), SessionPermission.DELETE, MODULE_SALES) ?: return@coroutineHandler
        val id = ctx.request().getParam("id")?.toIntOrNull() ?: 0
        if (id > 0) saleService.delete(id)
        redirect(ctx, "/sales")
    }

    router.post("/sales/rollback").coroutineHandler { ctx ->
        requireAuth(ctx, setOf("Admin", "Sales"), SessionPermission.DELETE, MODULE_SALES) ?: return@coroutineHandler
        saleService.rollback()
        redirect(ctx, "/sales")
    }

    router.get("/reports").coroutineHandler { ctx ->
        val session = requireAuth(ctx, setOf("Admin", "Sales")) ?: return@coroutineHandler
        ctx.response()
            .putHeader("Content-Type", "text/html; charset=utf-8")
            .end(reportsPage(session, rawInventory.listAll(), productInventory.listAll(), salesExtended.listAll()))
    }

    router.get("/budget").coroutineHandler { ctx ->
        val session = requireAuth(ctx, setOf("Admin")) ?: return@coroutineHandler
        ctx.response()
            .putHeader("Content-Type", "text/html; charset=utf-8")
            .end(budgetPage(session, budgets.listAll()))
    }

    router.post("/budget/save").coroutineHandler { ctx ->
        requireAuth(ctx, setOf("Admin"), SessionPermission.EDIT, MODULE_BUDGET) ?: return@coroutineHandler
        val amount = ctx.request().getParam("amount")?.toDoubleOrNull()
        if (amount == null) { badRequest(ctx, "Amount is required"); return@coroutineHandler }
        budgets.create(amount)
        redirect(ctx, "/budget")
    }

    router.post("/credits/save").coroutineHandler { ctx ->
        requireAuth(ctx, setOf("Admin"), SessionPermission.EDIT, MODULE_BUDGET) ?: return@coroutineHandler
        val bankName = ctx.request().getParam("bank")?.trim().orEmpty()
        val amount = ctx.request().getParam("amount")?.toDoubleOrNull()
        val rate = ctx.request().getParam("rate")?.toDoubleOrNull()
        val termMonths = ctx.request().getParam("termMonths")?.toIntOrNull()
        val startDateStr = ctx.request().getParam("startDate")?.trim() ?: ""
        val startDate = if (startDateStr.isBlank()) null else try { LocalDate.parse(startDateStr) } catch (e: Exception) { null }
        if (bankName.isBlank() || amount == null || rate == null || termMonths == null || startDate == null) {
            badRequest(ctx, "Bank, Amount, Rate, Term and Start date are required"); return@coroutineHandler
        }
        creditService.create(bankName, amount, rate, termMonths, startDate)
        redirect(ctx, "/budget")
    }

    router.post("/credits/pay").coroutineHandler { ctx ->
        requireAuth(ctx, setOf("Admin"), SessionPermission.EDIT, MODULE_BUDGET) ?: return@coroutineHandler
        val id = ctx.request().getParam("id")?.toIntOrNull() ?: 0
        val paymentAmount = ctx.request().getParam("paymentAmount")?.toDoubleOrNull()
        if (paymentAmount == null) { badRequest(ctx, "Payment amount is required"); return@coroutineHandler }
        creditService.pay(id, paymentAmount)
        redirect(ctx, "/budget")
    }

    // Public production request portal (no auth required)
    router.get("/request").coroutineHandler { ctx ->
        val submittedId = ctx.request().getParam("submitted")?.toIntOrNull()
        val productsList = products.listAll()
        ctx.response()
            .putHeader("Content-Type", "text/html; charset=utf-8")
            .end(publicRequestPage(productsList, submittedId, null, null))
    }

    router.post("/request/submit").coroutineHandler { ctx ->
        val applicantName = ctx.request().getParam("applicantName")?.trim().orEmpty()
        val productId = ctx.request().getParam("productId")?.toIntOrNull()
        val quantity = ctx.request().getParam("quantity")?.toDoubleOrNull()
        if (applicantName.isBlank() || productId == null || quantity == null) {
            redirect(ctx, "/request?error=Applicant+name%2C+product+and+quantity+are+required")
            return@coroutineHandler
        }
        val id = try {
            productionRequestService.createOnly(applicantName, productId, quantity)
        } catch (e: ValidationException) {
            redirect(ctx, "/request?error=${java.net.URLEncoder.encode(e.message ?: "Validation error", "UTF-8")}")
            return@coroutineHandler
        }
        redirect(ctx, "/request?submitted=$id")
    }

    router.get("/request/status").coroutineHandler { ctx ->
        val id = ctx.request().getParam("id")?.toIntOrNull()
        val request = if (id != null) productionRequestService.getById(id) else null
        val productsList = products.listAll()
        ctx.response()
            .putHeader("Content-Type", "text/html; charset=utf-8")
            .end(publicRequestPage(productsList, null, id, request))
    }

    router.get("/production-requests").coroutineHandler { ctx ->
        val session = requireAuth(ctx, setOf("Admin", "Production")) ?: return@coroutineHandler
        ctx.response()
            .putHeader("Content-Type", "text/html; charset=utf-8")
            .end(productionRequestsPage(session, productionRequestService.listAll(), products.listAll()))
    }

    router.get("/production-requests/cards").coroutineHandler { ctx ->
        val session = requireAuth(ctx, setOf("Admin", "Production")) ?: return@coroutineHandler
        val requests = productionRequestService.listAll()
        apiJson(
            ctx,
            mapOf(
                "signature" to productionRequestSignature(requests),
                "html" to renderProductionRequestCards(session, requests)
            )
        )
    }

    router.post("/production-requests/submit").coroutineHandler { ctx ->
        requireAuth(ctx, setOf("Admin", "Production"), SessionPermission.EDIT, MODULE_PRODUCTION_REQUESTS) ?: return@coroutineHandler
        val applicantName = ctx.request().getParam("applicantName")?.trim().orEmpty()
        val productId = ctx.request().getParam("productId")?.toIntOrNull()
        val quantity = ctx.request().getParam("quantity")?.toDoubleOrNull()
        if (applicantName.isBlank() || productId == null || quantity == null) {
            badRequest(ctx, "Applicant name, product and quantity are required"); return@coroutineHandler
        }
        try {
            productionRequestService.submit(applicantName, productId, quantity)
        } catch (e: ValidationException) {
            badRequest(ctx, e.message ?: "Validation error"); return@coroutineHandler
        } catch (e: Exception) {
            badRequest(ctx, "Processing failed: ${e.message}"); return@coroutineHandler
        }
        redirect(ctx, "/production-requests")
    }

    router.post("/production-requests/process").coroutineHandler { ctx ->
        requireAuth(ctx, setOf("Admin", "Production"), SessionPermission.EDIT, MODULE_PRODUCTION_REQUESTS) ?: return@coroutineHandler
        val id = ctx.request().getParam("id")?.toIntOrNull() ?: 0
        if (id > 0) {
            try {
                productionRequestService.processById(id)
            } catch (e: ValidationException) {
                badRequest(ctx, e.message ?: "Processing failed"); return@coroutineHandler
            }
        }
        redirect(ctx, "/production-requests")
    }

    router.post("/production-requests/delete").coroutineHandler { ctx ->
        requireAuth(ctx, setOf("Admin", "Production"), SessionPermission.DELETE, MODULE_PRODUCTION_REQUESTS) ?: return@coroutineHandler
        val id = ctx.request().getParam("id")?.toIntOrNull() ?: 0
        if (id > 0) productionRequestService.delete(id)
        redirect(ctx, "/production-requests")
    }

    router.post("/credits/delete").coroutineHandler { ctx ->
        requireAuth(ctx, setOf("Admin"), SessionPermission.DELETE, MODULE_BUDGET) ?: return@coroutineHandler
        val id = ctx.request().getParam("id")?.toIntOrNull() ?: 0
        if (id > 0) creditService.delete(id)
        redirect(ctx, "/budget")
    }

    router.get("/admin/users").coroutineHandler { ctx ->
        val session = requireAuth(ctx, setOf("Admin")) ?: return@coroutineHandler
        val users = authRepo.getUsersWithRoles()
        val roles = authRepo.getRoles()
        val editId = ctx.request().getParam("editId")?.toIntOrNull()
        val edit = if (editId != null) users.firstOrNull { it.id == editId } else null
        ctx.response()
            .putHeader("Content-Type", "text/html; charset=utf-8")
            .end(adminUsersPage(session, users, roles, employees.listAll(), edit, null))
    }

    router.post("/admin/users/save").coroutineHandler { ctx ->
        requireAuth(ctx, setOf("Admin"), SessionPermission.EDIT, MODULE_USERS) ?: return@coroutineHandler
        val id = ctx.request().getParam("id")?.toIntOrNull() ?: 0
        val username = ctx.request().getParam("username")?.trim().orEmpty()
        val password = ctx.request().getParam("password")?.trim().orEmpty()
        val isActive = ctx.request().getParam("isActive") == "on"
        val employeeId = ctx.request().getParam("employeeId")?.toIntOrNull()
        val roles = authRepo.getRoles()
        val selectedRoleIds = roles.filter { ctx.request().getParam("role_${it.id}") == "on" }.map { it.id }
        if (username.isBlank()) {
            redirectWithAlert(ctx, "/admin/users", "Username is required"); return@coroutineHandler
        }
        if (id == 0) {
            if (password.isBlank()) {
                redirectWithAlert(ctx, "/admin/users", "Password is required for new user"); return@coroutineHandler
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
        if (id == session.userId) {
            redirectWithAlert(ctx, "/admin/users", "Cannot delete the account you are currently logged in as.")
            return@coroutineHandler
        }
        if (id > 0) authRepo.deleteUser(id)
        redirect(ctx, "/admin/users")
    }

    router.post("/admin/roles/permissions/save").coroutineHandler { ctx ->
        requireAuth(ctx, setOf("Admin"), SessionPermission.EDIT, MODULE_USERS) ?: return@coroutineHandler
        val roleId = ctx.request().getParam("roleId")?.toIntOrNull() ?: 0
        if (roleId <= 0) { badRequest(ctx, "Role is required"); return@coroutineHandler }
        models.MODULE_PERMISSION_LABELS.keys.forEach { moduleKey ->
            authRepo.saveRolePermission(
                roleId = roleId,
                moduleKey = moduleKey,
                canEdit = ctx.request().getParam("${moduleKey}_edit") == "on",
                canDelete = ctx.request().getParam("${moduleKey}_delete") == "on"
            )
        }
        redirect(ctx, "/admin/users")
    }
}
